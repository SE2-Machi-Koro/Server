package org.machikoro.server.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry that maps every active STOMP session to the user and
 * game it belongs to.  Populated by [org.machikoro.server.controller.WebSocketController]
 * and [org.machikoro.server.listener.WebSocketEventListener].
 */
@Service
class WebSocketConnectionTracker(
    private val gameSyncService: GameSyncService,
) {

    private val logger = LoggerFactory.getLogger(WebSocketConnectionTracker::class.java)

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
     * Returns the [userId] associated with [sessionId], or `null` if the session
     * is not registered. Used by the WebSocket controller to authorise actions
     * without trusting a client-supplied user ID.
     *
     * @param sessionId STOMP session identifier
     * @return the registered user ID, or `null` if unknown
     */
    fun getUserId(sessionId: String): Int? = sessions[sessionId]?.first

    /**
     * Returns the set of user IDs that are currently connected to a specific game.
     * Used for roster sanitization before game start.
     *
     * @param gameId target game ID
     * @return set of connected user IDs
     */
    fun getConnectedUserIds(gameId: Int): Set<Int> =
        sessions.values.mapNotNullTo(mutableSetOf()) { (uId, gId) ->
            uId.takeIf { gId == gameId }
        }

    /**
     * Periodically evicts sessions whose associated game is no longer in progress.
     *
     * A STOMP disconnect event is the primary cleanup path, but network drops or
     * proxy resets can leave sessions in the registry indefinitely. This scheduled
     * sweep removes any entry whose game has already finished (or never existed),
     * preventing the map from growing without bound over long server uptimes.
     *
     * Runs every 5 minutes by default. The interval is intentionally coarse — the
     * registry is small (bounded by max concurrent players) and correctness of the
     * [getConnectedUserIds] result matters more than immediate eviction.
     */
    @Scheduled(fixedDelayString = "\${websocket.tracker.cleanup-interval-ms:300000}")
    fun evictStaleGameSessions() {
        val stale = sessions.entries
            .filter { (_, pair) -> !gameSyncService.isInProgress(pair.second) }
            .map { it.key }

        if (stale.isNotEmpty()) {
            logger.debug("Evicting {} stale WebSocket session(s) for finished/missing games", stale.size)
            stale.forEach { sessions.remove(it) }
        }
    }
}
