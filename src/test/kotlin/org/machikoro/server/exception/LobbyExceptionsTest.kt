package org.machikoro.server.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LobbyExceptionsTest {

    @Test
    fun `GameStartedException retains message`() {
        val ex = GameStartedException("test msg")
        assertEquals("test msg", ex.message)
    }

    @Test
    fun `LobbyFullException retains message`() {
        val ex = LobbyFullException("test msg 2")
        assertEquals("test msg 2", ex.message)
    }
}
