package dev.localai.chat.llm

import org.junit.Assert.assertEquals
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
}
