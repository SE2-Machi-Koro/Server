package org.machikoro.server.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.exception.CustomWebSocketException

class GlobalWebSocketExceptionHandlerTests {

    private val handler = GlobalWebSocketExceptionHandler()

    @Test
    fun handleCustomExceptionShouldReturnDomainErrorPayload() {
        val start = System.currentTimeMillis()
        val exception = CustomWebSocketException(
            errorCode = "INVALID_MESSAGE",
            message = "Message content cannot be empty"
        )

        val response = handler.handleCustomException(exception)
        val end = System.currentTimeMillis()

        assertEquals("INVALID_MESSAGE", response.code)
        assertEquals("Message content cannot be empty", response.message)
        assertTrue(response.timestamp in start..end)
    }

    @Test
    fun handleGenericExceptionShouldReturnInternalErrorPayload() {
        val start = System.currentTimeMillis()

        val response = handler.handleGenericException(IllegalStateException("boom"))
        val end = System.currentTimeMillis()

        assertEquals("INTERNAL_ERROR", response.code)
        assertEquals("Unexpected error while processing WebSocket message", response.message)
        assertTrue(response.timestamp in start..end)
    }
}

