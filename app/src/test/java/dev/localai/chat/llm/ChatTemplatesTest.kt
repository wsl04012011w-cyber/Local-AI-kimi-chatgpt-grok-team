package dev.localai.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplatesTest {

    private val sys = ChatTurn(ChatTurn.SYSTEM, "S")
    private fun u(t: String) = ChatTurn(ChatTurn.USER, t)
    private fun a(t: String) = ChatTurn(ChatTurn.ASSISTANT, t)
    private fun String.occurrences(sub: String) = split(sub).size - 1

    // ---- Gemma ---------------------------------------------------------------------------

    @Test fun gemmaFirstMessage() {
        assertEquals(
            "<bos><start_of_turn>user\nS\n\noii<end_of_turn>\n<start_of_turn>model\n",
            ChatTemplates.format(ChatFamily.GEMMA, listOf(sys, u("oii"))),
        )
    }

    @Test fun gemmaSecondMessageUsesPreviousHistory() {
        assertEquals(
            "<bos><start_of_turn>user\nS\n\noii<end_of_turn>\n<start_of_turn>model\nOlá!<end_of_turn>\n" +
                "<start_of_turn>user\ntudo bem?<end_of_turn>\n<start_of_turn>model\n",
            ChatTemplates.format(ChatFamily.GEMMA, listOf(sys, u("oii"), a("Olá!"), u("tudo bem?"))),
        )
    }

    @Test fun gemmaThreeOrMoreTurnsHaveNoDuplication() {
        val turns = listOf(sys, u("m1"), a("r1"), u("m2"), a("r2"), u("m3"))
        val p = ChatTemplates.format(ChatFamily.GEMMA, turns)
        for (t in listOf("m1", "r1", "m2", "r2", "m3")) assertEquals(t, 1, p.occurrences(t))
        assertEquals(3, p.occurrences("<start_of_turn>user"))
        assertEquals(3, p.occurrences("<start_of_turn>model")) // 2 respostas + o prefixo final
        assertEquals(1, p.occurrences("S\n\n"))                  // sistema aparece uma unica vez
        assertTrue(p.endsWith("<start_of_turn>model\n"))
        assertTrue(p.startsWith("<bos>"))
        assertEquals(1, p.occurrences("<bos>"))
    }

    @Test fun gemmaNeverUsesChatMlMarkersAndChatMlNeverUsesGemmaMarkers() {
        val turns = listOf(sys, u("oi"))
        val g = ChatTemplates.format(ChatFamily.GEMMA, turns)
        val c = ChatTemplates.format(ChatFamily.CHATML, turns)
        assertFalse(g.contains("<|im_start|>") || g.contains("<|im_end|>"))
        assertFalse(c.contains("<start_of_turn>") || c.contains("<end_of_turn>"))
    }

    @Test fun gemmaKeepsNaturalRoleWordsInsideContent() {
        val p = ChatTemplates.format(
            ChatFamily.GEMMA,
            listOf(sys, u("o que e um user?"), a("O user usa; o assistant responde."), u("ok")),
        )
        assertTrue(p.contains("O user usa; o assistant responde."))
    }

    // ---- ChatML (Qwen) ---------------------------------------------------------------------

    @Test fun chatMlFirstMessage() {
        assertEquals(
            "<|im_start|>system\nS<|im_end|>\n<|im_start|>user\noi<|im_end|>\n<|im_start|>assistant\n",
            ChatTemplates.format(ChatFamily.CHATML, listOf(sys, u("oi"))),
        )
    }

    @Test fun chatMlMultiTurnEndsWithAssistantPrefix() {
        val p = ChatTemplates.format(ChatFamily.CHATML, listOf(sys, u("m1"), a("r1"), u("m2")))
        assertTrue(p.endsWith("<|im_start|>assistant\n"))
        assertEquals(1, p.occurrences("m1"))
        assertEquals(1, p.occurrences("r1"))
        assertEquals(1, p.occurrences("m2"))
    }

    // ---- Deteccao de familia ---------------------------------------------------------------

    @Test fun detectsFamilyFromEmbeddedTemplateText() {
        assertEquals(ChatFamily.GEMMA, ChatTemplates.detectFamily("{{ '<start_of_turn>' }}") { false })
        assertEquals(ChatFamily.CHATML, ChatTemplates.detectFamily("{{ '<|im_start|>' }}") { false })
    }

    @Test fun detectsFamilyBySpecialTokenProbeWhenThereIsNoTemplate() {
        assertEquals(ChatFamily.GEMMA, ChatTemplates.detectFamily("") { it == "<start_of_turn>" })
        assertEquals(ChatFamily.CHATML, ChatTemplates.detectFamily("") { it == "<|im_start|>" })
        assertNull(ChatTemplates.detectFamily("") { false })
    }
}
