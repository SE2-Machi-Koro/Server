package org.machikoro.server.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * Pins the wire contract for the lobby/game exception family:
 *  - each exception preserves its constructor message,
 *  - each exposes the stable `errorCode` clients match on,
 *  - each is a `CustomWebSocketException` so `GlobalWebSocketExceptionHandler`
 *    routes it via `/user/queue/errors` with the correct code.
 *
 * A future refactor that swaps the hardcoded code (or unhooks the hierarchy)
 * has to update these tests, which makes the contract change visible in code
 * review.
 */
class LobbyExceptionsTest {

    @Test
    fun `GameStartedException carries message, GAME_STARTED code, and extends CustomWebSocketException`() {
        val ex = GameStartedException("test msg")
        assertEquals("test msg", ex.message)
        assertEquals("GAME_STARTED", ex.errorCode)
        assertInstanceOf(CustomWebSocketException::class.java, ex)
    }

    @Test
    fun `LobbyFullException carries message, LOBBY_FULL code, and extends CustomWebSocketException`() {
        val ex = LobbyFullException("test msg 2")
        assertEquals("test msg 2", ex.message)
        assertEquals("LOBBY_FULL", ex.errorCode)
        assertInstanceOf(CustomWebSocketException::class.java, ex)
    }

    @Test
    fun `GameNotFoundException carries message, GAME_NOT_FOUND code, and extends CustomWebSocketException`() {
        val ex = GameNotFoundException("nope")
        assertEquals("nope", ex.message)
        assertEquals("GAME_NOT_FOUND", ex.errorCode)
        assertInstanceOf(CustomWebSocketException::class.java, ex)
    }

    @Test
    fun `GameFinishedException carries message, GAME_FINISHED code, and extends CustomWebSocketException`() {
        val ex = GameFinishedException("done")
        assertEquals("done", ex.message)
        assertEquals("GAME_FINISHED", ex.errorCode)
        assertInstanceOf(CustomWebSocketException::class.java, ex)
    }
}
