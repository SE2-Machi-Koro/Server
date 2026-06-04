package org.machikoro.server.listener

import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.PendingLobbyCreatedCache
import org.machikoro.server.service.WebSocketConnectionTracker
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessageSendingOperations
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.stereotype.Component
import org.springframework.web.socket.messaging.SessionDisconnectEvent
import org.springframework.web.socket.messaging.SessionSubscribeEvent

@Component
class WebSocketEventListener(
    private val template: SimpMessageSendingOperations,
    private val connectionTracker: WebSocketConnectionTracker,
    private val pendingLobbyCreatedCache: PendingLobbyCreatedCache,
) {
    private val logger = LoggerFactory.getLogger(WebSocketEventListener::class.java)

    /**
     * Replay a pending LOBBY_CREATED message when the client's user queue subscription is confirmed.
     *
     * Spring's simple broker processes STOMP frames on a thread pool, so a SEND can race
     * ahead of its sibling SUBSCRIBE in the inbound channel. [PendingLobbyCreatedCache] stores
     * the response from createLobby; this handler delivers it once the subscription is guaranteed
     * to be registered (the event fires after the broker records the subscription).
     */
    @EventListener
    fun handleSubscribeEvent(event: SessionSubscribeEvent) {
        val accessor = StompHeaderAccessor.wrap(event.message)
        val sessionId = accessor.sessionId ?: return
        val destination = accessor.destination ?: return

        // Only replay on the creator's personal lobby queue, not on game topics or other queues
        if (destination != "/queue/lobby-user$sessionId") return

        val pending = pendingLobbyCreatedCache.consume(sessionId) ?: return
        logger.debug("Replaying LOBBY_CREATED for session '{}' — subscription confirmed late", sessionId)
        template.convertAndSend(destination, pending)
    }

    /**
     * Listen for WebSocket disconnect events.
     * Unregisters the session from the connection tracker, then broadcasts a
     * LEAVE message to all subscribers when a user disconnects.
     */
    @EventListener
    fun handleWebSocketDisconnectListener(event: SessionDisconnectEvent) {
        val headerAccessor = StompHeaderAccessor.wrap(event.message)
        val sessionId = headerAccessor.sessionId
        val username = headerAccessor.sessionAttributes?.get("username") as? String

        if (sessionId != null) {
            // Evict any un-replayed pending message so the cache does not grow unbounded
            pendingLobbyCreatedCache.evict(sessionId)
            connectionTracker.unregister(sessionId)
        }

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
