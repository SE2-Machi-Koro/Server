package org.machikoro.server.service

import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry that maps every active STOMP session to the user and
 * game it belongs to.  Populated by [org.machikoro.server.controller.WebSocketController]
 * and [org.machikoro.server.listener.WebSocketEventListener].
 */
@Service
class WebSocketConnectionTracker {

    // sessionId → Pair(userId, gameId)
    private val sessions = ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * Records an authenticated session.
     *
     * @param sessionId STOMP session identifier
     * @param userId    database user ID
     * @param gameId    game / lobby the user joined
     */
    fun register(sessionId: String, userId: Int, gameId: Int) {
        sessions[sessionId] = Pair(userId, gameId)
    }

    /**
     * Removes a session when the socket disconnects.
     *
     * @param sessionId STOMP session identifier
     */
    fun unregister(sessionId: String) {
        sessions.remove(sessionId)
    }

    /**
     * Returns the set of user IDs that are currently connected to a specific game.
     * Used for roster sanitization before game start.
     *
     * @param gameId target game ID
     * @return set of connected user IDs
     */
    fun getConnectedUserIds(gameId: Int): Set<Int> =
        sessions.values
            .filter { (_, gId) -> gId == gameId }
            .map { (uId, _) -> uId }
            .toSet()
}

