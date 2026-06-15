package org.machikoro.server.controller

import io.github.springwolf.core.asyncapi.annotations.AsyncListener
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.auth.userPrincipal
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.SyncGameRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.WebSocketConnectionTracker
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

@Controller
class WebSocketController(
    private val lobbyService: LobbyService,
    private val connectionTracker: WebSocketConnectionTracker,
    private val gameSyncService: GameSyncService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val logger = LoggerFactory.getLogger(WebSocketController::class.java)

    companion object {
        private const val SYNC_SUCCESS_METRIC = "machikoro.reconnect.sync.success.total"
        private const val SYNC_FAILURE_METRIC = "machikoro.reconnect.sync.failure.total"
        private const val SYNC_LATENCY_METRIC = "machikoro.reconnect.sync.latency.ms"
        private const val GAME_SYNC_SOURCE = "game.sync"
    }

    /**
     * Handle incoming chat messages and broadcast to all subscribers.
     * Message is sent to /app/chat.send and broadcast to /topic/public
     */
    @MessageMapping("/chat.send")
    @SendTo("/topic/public")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/chat.send",
        description = "Broadcasts a chat message to all subscribers of /topic/public.",
        payloadType = WebSocketMessage::class,
    ))
    fun sendMessage(@Payload message: WebSocketMessage): WebSocketMessage {
        logger.info("Message received from ${message.sender}: ${message.content}")
        return message
    }

    /**
     * Handle user join events and broadcast to all subscribers.
     * Stores username in session for later reference during disconnect.
     * Registers the session in [WebSocketConnectionTracker] when user and gameId are known.
     *
     * Identity is read exclusively from the [UserPrincipal] attached by
     * [org.machikoro.server.auth.StompAuthChannelInterceptor] at CONNECT time —
     * the client-supplied [WebSocketMessage.sender] field is ignored to prevent
     * username spoofing / state exfiltration.
     */
    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/chat.addUser",
        description = "Registers a user session and broadcasts the join event to /topic/public. Triggers a game-state sync if the user has an active in-progress game.",
        payloadType = WebSocketMessage::class,
    ))
    fun addUser(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ): WebSocketMessage {
        val principal = headerAccessor.userPrincipal()
        if (principal == null) {
            logger.warn("addUser rejected: no authenticated principal on session ${headerAccessor.sessionId}")
            return message
        }

        logger.info("User '{}' joined the chat", principal.username)
        // Store server-verified username so the disconnect listener can reference it
        // without trusting whatever the client put in the message payload.
        headerAccessor.sessionAttributes?.put("username", principal.username)

        val sessionId = headerAccessor.sessionId

        // Trust only server-side mapping for reconnect. A client-provided
        // gameId can be stale and must not recreate old lobby membership.
        val mappedInProgressGameId = gameSyncService.findActiveInProgressGameId(principal.userId)
        var gameIdToJoin = mappedInProgressGameId

        if (gameIdToJoin != null) {
            try {
                lobbyService.addUserToLobby(gameIdToJoin, principal.userId)
            } catch (e: GameStartedException) {
                // Start-transition race: the game status flipped to IN_PROGRESS between
                // the client sending JOIN and the server processing it.  The client is not
                // in an inconsistent state — it simply joined a lobby that just started.
                // Attempt to re-map to the now-active game so the player receives the
                // correct state snapshot.  If no active game can be found after the race,
                // the exception is re-thrown and the client must retry.
                val remappedInProgressGameId = gameSyncService.findActiveInProgressGameId(principal.userId)
                if (remappedInProgressGameId == null) {
                    throw e
                }
                gameIdToJoin = remappedInProgressGameId
            }

            if (sessionId != null) {
                connectionTracker.register(sessionId, principal.userId, gameIdToJoin)
            }

            val syncGameId = gameSyncService.findActiveInProgressGameId(principal.userId)
            if (syncGameId != null && sessionId != null) {
                emitSyncWithTelemetry(
                    source = "chat.addUser",
                    sessionId = sessionId,
                    userId = principal.userId,
                    gameId = syncGameId,
                )
            }
        } else if (sessionId != null) {
            // No in-progress game, but the user may still hold a server-owned
            // WAITING lobby membership — e.g. a host whose STOMP session dropped
            // and reconnected under a new session id, or after a backend restart.
            // Re-register the fresh session against that server-resolved waiting
            // membership (never the client-supplied gameId) so later game actions
            // such as game.start can still resolve the requesting user.
            val waitingGameId = lobbyService.findWaitingLobbyIdForUser(principal.userId)
            if (waitingGameId != null) {
                connectionTracker.register(sessionId, principal.userId, waitingGameId)
            }
        }

        return message
    }

    /**
     * Explicit re-sync endpoint for clients that reconnect mid-transition and miss
     * the GAME_STARTED broadcast.
     *
     * Identity is read from the [UserPrincipal] attached at CONNECT time —
     * the [SyncGameRequest.gameId] field may be client-supplied but is validated
     * against the server-side player membership before the snapshot is emitted.
     */
    @MessageMapping("/game.sync")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.sync",
        description = "Explicit reconnect sync — delivers a full game-state snapshot to the requesting user's private queue.",
        payloadType = SyncGameRequest::class,
    ))
    fun syncGameState(
        @Payload request: SyncGameRequest,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val sessionId = headerAccessor.sessionId
        if (sessionId == null) {
            recordSyncFailure(source = GAME_SYNC_SOURCE, reason = "missing_session")
            return
        }

        // Resolve identity from the authenticated principal, not from the tracker,
        // to prevent a compromised or spoofed session from claiming another user's ID.
        val principal = headerAccessor.userPrincipal()
        if (principal == null) {
            logger.warn("SYNC rejected: no authenticated principal on session {}", sessionId)
            recordSyncFailure(source = GAME_SYNC_SOURCE, reason = "missing_principal")
            return
        }

        val userId = principal.userId

        if (request.gameId != null && !gameSyncService.isUserInGame(userId, request.gameId)) {
            logger.warn("SYNC rejected for userId={} on unrelated gameId={}", userId, request.gameId)
            recordSyncFailure(source = GAME_SYNC_SOURCE, reason = "unauthorized_game")
            return
        }

        val resolvedGameId = request.gameId ?: gameSyncService.findActiveInProgressGameId(userId)
        if (resolvedGameId == null || !gameSyncService.isInProgress(resolvedGameId)) {
            logger.info("SYNC skipped for userId={} - no active in-progress game", userId)
            recordSyncFailure(source = GAME_SYNC_SOURCE, reason = "no_active_game")
            return
        }

        emitSyncWithTelemetry(
            source = GAME_SYNC_SOURCE,
            sessionId = sessionId,
            userId = userId,
            gameId = resolvedGameId,
        )
    }

    private fun emitSyncWithTelemetry(
        source: String,
        sessionId: String,
        userId: Int,
        gameId: Int,
    ) {
        val startedNs = System.nanoTime()
        runCatching { publishSync(sessionId, userId, gameId) }
            .onSuccess {
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)
                Metrics.counter(SYNC_SUCCESS_METRIC, "source", source).increment()
                Timer.builder(SYNC_LATENCY_METRIC)
                    .tag("source", source)
                    .register(Metrics.globalRegistry)
                    .record(elapsedMs, TimeUnit.MILLISECONDS)
            }
            .onFailure { ex ->
                logger.warn(
                    "SYNC publish failed (source={}, sessionId={}, userId={}, gameId={})",
                    source,
                    sessionId,
                    userId,
                    gameId,
                    ex,
                )
                recordSyncFailure(source = source, reason = "publish_failed")
            }
    }

    private fun recordSyncFailure(source: String, reason: String) {
        Metrics.counter(
            SYNC_FAILURE_METRIC,
            "source", source,
            "reason", reason,
        ).increment()
    }

    /**
     * Builds and delivers the game-state snapshot to a single reconnecting user.
     *
     * Clients subscribe to `/user/queue/game-sync`. Spring resolves that user
     * destination to a session-scoped broker destination with the `-user{sessionId}`
     * suffix. Sending to that resolved destination avoids depending on username
     * lookup in the user registry during reconnect recovery while still targeting
     * only the requesting WebSocket session.
     */
    private fun publishSync(sessionId: String, userId: Int, gameId: Int) {
        messagingTemplate.convertAndSend(
            sessionScopedGameSyncDestination(sessionId),
            buildSyncMessage(sessionId, userId, gameId),
        )
    }

    private fun sessionScopedGameSyncDestination(sessionId: String): String =
        "/queue/game-sync-user$sessionId"

    private fun buildSyncMessage(sessionId: String, userId: Int, gameId: Int): WebSocketMessage {
        val snapshot = gameSyncService.buildSnapshot(gameId)
        return WebSocketMessage(
            type = MessageType.SYNC,
            sender = "server",
            content = "State sync for reconnecting player",
            payload = mapOf(
                "targetUserId" to userId,
                "targetSessionId" to sessionId,
                "state" to snapshot,
            ),
            gameId = gameId,
        )
    }
}
