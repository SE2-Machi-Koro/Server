package org.machikoro.server.controller

import io.github.springwolf.core.asyncapi.annotations.AsyncListener
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import org.machikoro.server.auth.requireUserPrincipal
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.WebSocketErrorDto
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
        resolveAndBroadcast(request.gameId, "EFFECTS_RESOLVED", "EFFECTS_FAILED") {
            earningsService.resolveEffects(request.gameId)
        }
    }

    /**
     * Runs an earnings-resolving [action] and broadcasts the outcome to the game topic:
     * on success a [successEvent] GAME_ACTION carrying the fresh snapshot plus the
     * per-player coin deltas (used for the coin sound effects, issue #389); on a known
     * domain rejection a [failureEvent] ERROR so clients can reset pending actions.
     * Unknown failures propagate to the global WebSocket exception handler.
     */
    private fun resolveAndBroadcast(
        gameId: Int,
        successEvent: String,
        failureEvent: String,
        action: () -> Map<Int, Int>,
    ) {
        val gameTopic = "/topic/game/$gameId"
        try {
            val coinDeltas = action()
            val state = gameSyncService.buildSnapshot(gameId)
            logger.info("Broadcasting {} for game {}", successEvent, gameId)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = mapOf(
                        "event" to successEvent,
                        "gameId" to gameId,
                        "turnPhase" to state.game.turnPhase.name,
                        "activePlayerId" to state.activePlayerId,
                        "coinDeltas" to coinDeltas,
                        "state" to state,
                    ),
                    gameId = gameId,
                )
            )
        } catch (e: CustomWebSocketException) {
            logger.warn("{} rejected for game {} [{}]: {}", failureEvent, gameId, e.errorCode, e.message)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "server",
                    payload = WebSocketErrorDto.from(e, mapOf("event" to failureEvent)),
                    gameId = gameId,
                )
            )
        }
    }

}
