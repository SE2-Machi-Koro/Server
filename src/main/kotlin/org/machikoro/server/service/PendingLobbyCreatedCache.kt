package org.machikoro.server.service

import org.machikoro.server.dto.WebSocketMessage
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

// Guards against the STOMP SUBSCRIBE/SEND race where createLobby sends before the subscription is registered
@Component
class PendingLobbyCreatedCache {

    // sessionId -> message waiting to be replayed on subscribe confirmation
    private val pending = ConcurrentHashMap<String, WebSocketMessage>()

    // Store a LOBBY_CREATED message keyed by the creator's STOMP session
    fun put(sessionId: String, message: WebSocketMessage) {
        pending[sessionId] = message
    }

    // Returns and removes the pending message atomically — null if already consumed or never stored
    fun consume(sessionId: String): WebSocketMessage? = pending.remove(sessionId)

    // Remove on disconnect to prevent unbounded growth for sessions that never subscribed
    fun evict(sessionId: String) {
        pending.remove(sessionId)
    }
}