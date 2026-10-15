package dev.localai.chat.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptHistoryTest {

    private fun u(t: String) = ChatTurn(ChatTurn.USER, t)
    private fun a(t: String) = ChatTurn(ChatTurn.ASSISTANT, t)

    @Test fun snapshotPlusNewUserMessageIsNotDuplicated() {
        val prior = listOf(u("u1"), a("a1"))
        assertEquals(listOf(u("u1"), a("a1"), u("u2")), PromptHistory.select(prior + u("u2")))
    }

    @Test fun blankAssistantFromACancelledGenerationIsDroppedAndUsersAreMerged() {
        val r = PromptHistory.select(listOf(u("a"), a("  "), u("b")))
        assertEquals(listOf(u("a\n\nb")), r)
    }

    @Test fun partialAssistantTextFromACancelledGenerationIsKept() {
        val r = PromptHistory.select(listOf(u("conte uma historia"), a("Era uma vez"), u("continue")))
        assertEquals(listOf(u("conte uma historia"), a("Era uma vez"), u("continue")), r)
    }

    @Test fun windowKeepsTheLastTurnsAndStartsWithUser() {
        val turns = (0..8).map { if (it % 2 == 0) u("t$it") else a("t$it") } // u,a,u,a,u,a,u,a,u
        val r = PromptHistory.select(turns, maxTurns = 5)
        assertEquals(listOf("t4", "t5", "t6", "t7", "t8"), r.map { it.content })
        assertEquals(ChatTurn.USER, r.first().role)
    }

    @Test fun leadingAssistantTurnIsDropped() {
        val turns = (0..7).map { if (it % 2 == 0) u("t$it") else a("t$it") }
        val r = PromptHistory.select(turns, maxTurns = 5) // pega a,u,a,u,a
        assertEquals(ChatTurn.USER, r.first().role)
        assertEquals(listOf("t4", "t5", "t6", "t7"), r.map { it.content })
    }

    @Test fun characterBudgetDropsTheOldestFirst() {
        val big = "x".repeat(3000)
        val r = PromptHistory.select(listOf(u(big), a(big), u(big)), maxChars = 8000)
        assertEquals(listOf(u(big)), r)
    }

    @Test fun naturalRoleWordsInContentAreUntouched() {
        val r = PromptHistory.select(listOf(u("o que e um user?"), a("O user usa; o assistant responde."), u("ok")))
        assertEquals("O user usa; o assistant responde.", r[1].content)
    }

    @Test fun threeOrMoreTurnsKeepEveryMessageExactlyOnce() {
        val prior = listOf(u("m1"), a("r1"), u("m2"), a("r2"))
        val r = PromptHistory.select(prior + u("m3"))
        assertEquals(listOf("m1", "r1", "m2", "r2", "m3"), r.map { it.content })
    }
    @Test fun prefixRemainsStableAcrossConsecutiveTurnsUntilBlockTrim() {
        val history = listOf(u("u1"), a("a1"), u("u2"), a("a2"))
        val first = PromptHistory.select(history)
        val next = PromptHistory.select(history + u("u3"))
        assertEquals(first, next.take(first.size))
    }

    @Test fun blockTruncationKeepsUserAssistantAlternation() {
        val big = "x".repeat(1200)
        val turns = (1..8).flatMap { listOf(u("u$it-$big"), a("a$it-$big")) }
        val selected = PromptHistory.select(turns, maxTurns = 16, maxChars = 5000)
        assertEquals(ChatTurn.USER, selected.first().role)
        for (i in selected.indices) {
            if (i > 0) assertTrue(selected[i - 1].role != selected[i].role)
        }
        assertTrue(selected.size <= 8)
    }

    @Test fun systemPromptIsAlwaysPrependedByPromptBuilderContract() {
        // PromptHistory aceita apenas turnos de usuário/assistente; system é adicionado
        // pelo InferenceEngine.buildPrompt em todas as chamadas.
        val selected = PromptHistory.select(listOf(u("oi"), a("olá")))
        assertTrue(selected.none { it.role == ChatTurn.SYSTEM })
        assertEquals(listOf(ChatTurn.USER, ChatTurn.ASSISTANT), selected.map { it.role })
    }

}
