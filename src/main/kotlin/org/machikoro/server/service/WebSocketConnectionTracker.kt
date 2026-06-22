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
     * @property userId       database user ID
     * @property gameId       game / lobby the user joined
     * @property lastAccessMs wall-clock millisecond timestamp of the most recent
     *                        [register] or [touch] call for this session
     */
    private data class SessionEntry(val userId: Int, val gameId: Int, val lastAccessMs: Long)

    // sessionId → SessionEntry
    private val sessions = ConcurrentHashMap<String, SessionEntry>()

    companion object {
        /** Sessions idle longer than this are considered stale and will be evicted. */
        internal const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
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
     * Updates the last-access timestamp for an existing session without
     * changing its userId or gameId.  Call this on any STOMP frame received
     * from the client to keep active sessions from being evicted.
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
     * Evicts sessions that have been idle for longer than [STALE_THRESHOLD_MS].
     *
     * This is a safety net for missed disconnect events (e.g. network drops,
     * ungraceful client exits).  Without it, the session map grows without bound
     * and eventually contributes to OOM pressure on the 1 GB heap.
     *
     * Runs every 5 minutes.  Only active when Spring scheduling is enabled
     * (i.e. when [org.machikoro.server.config.SchedulingConfig] is loaded),
     * which is gated to non-test profiles so this task never fires during tests.
     */
    @Scheduled(fixedRateString = "\${machikoro.websocket.stale-session-check-ms:300000}")
    fun evictStaleSessions() {
        val cutoff = System.currentTimeMillis() - STALE_THRESHOLD_MS
        val stale = sessions.entries.filter { it.value.lastAccessMs < cutoff }
        if (stale.isEmpty()) return
        stale.forEach { (sessionId, entry) ->
            sessions.remove(sessionId)
            logger.info(
                "Evicted stale WebSocket session {} (userId={}, gameId={}, idleMs={})",
                sessionId,
                entry.userId,
                entry.gameId,
                System.currentTimeMillis() - entry.lastAccessMs,
            )
        }
        logger.info("Stale-session sweep complete: evicted {} session(s)", stale.size)
    }
}

