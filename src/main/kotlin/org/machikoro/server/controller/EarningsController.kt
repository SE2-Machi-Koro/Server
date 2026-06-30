package org.machikoro.server.controller

import io.github.springwolf.core.asyncapi.annotations.AsyncListener
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
import org.machikoro.server.auth.requireUserPrincipal
import org.machikoro.server.dto.BusinessCenterSwapRequest
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
        val gameTopic = "/topic/game/${request.gameId}"
        try {
            val coinDeltas = earningsService.resolveEffects(request.gameId)
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
                        // playerId -> signed coin delta; client uses it for the
                        // coin / coin-drawer sound effects (issue #389).
                        "coinDeltas" to coinDeltas,
                        "state" to state,
                    ),
                    gameId = request.gameId,
                )
            )
        } catch (e: CustomWebSocketException) {
            // Known effect-resolution rejections are broadcast to the game topic so clients reset pending actions.
            logger.warn("Resolve effects rejected for game {} [{}]: {}", request.gameId, e.errorCode, e.message)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "server",
                    payload = WebSocketErrorDto.from(e, mapOf("event" to "EFFECTS_FAILED")),
                    gameId = request.gameId,
                )
            )
        }
    }

    /**
     * Applies Business Center's one-card exchange action and broadcasts the updated state.
     */
    @MessageMapping("/game.businessCenter.swap")
    @AsyncListener(
        operation = AsyncOperation(
            channelName = "/game.businessCenter.swap",
            description = "Exchanges one non-major establishment with another player after Business Center activates."
        )
    )
    fun swapBusinessCenterCard(@Payload request: BusinessCenterSwapRequest, headerAccessor: SimpMessageHeaderAccessor) {
        val user = headerAccessor.requireUserPrincipal()
        val activePlayer = gameStateGuard.ensureSenderIsActivePlayer(request.gameId, user)
        val gameTopic = "/topic/game/${request.gameId}"
        try {
            earningsService.swapBusinessCenterCard(
                gameId = request.gameId,
                activePlayerId = activePlayer.id,
                targetPlayerId = request.targetPlayerId,
                offeredCardType = request.offeredCardType,
                requestedCardType = request.requestedCardType,
            )
            val state = gameSyncService.buildSnapshot(request.gameId)
            logger.info("Applied Business Center swap for game ${request.gameId}")
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = mapOf(
                        "event" to "BUSINESS_CENTER_SWAP_APPLIED",
                        "gameId" to request.gameId,
                        "turnPhase" to state.game.turnPhase.name,
                        "activePlayerId" to state.activePlayerId,
                        "state" to state,
                    ),
                    gameId = request.gameId,
                )
            )
        } catch (e: CustomWebSocketException) {
            logger.warn("Business Center swap rejected for game {} [{}]: {}", request.gameId, e.errorCode, e.message)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "server",
                    payload = WebSocketErrorDto.from(e, mapOf("event" to "BUSINESS_CENTER_SWAP_FAILED")),
                    gameId = request.gameId,
                )
            )
        }
    }
}
