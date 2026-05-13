package org.machikoro.server.controller

import org.machikoro.server.auth.userPrincipal
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.LobbyService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.stereotype.Controller

@Controller
class LobbyWebSocketController(
    private val lobbyService: LobbyService,
) {
    private val logger = LoggerFactory.getLogger(LobbyWebSocketController::class.java)

    /**
     * Handles lobby creation via WebSocket.
     *
     * Client sends a message to /app/lobby.create.
     * Server creates a new lobby and broadcasts the created lobby data to /topic/public.
     *
     * Identity is read from the [UserPrincipal] that [org.machikoro.server.auth.StompAuthChannelInterceptor]
     * attaches at CONNECT time — [WebSocketMessage.sender] is ignored to prevent
     * username spoofing.
     */
    @MessageMapping("/lobby.create")
    @SendTo("/topic/public")
    @Suppress("UNUSED_PARAMETER") // Spring requires a @Payload parameter to deserialize the STOMP frame body
    fun createLobby(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ): WebSocketMessage {
        val principal = headerAccessor.userPrincipal()
            ?: throw CustomWebSocketException(
                errorCode = "UNAUTHENTICATED",
                message = "Authenticated principal not found — connection may not have completed STOMP handshake",
            )

        logger.info("User '{}' requested lobby creation", principal.username)

        val lobby = lobbyService.createLobby(principal.userId)

        return WebSocketMessage(
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
    }

    /**
     * Handles joining an existing lobby via WebSocket.
     *
     * Client sends a message to /app/lobby.join with the lobby code in the payload.
     * Server resolves the lobby code, adds the authenticated user to the lobby,
     * and broadcasts the joined player data to /topic/public.
     *
     * Identity is read from the authenticated UserPrincipal. The sender field from
     * the WebSocket payload is ignored to prevent username spoofing.
     */
    @MessageMapping("/lobby.join")
    @SendTo("/topic/public")
    fun joinLobby(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor,
    ): WebSocketMessage {
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

        val player = lobbyService.joinLobby(lobbyCode, principal.userId)

        return WebSocketMessage(
            type = MessageType.LOBBY_JOINED,
            sender = "SERVER",
            content = "Player joined lobby",
            gameId = player.gameId,
            payload = mapOf(
                "playerId" to player.id,
                "userId" to player.userId,
                "gameId" to player.gameId,
                "coins" to player.coins
            )
        )
    }
}