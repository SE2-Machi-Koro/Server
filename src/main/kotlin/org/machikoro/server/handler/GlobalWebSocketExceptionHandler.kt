package org.machikoro.server.handler

import org.machikoro.server.dto.ErrorMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
@ControllerAdvice
class GlobalWebSocketExceptionHandler(
    private val template: SimpMessagingTemplate
) {
    private val logger = LoggerFactory.getLogger(GlobalWebSocketExceptionHandler::class.java)

    /**
     * Handle custom WebSocket exceptions.
     * Sends error message back to the client through a dedicated error topic.
     */
    @Suppress("UNUSED")
    fun handleWebSocketException(sessionId: String, exception: CustomWebSocketException) {
        logger.error("WebSocket error in session $sessionId: ${exception.message}", exception)

        val errorMessage = ErrorMessage(
            code = exception.code,
            message = exception.message ?: "An unknown error occurred"
        )

        template.convertAndSendToUser(sessionId, "/queue/errors", errorMessage)
    }

    /**
     * Handle validation errors.
     * Sends validation error message back to the client.
     */
    @Suppress("UNUSED")
    fun handleValidationException(sessionId: String, exception: MethodArgumentNotValidException) {
        logger.warn("Validation error in session $sessionId: ${exception.message}")

        val errorMessage = ErrorMessage(
            code = "VALIDATION_ERROR",
            message = "Invalid message format or missing required fields"
        )

        template.convertAndSendToUser(sessionId, "/queue/errors", errorMessage)
    }

    /**
     * Handle general exceptions.
     * Sends generic error message back to the client.
     */
    @Suppress("UNUSED")
    fun handleGenericException(sessionId: String, exception: Exception) {
        logger.error("Unexpected error in session $sessionId: ${exception.message}", exception)

        val errorMessage = ErrorMessage(
            code = "INTERNAL_ERROR",
            message = "An unexpected error occurred. Please try again."
        )

        template.convertAndSendToUser(sessionId, "/queue/errors", errorMessage)
    }

    /**
     * Handle disconnection errors.
     * Log when session disconnects abnormally.
     */
    @Suppress("UNUSED")
    fun handleDisconnectionError(event: SessionDisconnectEvent) {
        val sessionId = event.sessionId
        logger.info("Session disconnected: $sessionId, Code: ${event.closeStatus?.code}")
    }
}
