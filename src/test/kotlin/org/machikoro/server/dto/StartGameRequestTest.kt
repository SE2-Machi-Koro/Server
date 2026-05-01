package org.machikoro.server.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartGameRequestTest {

    @Test
    fun `StartGameRequest round-trip equality and copy`() {
        val original = StartGameRequest(gameId = 7)
        val copy = original.copy(gameId = 99)

        assertEquals(7, original.gameId)
        assertEquals(99, copy.gameId)
        assertEquals(original, StartGameRequest(gameId = 7))
    }
}


