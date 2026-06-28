package org.machikoro.server.handler

import org.machikoro.server.dto.WebSocketErrorDto
import org.machikoro.server.exception.CustomWebSocketException
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageExceptionHandler
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
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
 *
 * The error frame is sent to the caller's session-scoped queue directly rather
 * than via `@SendToUser`. `@SendToUser` resolves the target through the
 * `SimpUserRegistry`, which this app does not populate — it authenticates with a
 * token in the STOMP CONNECT body instead of Spring Security — so the resolver
 * would find no sessions and silently drop the reply. Targeting
 * `/queue/errors-user{sessionId}` (the destination Spring rewrites a
 * `/user/queue/errors` subscription to) sidesteps the registry, mirroring
 * [org.machikoro.server.controller.WebSocketController.publishSync].
 */
@ControllerAdvice
class GlobalWebSocketExceptionHandler(
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val logger = LoggerFactory.getLogger(GlobalWebSocketExceptionHandler::class.java)

    @MessageExceptionHandler(CustomWebSocketException::class)
    fun handleCustomException(ex: CustomWebSocketException, headerAccessor: SimpMessageHeaderAccessor) {
        logger.warn("Handled WebSocket exception [{}]: {}", ex.errorCode, ex.message)
        sendError(headerAccessor.sessionId, WebSocketErrorDto.from(ex))
    }

    @MessageExceptionHandler(Exception::class)
    fun handleGenericException(ex: Exception, headerAccessor: SimpMessageHeaderAccessor) {
        logger.error("Unhandled WebSocket exception", ex)
        sendError(headerAccessor.sessionId, WebSocketErrorDto.internal())
    }

    /**
     * Delivers [dto] to the originating session's private error queue. No-op when
     * the session id is absent (e.g. the exception did not originate from a client
     * frame), since there is no session to reply to.
     */
    private fun sendError(sessionId: String?, dto: WebSocketErrorDto) {
        if (sessionId == null) {
            logger.warn("Dropping WebSocket error [{}]: no session id on the originating message", dto.code)
            return
        }
        messagingTemplate.convertAndSend(sessionScopedErrorDestination(sessionId), dto)
    }

    private fun sessionScopedErrorDestination(sessionId: String): String =
        "$USER_ERROR_QUEUE-user$sessionId"

    private companion object {
        private const val USER_ERROR_QUEUE = "/queue/errors"
    }
}
