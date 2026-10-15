package dev.localai.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStreamProcessorTest {

    private data class Result(val visible: String, val thinking: String, val turnBoundary: Boolean)

    private fun run(chunks: List<String>, processor: TokenStreamProcessor = TokenStreamProcessor()): Result {
        val v = StringBuilder()
        val t = StringBuilder()
        var boundary = false
        for (c in chunks) {
            val o = processor.feed(c)
            v.append(o.visibleDelta)
            t.append(o.thinkingDelta)
            boundary = boundary || o.turnBoundaryReached
        }
        val f = processor.flush()
        v.append(f.visibleDelta)
        t.append(f.thinkingDelta)
        return Result(v.toString(), t.toString(), boundary)
    }

    private fun chars(s: String) = s.map { it.toString() }

    private fun split(s: String, size: Int) = s.chunked(size)

    // ---- Qwen: geracao normal, thinking, fim de turno -----------------------------------

    @Test fun plainResponseWithoutThinking() {
        val r = run(listOf("Ol", "á, ", "tudo bem?"))
        assertEquals("Olá, tudo bem?", r.visible)
        assertEquals("", r.thinking)
        assertFalse(r.turnBoundary)
    }

    @Test fun thinkingIsSeparatedFromTheAnswer() {
        val r = run(chars("<think>vou pensar</think>\n\nResposta"))
        assertEquals("vou pensar", r.thinking)
        assertEquals("Resposta", r.visible.trim())
    }

    @Test fun thinkingMarkersSurviveAnyChunking() {
        val text = "<think>passo 1\npasso 2</think>\n\nA resposta final."
        for (size in 1..9) {
            val r = run(split(text, size))
            assertEquals("tamanho $size", "passo 1\npasso 2", r.thinking)
            assertEquals("tamanho $size", "A resposta final.", r.visible.trim())
        }
    }

    @Test fun qwenEndOfTurnTokensAreNotShown() {
        assertEquals("Oi!", run(chars("Oi!<|im_end|>")).visible)
        assertEquals("Oi!", run(chars("Oi!<|endoftext|>")).visible)
    }

    @Test fun incompleteMarkerAtTheEndIsDroppedNotLeaked() {
        assertEquals("Olá ", run(listOf("Olá <|im_e")).visible)
    }

    @Test fun cancelledInTheMiddleOfThinkingKeepsPartialThinking() {
        val r = run(listOf("<think>racioc"))
        assertEquals("racioc", r.thinking)
        assertEquals("", r.visible)
    }

    @Test fun longContentWithThinking() {
        val text = "<think>" + "x".repeat(50_000) + "</think>" + "y".repeat(50_000)
        val r = run(split(text, 37))
        assertEquals(50_000, r.thinking.length)
        assertEquals(50_000, r.visible.length)
    }

    @Test fun thinkingTextWithRoleWordsIsKept() {
        val r = run(chars("<think>o user pediu\nassistant deve responder</think>ok"))
        assertEquals("o user pediu\nassistant deve responder", r.thinking)
        assertEquals("ok", r.visible)
    }

    // ---- Palavras "user"/"assistant" legitimas NAO podem ser apagadas -----------------------

    @Test fun naturalUserAndAssistantWordsArePreserved() {
        val text = "O papel do user e do assistant:\nuser\nassistant\nfim"
        assertEquals(text, run(chars(text)).visible)
        assertEquals(text, run(listOf(text)).visible)
    }

    // ---- Fim de turno estrutural (Gemma / ChatML) -------------------------------------------

    @Test fun gemmaNewTurnStopsTheStreamAndDiscardsTheRest() {
        val text = "Olá!<end_of_turn>\n<start_of_turn>user\noi<end_of_turn>\n<start_of_turn>model\nlixo"
        val r = run(chars(text))
        assertEquals("Olá!", r.visible.trim())
        assertTrue(r.turnBoundary)
        assertFalse(r.visible.contains("lixo"))
        assertFalse(r.visible.contains("user"))
    }

    @Test fun chatMlNewTurnStopsTheStream() {
        val r = run(listOf("Oi", "<|im_end|><|im_start|>", "user\nxx"))
        assertEquals("Oi", r.visible)
        assertTrue(r.turnBoundary)
    }

    @Test fun feedAfterTurnBoundaryReturnsNothing() {
        val p = TokenStreamProcessor()
        p.feed("Oi<start_of_turn>")
        val o = p.feed("mais texto")
        assertEquals("", o.visibleDelta)
        assertTrue(o.turnBoundaryReached)
    }

    @Test fun resetAllowsANewGenerationAfterABoundary() {
        val p = TokenStreamProcessor()
        run(listOf("A<start_of_turn>"), p)
        p.reset()
        val r = run(listOf("Nova resposta"), p)
        assertEquals("Nova resposta", r.visible)
        assertFalse(r.turnBoundary)
    }
}
