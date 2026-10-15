package dev.localai.chat.llm

/**
 * Processa a saída incremental do llama.cpp, filtrando tokens especiais
 * de controle (ChatML, Llama-3, etc.) e detectando blocos de thinking.
 *
 * Mantém um buffer para lidar com marcadores fragmentados entre chunks.
 * Nunca emite fragmentos parciais de marcadores especiais para a UI.
 */
class TokenStreamProcessor {

    enum class Phase {
        RESPONSE,
        THINKING,
    }

    data class Output(
        val visibleDelta: String = "",
        val thinkingDelta: String = "",
        val phase: Phase = Phase.RESPONSE,
        val thinkingJustStarted: Boolean = false,
        val thinkingJustEnded: Boolean = false,
        /** O modelo comecou um novo turno (ex.: <start_of_turn>): a geracao deve ser interrompida. */
        val turnBoundaryReached: Boolean = false,
    )

    private val buffer = StringBuilder()
    private var phase: Phase = Phase.RESPONSE
    private var pendingEmit = StringBuilder()
    private var terminated = false

    // Marcadores de controle conhecidos (ChatML, Llama-3, Gemma, Mistral, etc.)
    private val specialMarkers = listOf(
        "<|im_start|>",
        "<|im_end|>",
        "<|endoftext|>",
        "<start_of_turn>",
        "<end_of_turn>",
        "<|im_sep|>",
        "<|end|>",
        "<|eot_id|>",
        "<|start_header_id|>",
        "<|end_header_id|>",
        "<|eom_id|>",
        "<|end_of_text|>",
        "<|fim_prefix|>",
        "<|fim_suffix|>",
        "<|fim_middle|>",
        "<|fim_pad|>",
        "<|ghissue|>",
        "<|meta_sep|>",
        "<|assistant|>",
        "<|user|>",
        "<|system|>",
        "[INST]",
        "[/INST]",
        "<<SYS>>",
        "<</SYS>>",
        "<s>",
        "</s>",
        "<bos>",
        "<eos>",
        "<pad>",
        "<unk>",
    ).sortedByDescending { it.length }

    // Marcadores de thinking (vários formatos usados por modelos)
    private val thinkStartMarkers = listOf(
        "<think>",
        "<thinking>",
        "<|think|>",
        "<|thinking|>",
        "◁think▷",
    )
    private val thinkEndMarkers = listOf(
        "</think>",
        "</thinking>",
        "<|/think|>",
        "<|/thinking|>",
        "◁/think▷",
    )

    // Inicio de um NOVO turno emitido pelo proprio modelo = fim da resposta (estrutural, nao
    // uma troca de palavras: "user"/"assistant" em texto normal nunca sao tocados).
    private val turnStartMarkers = listOf(
        "<start_of_turn>",
        "<|im_start|>",
        "<|start_header_id|>",
    )

    private val allMarkers: List<String> by lazy {
        (specialMarkers + thinkStartMarkers + thinkEndMarkers)
            .distinct()
            .sortedByDescending { it.length }
    }

    /**
     * Alimenta um novo pedaço de texto vindo do callback JNI.
     * Retorna o que deve ser mostrado (ou adicionado ao thinking).
     */
    fun feed(chunk: String): Output {
        if (terminated) return Output(phase = phase, turnBoundaryReached = true)
        if (chunk.isEmpty()) return Output(phase = phase)
        buffer.append(chunk)

        val turn = findEarliestMarker(buffer.toString(), turnStartMarkers)
        if (turn != null) {
            // Mantem so o que veio antes do marcador; o marcador e tudo depois dele sao descartados.
            buffer.setLength(turn.index)
            terminated = true
            val before = processBuffer()
            val rest = flush()
            return Output(
                visibleDelta = before.visibleDelta + rest.visibleDelta,
                thinkingDelta = before.thinkingDelta + rest.thinkingDelta,
                phase = phase,
                thinkingJustStarted = before.thinkingJustStarted,
                thinkingJustEnded = before.thinkingJustEnded,
                turnBoundaryReached = true,
            )
        }
        return processBuffer()
    }

