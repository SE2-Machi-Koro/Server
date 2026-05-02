package org.machikoro.server.controller

import org.machikoro.server.dao.UserDao
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.WebSocketConnectionTracker
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.stereotype.Controller
import org.slf4j.LoggerFactory

@Controller
class WebSocketController(
    private val lobbyService: LobbyService,
    private val userDao: UserDao,
    private val connectionTracker: WebSocketConnectionTracker,
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
        if (user != null && message.gameId != null) {
            lobbyService.addUserToLobby(message.gameId, user.id)
            val sessionId = headerAccessor.sessionId
            if (sessionId != null) {
                connectionTracker.register(sessionId, user.id, message.gameId)
            }
        }
        return message
    }
}