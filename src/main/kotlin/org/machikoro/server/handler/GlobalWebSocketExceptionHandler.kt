package org.machikoro.server.handler

import org.machikoro.server.dto.WebSocketErrorDto
import org.machikoro.server.exception.CustomWebSocketException
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.simp.annotation.SendToUser
import org.springframework.web.bind.annotation.ControllerAdvice

/**
 * Global handler for exceptions thrown by `@MessageMapping` controllers.
 *
 * Must be `@ControllerAdvice` (not `@Controller`): Spring only applies
 * `@MessageExceptionHandler` methods across other controllers when the bean is
 * registered as advice. As a plain `@Controller` with no `@MessageMapping`
 * methods, these handlers caught nothing — domain exceptions were logged at
 * ERROR with a stack trace by Spring's fallback and the error reply never
 * reached the client. See issue #427.
 */
@ControllerAdvice
class GlobalWebSocketExceptionHandler {
    private val logger = LoggerFactory.getLogger(GlobalWebSocketExceptionHandler::class.java)

    @MessageExceptionHandler(CustomWebSocketException::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleCustomException(ex: CustomWebSocketException): WebSocketErrorDto {
        logger.warn("Handled WebSocket exception [{}]: {}", ex.errorCode, ex.message)
        return WebSocketErrorDto.from(ex)
    }

    @MessageExceptionHandler(Exception::class)
    @SendToUser("/queue/errors", broadcast = false)
    fun handleGenericException(ex: Exception): WebSocketErrorDto {
        logger.error("Unhandled WebSocket exception", ex)
        return WebSocketErrorDto.internal()
    }
}
