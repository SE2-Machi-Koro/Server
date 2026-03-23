package org.machikoro.server.listener

import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionDisconnectEvent

@Component
class WebSocketEventListener(
    private val template: SimpMessageSendingOperations
) {
    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    /**
     * Listen for WebSocket disconnect events.
     * Broadcasts a LEAVE message to all subscribers when a user disconnects.
     * Handles errors gracefully to prevent listener failures from affecting other sessions.
     */
    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        try {
            val headerAccessor = StompHeaderAccessor.wrap(event.message)
            val username = headerAccessor.sessionAttributes?.get("username") as? String

            if (username != null) {
                logger.info("User Disconnected: $username")

                val chatMessage = WebSocketMessage(
                    type = MessageType.LEAVE,
                    sender = username,
                    content = "$username has left the chat"
                )

                try {
                    template.convertAndSend("/topic/public", chatMessage)
                } catch (ex: Exception) {
                    logger.error("Failed to broadcast leave message for user $username", ex)
                }
            } else {
                logger.debug("Session disconnected without username: ${event.sessionId}")
            }
        } catch (ex: Exception) {
            logger.error("Error processing disconnect event: ${ex.message}", ex)
        }
    }
}
