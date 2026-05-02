package org.machikoro.server.controller

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.Timer
import org.machikoro.server.auth.UserPrincipal
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
import org.springframework.messaging.simp.SimpMessageType
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
    fun addUser(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ): WebSocketMessage {
        val principal = headerAccessor.user as? UserPrincipal
        if (principal == null) {
            logger.warn("addUser rejected: no authenticated principal on session ${headerAccessor.sessionId}")
            return message
        }

        logger.info("User '{}' joined the chat", principal.username)
        // Store server-verified username so the disconnect listener can reference it
        // without trusting whatever the client put in the message payload.
        headerAccessor.sessionAttributes?.put("username", principal.username)

        // Prefer server-side mapping to an active game when a player reconnects.
        val mappedInProgressGameId = gameSyncService.findActiveInProgressGameId(principal.userId)
        var gameIdToJoin = mappedInProgressGameId ?: message.gameId

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

            val sessionId = headerAccessor.sessionId
            if (sessionId != null) {
                connectionTracker.register(sessionId, principal.userId, gameIdToJoin)
            }

            val syncGameId = gameSyncService.findActiveInProgressGameId(principal.userId)
            if (syncGameId != null && sessionId != null) {
                emitSyncWithTelemetry(
                    source = "chat.addUser",
                    principalName = principal.name,
                    sessionId = sessionId,
                    userId = principal.userId,
                    gameId = syncGameId,
                )
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
        val principal = headerAccessor.user as? UserPrincipal
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
            principalName = principal.name,
            sessionId = sessionId,
            userId = userId,
            gameId = resolvedGameId,
        )
    }

    private fun emitSyncWithTelemetry(
        source: String,
        principalName: String,
        sessionId: String,
        userId: Int,
        gameId: Int,
    ) {
        val startedNs = System.nanoTime()
        runCatching { publishSync(principalName, sessionId, userId, gameId) }
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
     * [principalName] is the value from [UserPrincipal.name] (i.e. the username),
     * which is what Spring's [org.springframework.messaging.simp.user.UserDestinationResolver]
     * uses to look up the subscriber's session.  Passing a raw STOMP sessionId here
     * would cause the message to be silently dropped in production because the resolver
     * resolves destinations by principal, not by session ID directly.
     */
    private fun publishSync(principalName: String, sessionId: String, userId: Int, gameId: Int) {
        val snapshot = gameSyncService.buildSnapshot(gameId)
        val headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE).apply {
            setSessionId(sessionId)
            setLeaveMutable(true)
        }.messageHeaders

        messagingTemplate.convertAndSendToUser(
            principalName,
            "/queue/game-sync",
            WebSocketMessage(
                type = MessageType.SYNC,
                sender = "server",
                content = "State sync for reconnecting player",
                payload = mapOf(
                    "targetUserId" to userId,
                    "targetSessionId" to sessionId,
                    "state" to snapshot,
                ),
                gameId = gameId,
            ),
            headers,
        )
    }
}