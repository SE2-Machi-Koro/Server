package org.machikoro.server.service

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe registry that maps every active STOMP session to the user and
 * game it belongs to.  Populated by [org.machikoro.server.controller.WebSocketController]
 * and [org.machikoro.server.listener.WebSocketEventListener].
 *
 * Each entry also records a last-access timestamp so that sessions whose
 * disconnect event was missed (e.g. due to a network drop or server restart)
 * are eventually evicted by [evictStaleSessions].
 */
@Service
class WebSocketConnectionTracker {

    private val logger = LoggerFactory.getLogger(WebSocketConnectionTracker::class.java)

    /**
     * Holds the registered state for a single STOMP session.
     *
     * @property userId      database user ID
     * @property gameId      game / lobby the user joined
     * @property lastAccessMs wall-clock millisecond timestamp of the most recent
     *                        [register] or [touch] call for this session
     */
    private data class SessionEntry(val userId: Int, val gameId: Int, val lastAccessMs: Long)

    // sessionId → SessionEntry
    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    companion object {
        /** Sessions idle longer than this are considered stale and will be evicted. */
        private const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
    }

    /**
     * Records an authenticated session and stamps it with the current time.
     *
     * @param sessionId STOMP session identifier
     * @param userId    database user ID
     * @param gameId    game / lobby the user joined
     */
    fun register(sessionId: String, userId: Int, gameId: Int) {
        sessions[sessionId] = SessionEntry(userId, gameId, System.currentTimeMillis())
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
     * Updates the last-access timestamp for an existing session, preventing it
     * from being treated as stale while the client is still active.  No-op if
     * the session is not currently registered.
     *
     * @param sessionId STOMP session identifier
     */
    fun touch(sessionId: String) {
        sessions.computeIfPresent(sessionId) { _, entry ->
            entry.copy(lastAccessMs = System.currentTimeMillis())
        }
    }

    /**
     * Returns the [userId] associated with [sessionId], or `null` if the session
     * is not registered. Used by the WebSocket controller to authorise actions
     * without trusting a client-supplied user ID.
     *
     * @param sessionId STOMP session identifier
     * @return the registered user ID, or `null` if unknown
     */
    fun getUserId(sessionId: String): Int? = sessions[sessionId]?.userId

    /**
     * Returns the set of user IDs that are currently connected to a specific game.
     * Used for roster sanitization before game start.
     *
     * @param gameId target game ID
     * @return set of connected user IDs
     */
    fun getConnectedUserIds(gameId: Int): Set<Int> =
        sessions.values.mapNotNullTo(mutableSetOf()) { entry ->
            entry.userId.takeIf { entry.gameId == gameId }
        }

    /**
     * Scheduled cleanup that removes sessions which have not been accessed in the
     * last [STALE_THRESHOLD_MS] milliseconds.  This is a safety net for sessions
     * whose STOMP disconnect event was never delivered — for example when a mobile
     * client loses connectivity abruptly or the broker drops the frame silently.
     *
     * Runs every 5 minutes.  Under normal operation the [WebSocketEventListener]
     * handles cleanup promptly on disconnect; this task only catches the stragglers.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000L) // every 5 minutes
    fun evictStaleSessions() {
        val cutoff = System.currentTimeMillis() - STALE_THRESHOLD_MS
        val stale = sessions.entries.filter { (_, entry) -> entry.lastAccessMs < cutoff }
        if (stale.isEmpty()) return

        stale.forEach { (sessionId, entry) ->
            sessions.remove(sessionId)
            logger.info(
                "Evicted stale session {} (userId={}, gameId={}, idleMs={})",
                sessionId,
                entry.userId,
                entry.gameId,
                System.currentTimeMillis() - entry.lastAccessMs,
            )
        }
        logger.info("Stale session sweep complete: evicted {} session(s)", stale.size)
    }
}

