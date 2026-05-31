package org.machikoro.server.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class DebugEndGameErrorTest {

    @Test
    fun `exposes the error code and message`() {
        val error = DebugEndGameError("NOT_IN_GAME", "User alice is not a player in game 5")

        assertEquals("NOT_IN_GAME", error.error)
        assertEquals("User alice is not a player in game 5", error.message)
    }

    @Test
    fun `supports value equality, copy and hashing`() {
        val error = DebugEndGameError("GAME_NOT_FOUND", "Game 9 not found")

        assertEquals(error, error.copy())
        assertEquals(error, DebugEndGameError("GAME_NOT_FOUND", "Game 9 not found"))
        assertEquals(error.hashCode(), DebugEndGameError("GAME_NOT_FOUND", "Game 9 not found").hashCode())
        assertNotEquals(error, error.copy(error = "GAME_FINISHED"))
    }
}
