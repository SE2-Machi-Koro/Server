package org.machikoro.server.controller

import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.interfaces.EarningsService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

/**
 * Handles earnings resolution triggered by the frontend after a dice roll
 */
@Controller
class EarningsController(
    private val earningsService: EarningsService,
    private val messagingTemplate: SimpMessagingTemplate
) {
    private val logger = LoggerFactory.getLogger(EarningsController::class.java)

    /**
     * Resolves card effects for all players and notifies everyone in the game
     */
    @MessageMapping("/game.resolveEffects")
    fun resolveEffects(@Payload request: ResolveEffectsRequest) {
        try {
            earningsService.resolveEffects(request.gameId)
            logger.info("Resolved effects for game ${request.gameId}")
            messagingTemplate.convertAndSend(
                "/topic/public",
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = mapOf("event" to "EFFECTS_RESOLVED", "gameId" to request.gameId)
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to resolve effects for game ${request.gameId}", e)
        }
    }
}