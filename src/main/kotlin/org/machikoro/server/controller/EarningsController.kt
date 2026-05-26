package org.machikoro.server.controller

import io.github.springwolf.core.asyncapi.annotations.AsyncListener
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import org.machikoro.server.auth.requireUserPrincipal
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.interfaces.EarningsService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

/**
 * Handles earnings resolution triggered by the frontend after a dice roll.
 */
@Controller
class EarningsController(
    private val earningsService: EarningsService,
    private val messagingTemplate: SimpMessagingTemplate,
    private val gameStateGuard: GameStateGuard,
    private val gameSyncService: GameSyncService,
) {
    private val logger = LoggerFactory.getLogger(EarningsController::class.java)

    /**
     * Resolves card effects for all players and broadcasts the result to the game topic.
     */
    @MessageMapping("/game.resolveEffects")
    @AsyncListener(
        operation = AsyncOperation(
            channelName = "/game.resolveEffects",
            description = "Resolves card effects for all players after a dice roll."
        )
    )
    fun resolveEffects(@Payload request: ResolveEffectsRequest, headerAccessor: SimpMessageHeaderAccessor) {
        val user = headerAccessor.requireUserPrincipal()
        gameStateGuard.ensureSenderIsActivePlayer(request.gameId, user)
        val gameTopic = "/topic/game/${request.gameId}"
        try {
            earningsService.resolveEffects(request.gameId)
            val state = gameSyncService.buildSnapshot(request.gameId)
            logger.info("Resolved effects for game ${request.gameId}")
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = mapOf(
                        "event" to "EFFECTS_RESOLVED",
                        "gameId" to request.gameId,
                        "turnPhase" to state.game.turnPhase.name,
                        "activePlayerId" to state.activePlayerId,
                        "state" to state,
                    ),
                    gameId = request.gameId,
                )
            )
        } catch (e: CustomWebSocketException) {
            logger.warn("Resolve effects rejected for game {} [{}]: {}", request.gameId, e.errorCode, e.message)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "server",
                    payload = mapOf(
                        "event" to "EFFECTS_FAILED",
                        "code" to e.errorCode,
                        "message" to e.message,
                    ),
                    gameId = request.gameId,
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to resolve effects for game ${request.gameId}", e)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "server",
                    payload = mapOf("event" to "EFFECTS_FAILED", "message" to (e.message ?: "Unknown error"))
                )
            )
        }
    }
}
