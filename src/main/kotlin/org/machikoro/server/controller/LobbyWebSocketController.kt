package org.machikoro.server.controller

import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
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
        val principal = headerAccessor.user as? UserPrincipal
            ?: throw RuntimeException("Authenticated principal not found — connection may not have completed STOMP handshake")

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
}