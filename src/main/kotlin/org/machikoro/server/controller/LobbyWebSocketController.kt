package org.machikoro.server.controller

import org.machikoro.server.dao.UserDao
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.LobbyService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class LobbyWebSocketController(
    private val lobbyService: LobbyService,
    private val userDao: UserDao
) {
    private val logger = LoggerFactory.getLogger(LobbyWebSocketController::class.java)

    /**
     * Handles lobby creation via WebSocket.
     *
     * Client sends a message to /app/lobby.create.
     * Server creates a new lobby and broadcasts the created lobby data to /topic/public.
     */
    @MessageMapping("/lobby.create")
    @SendTo("/topic/public")
    fun createLobby(@Payload message: WebSocketMessage): WebSocketMessage {
        logger.info("User ${message.sender} requested lobby creation")

        val user = userDao.findByUsername(message.sender)
            ?: throw RuntimeException("User ${message.sender} not found")

        val lobby = lobbyService.createLobby(user.id)

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