package org.machikoro.server.dto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartGameRequestTest {

    @Test
    fun `StartGameRequest holds the provided gameId and requestingUserId`() {
        val request = StartGameRequest(gameId = 7, requestingUserId = 42)

        assertEquals(7, request.gameId)
        assertEquals(42, request.requestingUserId)
    }

    @Test
    fun `StartGameRequest copy produces independent instance with updated field`() {
        val original = StartGameRequest(gameId = 1, requestingUserId = 10)
        val copy = original.copy(requestingUserId = 99)

        assertEquals(1, copy.gameId)
        assertEquals(99, copy.requestingUserId)
        assertEquals(10, original.requestingUserId)
    }

    @Test
    fun `StartGameRequest equality is based on field values`() {
        val a = StartGameRequest(gameId = 3, requestingUserId = 5)
        val b = StartGameRequest(gameId = 3, requestingUserId = 5)

        assertEquals(a, b)
    }

    @Test
    fun `StartGameRequest toString contains field values`() {
        val request = StartGameRequest(gameId = 2, requestingUserId = 8)

        val str = request.toString()

        assert(str.contains("2"))
        assert(str.contains("8"))
    }
}

