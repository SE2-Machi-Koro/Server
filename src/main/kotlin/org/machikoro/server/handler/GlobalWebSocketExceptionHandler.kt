package org.machikoro.server.handler

import org.machikoro.server.exception.CustomWebSocketException
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.stereotype.Controller

@Controller
class GlobalWebSocketExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalWebSocketExceptionHandler::class.java)

    data class WebSocketErrorResponse(
        val code: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    @MessageExceptionHandler(CustomWebSocketException::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleCustomException(ex: CustomWebSocketException): WebSocketErrorResponse {
        logger.warn("Handled WebSocket exception [{}]: {}", ex.errorCode, ex.message)
        return WebSocketErrorResponse(
            code = ex.errorCode,
            message = ex.message
        )
    }

    @MessageExceptionHandler(Exception::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleGenericException(ex: Exception): WebSocketErrorResponse {
        logger.error("Unhandled WebSocket exception", ex)
        return WebSocketErrorResponse(
            code = "INTERNAL_ERROR",
            message = "Unexpected error while processing WebSocket message"
        )
    }
}
