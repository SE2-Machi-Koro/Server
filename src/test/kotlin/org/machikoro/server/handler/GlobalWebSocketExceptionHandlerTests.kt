package org.machikoro.server.handler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.dto.WebSocketErrorDto
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.web.bind.annotation.ControllerAdvice

class GlobalWebSocketExceptionHandlerTests {

    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val handler = GlobalWebSocketExceptionHandler(messagingTemplate)

    @Test
    fun shouldBeRegisteredAsControllerAdviceSoHandlersApplyGlobally() {
        // Regression guard for #427: as a plain @Controller, the
        // @MessageExceptionHandler methods are scoped to this bean only and
        // never catch exceptions from other @MessageMapping controllers.
        assertTrue(
            GlobalWebSocketExceptionHandler::class.java.isAnnotationPresent(ControllerAdvice::class.java),
            "GlobalWebSocketExceptionHandler must be @ControllerAdvice for its handlers to apply globally",
        )
    }

    @Test
    fun handleCustomExceptionSendsDomainErrorToCallersSessionQueue() {
        val exception = CustomWebSocketException(
            errorCode = "INVALID_MESSAGE",
            message = "Message content cannot be empty",
        )

        handler.handleCustomException(exception, accessorFor("sess-123"))

        val payload = captureSentPayload("/queue/errors-usersess-123")
        assertEquals("INVALID_MESSAGE", payload.code)
        assertEquals("Message content cannot be empty", payload.message)
    }

    @Test
    fun handleGenericExceptionSendsSanitizedInternalErrorToCallersSessionQueue() {
        val exception = IllegalStateException("jdbc:postgresql://prod.internal password=secret")

        handler.handleGenericException(exception, accessorFor("sess-999"))

        val payload = captureSentPayload("/queue/errors-usersess-999")
        assertEquals("INTERNAL_ERROR", payload.code)
        assertEquals("Unexpected error while processing WebSocket message", payload.message)
        assertTrue(!payload.message.contains("prod.internal"))
        assertTrue(!payload.message.contains("password=secret"))
    }

    @Test
    fun handleCustomExceptionWithoutSessionIdDoesNotSend() {
        val exception = CustomWebSocketException(errorCode = "NO_SESSION", message = "no session")

        handler.handleCustomException(exception, accessorFor(sessionId = null))

        verify(messagingTemplate, org.mockito.kotlin.never()).convertAndSend(
            org.mockito.kotlin.any<String>(),
            org.mockito.kotlin.any<Any>(),
        )
    }

    private fun accessorFor(sessionId: String?): SimpMessageHeaderAccessor =
        SimpMessageHeaderAccessor.create().apply { this.sessionId = sessionId }

    private fun captureSentPayload(expectedDestination: String): WebSocketErrorDto {
        val destinationCaptor = argumentCaptor<String>()
        val payloadCaptor = argumentCaptor<WebSocketErrorDto>()
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture())
        assertEquals(expectedDestination, destinationCaptor.firstValue)
        return payloadCaptor.firstValue
    }
}
