package org.machikoro.server.controller

import io.github.springwolf.core.asyncapi.annotations.AsyncListener
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import org.machikoro.server.auth.userPrincipal
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.service.LobbyService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

@Controller
class LobbyWebSocketController(
    private val lobbyService: LobbyService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val logger = LoggerFactory.getLogger(LobbyWebSocketController::class.java)

    /**
     * Handles lobby creation via WebSocket.
     *
     * Client sends a message to /app/lobby.create.
     * Server creates a new lobby and delivers LOBBY_CREATED only to the creator
     * via /queue/lobby-user{sessionId} — not a global broadcast.
     *
     * Identity is read from the [UserPrincipal] that [org.machikoro.server.auth.StompAuthChannelInterceptor]
     * attaches at CONNECT time — [WebSocketMessage.sender] is ignored to prevent
     * username spoofing.
     */
    @MessageMapping("/lobby.create")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/lobby.create",
        description = "Creates a new lobby for the authenticated user and delivers LOBBY_CREATED only to the creator.",
        payloadType = WebSocketMessage::class,
    ))
    @Suppress("UNUSED_PARAMETER") // Spring requires a @Payload parameter to deserialize the STOMP frame body
    fun createLobby(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val principal = headerAccessor.userPrincipal()
            ?: throw CustomWebSocketException(
                errorCode = "UNAUTHENTICATED",
                message = "Authenticated principal not found — connection may not have completed STOMP handshake",
            )

        val sessionId = headerAccessor.sessionId
        if (sessionId == null) {
            logger.warn("createLobby: missing sessionId for user '{}'", principal.username)
            return
        }

        logger.info("User '{}' requested lobby creation", principal.username)

        val lobby = lobbyService.createLobby(principal.userId)

        // Deliver only to the creator — avoids auto-joining unrelated clients
        messagingTemplate.convertAndSend(
            "/queue/lobby-user$sessionId",
            WebSocketMessage(
                type = MessageType.LOBBY_CREATED,
                sender = "SERVER",
                content = "Lobby created",
                gameId = lobby.id,
                payload = mapOf(
                    "lobbyCode" to lobby.lobbyCode,
                    "hostUserId" to lobby.hostUserId,
                    "status" to lobby.status.name
                )
            )
        )
    }

    /**
     * Handles joining an existing lobby via WebSocket.
     *
     * Client sends a message to /app/lobby.join with the lobby code in the payload.
     * Server resolves the lobby code, adds the authenticated user to the lobby,
     * and broadcasts LOBBY_JOINED to /topic/game/{gameId} so only that lobby's members
     * receive the event. Errors are delivered only to the requesting session.
     *
     * Identity is read from the authenticated UserPrincipal. The sender field from
     * the WebSocket payload is ignored to prevent username spoofing.
     */
    @MessageMapping("/lobby.join")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/lobby.join",
        description = "Joins an existing lobby by lobby code and broadcasts LOBBY_JOINED to /topic/game/{gameId}.",
        payloadType = WebSocketMessage::class,
    ))
    fun joinLobby(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ) {
        val principal = headerAccessor.userPrincipal()
            ?: throw CustomWebSocketException(
                errorCode = "UNAUTHENTICATED",
                message = "Authenticated principal not found — connection may not have completed STOMP handshake",
            )

        val payload = message.payload as? Map<*, *>
            ?: throw CustomWebSocketException(
                errorCode = "INVALID_PAYLOAD",
                message = "Lobby join payload must contain a lobbyCode",
            )

        val lobbyCode = payload["lobbyCode"] as? String
            ?: throw CustomWebSocketException(
                errorCode = "INVALID_LOBBY_CODE",
                message = "Lobby code is missing or invalid",
            )

        logger.info("User '{}' requested to join lobby '{}'", principal.username, lobbyCode)

        val sessionId = headerAccessor.sessionId

        val player = try {
            lobbyService.joinLobby(lobbyCode, principal.userId)
        } catch (ex: GameNotFoundException) {
            logger.warn("Failed to join lobby '{}': {}", lobbyCode, ex.message)

            // Route error only to the requester, not the whole server
            if (sessionId != null) {
                messagingTemplate.convertAndSend(
                    "/queue/lobby-user$sessionId",
                    WebSocketMessage(
                        type = MessageType.ERROR,
                        sender = "SERVER",
                        content = "Lobby code is invalid",
                        payload = mapOf("errorCode" to "INVALID_LOBBY_CODE")
                    )
                )
            }
            return
        }

        // Broadcast join to all members already subscribed to this lobby's topic
        messagingTemplate.convertAndSend(
            "/topic/game/${player.gameId}",
            WebSocketMessage(
                type = MessageType.LOBBY_JOINED,
                sender = "SERVER",
                content = "Player joined lobby",
                gameId = player.gameId,
                payload = mapOf(
                    "playerId" to player.id,
                    "userId" to player.userId,
                    "username" to principal.username,
                    "gameId" to player.gameId,
                    "coins" to player.coins
                )
            )
        )
    }
}