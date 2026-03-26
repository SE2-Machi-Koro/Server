package org.machikoro.server.exception

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CustomWebSocketExceptionTests {

    @Test
    fun shouldExposeErrorCodeAndMessage() {
        val ex = CustomWebSocketException(
            errorCode = "INVALID_MESSAGE",
            message = "Message content cannot be empty"
        )

        assertEquals("INVALID_MESSAGE", ex.errorCode)
        assertEquals("Message content cannot be empty", ex.message)
        assertNull(ex.cause)
    }

    @Test
    fun shouldPropagateCauseWhenProvided() {
        val cause = IllegalStateException("boom")

        val ex = CustomWebSocketException(
            errorCode = "INTERNAL_ERROR",
            message = "Unexpected failure",
            cause = cause
        )

        assertSame(cause, ex.cause)
    }
}
