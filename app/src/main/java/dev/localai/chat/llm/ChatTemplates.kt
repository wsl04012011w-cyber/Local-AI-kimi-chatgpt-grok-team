package dev.localai.chat.llm

/** Uma mensagem enviada ao modelo. [role] e "system", "user" ou "assistant". */
data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

enum class ChatFamily { CHATML, GEMMA }

/**
 * Templates de conversa por familia de modelo. Usado como fallback quando o template embutido
 * no GGUF nao existe ou nao e suportado pelo llama_chat_apply_template (ver InferenceEngine).
 * Qwen (ChatML) e Gemma NAO compartilham formato.
 */
object ChatTemplates {

    const val DEFAULT_SYSTEM = "Voce e um assistente util e conciso. Responda no idioma do usuario."

    /**
     * Identifica a familia pelo texto do template embutido no GGUF; sem template, sonda o
     * vocabulario (um marcador que vira exatamente 1 token existe naquele tokenizer).
     */
    fun detectFamily(templateText: String, isSingleSpecialToken: (String) -> Boolean): ChatFamily? = when {
        templateText.contains("<start_of_turn>") -> ChatFamily.GEMMA
        templateText.contains("<|im_start|>") -> ChatFamily.CHATML
        isSingleSpecialToken("<start_of_turn>") -> ChatFamily.GEMMA
        isSingleSpecialToken("<|im_start|>") -> ChatFamily.CHATML
        else -> null
    }

    /** Monta o prompt completo, ja terminando no prefixo que inicia a resposta do assistente. */
    fun format(family: ChatFamily, turns: List<ChatTurn>): String = when (family) {
        ChatFamily.CHATML -> formatChatMl(turns)
        ChatFamily.GEMMA -> formatGemma(turns)
    }

    private fun formatChatMl(turns: List<ChatTurn>): String {
        val sb = StringBuilder()
        for (t in turns) {
            sb.append("<|im_start|>").append(t.role).append('\n')
                .append(t.content.trim()).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * Gemma: papeis "user" e "model", sem papel de sistema (o texto de sistema entra no inicio
     * do primeiro turno de usuario). O BOS vem no inicio; o lado nativo nao o duplica.
     */
    private fun formatGemma(turns: List<ChatTurn>): String {
        val sb = StringBuilder("<bos>")
        var system = ""
        for (t in turns) {
            val text = t.content.trim()
            when (t.role) {
                ChatTurn.SYSTEM -> system = if (system.isEmpty()) text else "$system\n\n$text"
                ChatTurn.USER -> {
                    sb.append("<start_of_turn>user\n")
                    if (system.isNotEmpty()) {
                        sb.append(system).append("\n\n")
                        system = ""
                    }
                    sb.append(text).append("<end_of_turn>\n")
                }
                else -> sb.append("<start_of_turn>model\n").append(text).append("<end_of_turn>\n")
            }
        }
        if (system.isNotEmpty()) {
            sb.append("<start_of_turn>user\n").append(system).append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
