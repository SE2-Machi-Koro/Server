package org.machikoro.server.dto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LobbyLeavingOutcomeTest {

    @Test
    fun `LobbyRemains should have correct userId`() {
        val outcome = LobbyLeavingOutcome.LobbyRemains(userId = 42)
        assertEquals(42, outcome.userId)
    }

    @Test
    fun `LobbyRemains toString should be correct`() {
        val outcome = LobbyLeavingOutcome.LobbyRemains(userId = 42)
        val str = outcome.toString()
        assert(str.contains("42"))
    }

    @Test
    fun `LobbyRemains equality should work`() {
        val outcome1 = LobbyLeavingOutcome.LobbyRemains(userId = 42)
        val outcome2 = LobbyLeavingOutcome.LobbyRemains(userId = 42)
        assertEquals(outcome1, outcome2)
    }

    @Test
    fun `LobbyDeleted should have correct gameId`() {
        val outcome = LobbyLeavingOutcome.LobbyDeleted(gameId = 1)
        assertEquals(1, outcome.gameId)
    }

    @Test
    fun `LobbyDeleted toString should be correct`() {
        val outcome = LobbyLeavingOutcome.LobbyDeleted(gameId = 1)
        val str = outcome.toString()
        assert(str.contains("1"))
    }

    @Test
    fun `LobbyDeleted equality should work`() {
        val outcome1 = LobbyLeavingOutcome.LobbyDeleted(gameId = 1)
        val outcome2 = LobbyLeavingOutcome.LobbyDeleted(gameId = 1)
        assertEquals(outcome1, outcome2)
    }
}