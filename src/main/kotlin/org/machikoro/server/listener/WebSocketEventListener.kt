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
     */
    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val headerAccessor = StompHeaderAccessor.wrap(event.message)
        val username = headerAccessor.sessionAttributes?.get("username") as? String

        if (username != null) {
            logger.info("User Disconnected: $username")

            val chatMessage = WebSocketMessage(
                type = MessageType.LEAVE,
                sender = username,
                content = null
            )
            template.convertAndSend("/topic/public", chatMessage)
        }
    }
}

