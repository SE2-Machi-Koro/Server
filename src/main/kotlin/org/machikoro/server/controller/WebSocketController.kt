package org.machikoro.server.controller

import org.machikoro.server.dao.UserDao
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

@Controller
class WebSocketController(
    private val lobbyService: LobbyService,
    private val userDao: UserDao,
    private val connectionTracker: WebSocketConnectionTracker,
    private val gameSyncService: GameSyncService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val logger = LoggerFactory.getLogger(WebSocketController::class.java)

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
     */
    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    fun addUser(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor
    ): WebSocketMessage {
        logger.info("User ${message.sender} joined the chat")
        headerAccessor.sessionAttributes?.put("username", message.sender)

        val user = userDao.findByUsername(message.sender)
        if (user != null) {
            // Prefer server-side mapping to an active game when a player reconnects.
            val mappedInProgressGameId = gameSyncService.findActiveInProgressGameId(user.id)
            var gameIdToJoin = mappedInProgressGameId ?: message.gameId

            if (gameIdToJoin != null) {
                try {
                    lobbyService.addUserToLobby(gameIdToJoin, user.id)
                } catch (e: GameStartedException) {
                    // Start transition race: game flipped while reconnect was in-flight.
                    val remappedInProgressGameId = gameSyncService.findActiveInProgressGameId(user.id)
                    if (remappedInProgressGameId == null) {
                        throw e
                    }
                    gameIdToJoin = remappedInProgressGameId
                }

                val sessionId = headerAccessor.sessionId
                if (sessionId != null) {
                    connectionTracker.register(sessionId, user.id, gameIdToJoin)
                }

                val syncGameId = gameSyncService.findActiveInProgressGameId(user.id)

                if (syncGameId != null && sessionId != null) {
                    publishSync(sessionId, user.id, syncGameId)
                }
            }
        }
        return message
    }

    /**
     * Explicit re-sync endpoint for clients that reconnect mid-transition and miss
     * the GAME_STARTED broadcast.
     */
    @MessageMapping("/game.sync")
    fun syncGameState(
        @Payload request: SyncGameRequest,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val sessionId = headerAccessor.sessionId ?: return
        val userId = connectionTracker.getUserId(sessionId) ?: return

        val resolvedGameId = request.gameId ?: gameSyncService.findActiveInProgressGameId(userId)
        if (resolvedGameId == null || !gameSyncService.isInProgress(resolvedGameId)) {
            logger.info("SYNC skipped for userId={} - no active in-progress game", userId)
            return
        }

        publishSync(sessionId, userId, resolvedGameId)
    }

    private fun publishSync(sessionId: String, userId: Int, gameId: Int) {
        val snapshot = gameSyncService.buildSnapshot(gameId)
        val headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE).apply {
            setSessionId(sessionId)
            setLeaveMutable(true)
        }.messageHeaders

        messagingTemplate.convertAndSendToUser(
            sessionId,
            "/queue/game-sync",
            WebSocketMessage(
                type = MessageType.SYNC,
                sender = "server",
                content = "State sync for reconnecting player",
                payload = mapOf(
                    "targetUserId" to userId,
                    "state" to snapshot,
                ),
                gameId = gameId,
            ),
            headers,
        )
    }
}