    /** Força o flush de qualquer conteúdo restante no buffer (fim da geração). */
    fun flush(): Output {
        val remaining = buffer.toString()
        buffer.clear()
        if (remaining.isEmpty()) return Output(phase = phase)

        // At end-of-generation, do not leak a truncated special/thinking marker.
        // A genuine partial marker is indistinguishable from a truncated token,
        // so discard only the suffix that is a prefix of a known marker.
        val holdLen = longestPrefixMatch(remaining)
        val safe = remaining.substring(0, remaining.length - holdLen)
        return when (phase) {
            Phase.THINKING -> Output(thinkingDelta = safe, phase = phase)
            Phase.RESPONSE -> Output(visibleDelta = stripSpecials(safe), phase = phase)
        }
    }

    fun reset() {
        buffer.clear()
        pendingEmit.clear()
        phase = Phase.RESPONSE
        terminated = false
    }

    private fun processBuffer(): Output {
        var visible = ""
        var thinking = ""
        var justStarted = false
        var justEnded = false

        while (buffer.isNotEmpty()) {
            val text = buffer.toString()

            // 1. Procurar início de thinking (só se ainda não estiver pensando)
            if (phase == Phase.RESPONSE) {
                val start = findEarliestMarker(text, thinkStartMarkers)
                if (start != null) {
                    // Emitir o que veio antes do marcador (já limpo)
                    val before = text.substring(0, start.index)
                    visible += stripSpecials(before)
                    // Consumir até o final do marcador
                    buffer.delete(0, start.index + start.marker.length)
                    phase = Phase.THINKING
                    justStarted = true
                    continue
                }
            }

            // 2. Procurar fim de thinking
            if (phase == Phase.THINKING) {
                val end = findEarliestMarker(text, thinkEndMarkers)
                if (end != null) {
                    thinking += text.substring(0, end.index)
                    buffer.delete(0, end.index + end.marker.length)
                    phase = Phase.RESPONSE
                    justEnded = true
                    continue
                }
            }

            // 3. Verificar se o final do buffer é prefixo de algum marcador
            //    (pode estar fragmentado). Nesse caso, reter o prefixo.
            val holdLen = longestPrefixMatch(text)
            if (holdLen > 0 && holdLen == text.length) {
                // Todo o buffer é prefixo de um marcador → esperar mais dados
                break
            }

            // 4. Emitir o conteúdo seguro (tudo menos o possível prefixo final)
            val safeLen = text.length - holdLen
            if (safeLen <= 0) break

            val safe = text.substring(0, safeLen)
            buffer.delete(0, safeLen)

            when (phase) {
                Phase.THINKING -> thinking += safe
                Phase.RESPONSE -> visible += stripSpecials(safe)
            }
        }

        return Output(
            visibleDelta = visible,
            thinkingDelta = thinking,
            phase = phase,
            thinkingJustStarted = justStarted,
            thinkingJustEnded = justEnded,
        )
    }

    private data class MarkerMatch(val index: Int, val marker: String)

    private fun findEarliestMarker(text: String, markers: List<String>): MarkerMatch? {
        var best: MarkerMatch? = null
        for (m in markers) {
            val idx = text.indexOf(m)
            if (idx >= 0 && (best == null || idx < best.index)) {
                best = MarkerMatch(idx, m)
            }
        }
        return best
    }

    /**
     * Retorna o tamanho do maior prefixo do final de [text] que
     * coincide com o início de algum marcador conhecido.
     */
    private fun longestPrefixMatch(text: String): Int {
        var max = 0
        for (m in allMarkers) {
            val limit = minOf(m.length - 1, text.length)
            for (len in limit downTo 1) {
                if (text.endsWith(m.substring(0, len))) {
                    if (len > max) max = len
                    break
                }
            }
        }
        return max
    }

    /** Remove marcadores especiais completos de uma string já "segura". */
    private fun stripSpecials(input: String): String {
        if (input.isEmpty()) return input
        var result = input
        for (m in specialMarkers) {
            if (result.contains(m)) {
                result = result.replace(m, "")
            }
        }
        // Rotulos de papel ("user"/"assistant") NAO sao removidos por substituicao de texto: isso
        // destruiria respostas legitimas. Eles so apareciam por template errado / geracao que nao
        // parava no fim de turno, o que e corrigido na origem (template + parada por EOG).
        return result
    }
}
