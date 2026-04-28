package org.machikoro.server.controller

import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.stereotype.Controller
import org.slf4j.LoggerFactory

@Controller
class WebSocketController(
    private val diceService: DiceService
) {
    private val logger = LoggerFactory.getLogger(WebSocketController::class.java)

    @MessageMapping("/chat.send")
    @SendTo("/topic/public")
    fun sendMessage(@Payload message: WebSocketMessage): WebSocketMessage {
        logger.info("Message received from ${message.sender}: ${message.content}")
        return message
    }

    @MessageMapping("/chat.addUser")
    @SendTo("/topic/public")
    fun addUser(
        @Payload message: WebSocketMessage,
        headerAccessor: SimpMessageHeaderAccessor
    ): WebSocketMessage {
        logger.info("User ${message.sender} joined the chat")
        headerAccessor.sessionAttributes?.put("username", message.sender)
        return message
    }

    /**
     * Handles a roll dice request from a client.
     * - Validates that the requesting player is the active player
     * - Validates Train Station ownership if two dice requested
     * - Broadcasts the dice result to all game subscribers
     */
    @MessageMapping("/game.rollDice")
    @SendTo("/topic/game")
    fun rollDice(
        @Payload request: RollDiceRequest,
        headerAccessor: SimpMessageHeaderAccessor
    ): WebSocketMessage {
        logger.info("Roll dice request from player ${request.playerId} in game ${request.gameId}")
        val result = diceService.rollDice(request)
        return WebSocketMessage(
            type = MessageType.ROLL_DICE,
            sender = "SERVER",
            content = "Player ${request.playerId} rolled: ${result.total}",
            payload = mapOf("dice" to result.dice, "total" to result.total)
        )
    }
}