package org.machikoro.server.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RollDiceRequestTests {

    @Test
    fun rollDiceRequestShouldHaveCorrectValues() {
        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)

        assertEquals(1, request.gameId)
        assertEquals(2, request.playerId)
        assertEquals(true, request.rollTwoDice)
    }

    @Test
    fun rollTwoDiceShouldDefaultToFalse() {
        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertFalse(request.rollTwoDice)
    }

    @Test
    fun rollDiceRequestShouldSupportCopy() {
        val original = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = false)
        val copy = original.copy(rollTwoDice = true)

        assertFalse(original.rollTwoDice)
        assertEquals(true, copy.rollTwoDice)
        assertEquals(original.gameId, copy.gameId)
        assertEquals(original.playerId, copy.playerId)
    }
}