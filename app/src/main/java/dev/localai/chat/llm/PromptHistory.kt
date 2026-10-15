package dev.localai.chat.llm

/**
 * Seleciona quais mensagens ja persistidas entram no prompt. Logica pura (sem Android),
 * separada da representacao visual: a UI mostra [dev.localai.chat.ui.chat.ChatMessage]; o modelo
 * recebe apenas [ChatTurn] com o texto visivel da resposta (sem thinking e sem rotulos).
 */
object PromptHistory {

    const val MAX_TURNS = 12
    const val MAX_CHARS = 8000   // ~2,3 mil tokens: deixa espaco no contexto de 4096

    fun select(
        history: List<ChatTurn>,
        maxTurns: Int = MAX_TURNS,
        maxChars: Int = MAX_CHARS,
    ): List<ChatTurn> {
        // 1. descarta vazios (ex.: resposta cancelada antes do primeiro token) e papeis desconhecidos
        val cleaned = history.filter {
            it.content.isNotBlank() && (it.role == ChatTurn.USER || it.role == ChatTurn.ASSISTANT)
        }

        // 2. funde turnos consecutivos do mesmo papel (templates como o do Gemma exigem alternancia)
        val merged = ArrayList<ChatTurn>()
        for (t in cleaned) {
            val last = merged.lastOrNull()
            if (last != null && last.role == t.role) {
                merged[merged.lastIndex] = last.copy(content = last.content.trimEnd() + "\n\n" + t.content.trimStart())
            } else {
                merged.add(t)
            }
        }

        // 3. janela: ultimos turnos e, depois, orcamento de caracteres (remove os mais antigos)
        var window: List<ChatTurn> = merged.takeLast(maxTurns)
        while (window.size > 1 && window.sumOf { it.content.length } > maxChars) {
            window = window.drop(1)
        }

        // 4. a conversa precisa comecar por um turno de usuario
        while (window.isNotEmpty() && window.first().role != ChatTurn.USER) {
            window = window.drop(1)
        }
        return window
    }
}
