package org.machikoro.server.controller

import java.security.Principal
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.LeaveFinishedGameRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.StartGameRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.LeaveFinishedGameService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.WebSocketConnectionTracker
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

@Controller
class GameController(
    private val gamePhaseService: GamePhaseService,
    private val messagingTemplate: SimpMessagingTemplate,
    private val leaveFinishedGameService: LeaveFinishedGameService,
    private val purchaseService: PurchaseService,
    private val diceService: DiceService,
    private val lobbyService: LobbyService,
    private val connectionTracker: WebSocketConnectionTracker,
    private val gameStateGuard: GameStateGuard,
) {
    private val logger = LoggerFactory.getLogger(GameController::class.java)

    /**
     * Handle the START_GAME signal from the lobby host.
     *
     * Message is sent to /app/game.start.
     * On success, a GAME_STARTED event containing the full [GameStateDto] is
     * broadcast to /topic/game/{gameId} so every subscriber transitions
     * simultaneously to the game board.
     *
     * Authorization: the requesting user ID is derived from the STOMP session via
     * [WebSocketConnectionTracker] — a client cannot bypass the host check by
     * forging a user ID in the payload.
     */
    @MessageMapping("/game.start")
    fun startGame(@Payload request: StartGameRequest, headerAccessor: SimpMessageHeaderAccessor) {
        val gameTopic = "/topic/game/${request.gameId}"

        val sessionId = headerAccessor.sessionId
        val requestingUserId = sessionId?.let { connectionTracker.getUserId(it) }

        if (requestingUserId == null) {
            logger.warn("START_GAME rejected — no registered session (sessionId=$sessionId, gameId=${request.gameId})")
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "server",
                    payload = mapOf("event" to "START_FAILED", "message" to "Unknown session — please reconnect"),
                )
            )
            return
        }

        logger.info("START_GAME requested by userId=$requestingUserId for gameId=${request.gameId}")

        try {
            val gameState = lobbyService.startGame(
                gameId = request.gameId,
                requestingUserId = requestingUserId,
            )

            logger.info(
                "Game ${request.gameId} started — players=${gameState.players.size}, " +
                        "turnOrder=${gameState.turnOrder}"
            )

            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_STARTED,
                    sender = "server",
                    content = "Game ${request.gameId} has started",
                    payload = gameState,
                    gameId = request.gameId,
                )
            )
        } catch (e: Exception) {
            logger.error("START_GAME failed for gameId=${request.gameId}", e)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "server",
                    payload = mapOf("event" to "START_FAILED", "message" to (e.message ?: "Unknown error")),
                )
            )
        }
    }

    /**
     * Advances the current turn phase for a game and broadcasts the resulting
     * phase to all subscribers of the game topic.
     *
     * Message is sent to /app/game.advancePhase and broadcast to /topic/game/{gameId}.
     */
    @MessageMapping("/game.advancePhase")
    fun advancePhase(@Payload request: AdvancePhaseRequest, principal: Principal) {
        gameStateGuard.ensureSenderIsActivePlayer(request.gameId, principal)
        val newPhase = gamePhaseService.advancePhase(request.gameId)
        logger.info("Advanced phase for game ${request.gameId} to $newPhase")
        broadcastPhase(request.gameId, newPhase)
    }

    /**
     * Handles one buy/build action from the active player during BUY_OR_BUILD
     * and broadcasts either the purchase result or the win event after a
     * landmark completes the game.
     */
    @MessageMapping("/game.purchase")
    fun purchase(@Payload request: PurchaseRequest, principal: Principal) {
        gameStateGuard.ensureSenderIsActivePlayer(request.gameId, principal)
        val result = purchaseService.purchase(
            gameId = request.gameId,
            purchaseType = request.purchaseType,
            cardType = request.cardType,
            landmarkType = request.landmarkType,
        )
        logger.info("Processed {} purchase for game {}", result.purchaseType, request.gameId)

        broadcastPurchase(request.gameId, result)
    }

    @MessageMapping("/game.endTurn")
    fun endTurn(@Payload request: EndTurnRequest, principal: Principal) {
        gameStateGuard.ensureSenderIsActivePlayer(request.gameId, principal)
        when (val result = gamePhaseService.endTurn(request.gameId)) {
            is EndTurnOutcome.Continue -> {
                logger.info("Ended turn for game ${request.gameId}, new phase ${result.nextPhase}")
                broadcastPhase(request.gameId, result.nextPhase)
            }
            is EndTurnOutcome.Won -> {
                logger.info("Game ${request.gameId} finished, winner=${result.winnerId}")
                broadcastWinner(request.gameId, result.winnerId)
            }
        }
    }

    @MessageMapping("/game.leave")
    fun leaveFinishedGame(@Payload request: LeaveFinishedGameRequest, principal: Principal) {
        gameStateGuard.ensureSenderOwnsPlayer(request.gameId, request.playerId, principal)
        leaveFinishedGameService.leaveFinishedGame(request.gameId, request.playerId)
        logger.info("${request.playerId} left game ${request.gameId}")
        broadcastPlayerLeftFinishedGame(request.gameId, request.playerId)
    }

    /**
     * Handle dice roll requests and broadcast result to the specific game topic.
     * Message is sent to /app/game.rollDice and broadcast to /topic/game/{gameId}
     */
    @MessageMapping("/game.rollDice")
    fun rollDice(@Payload request: RollDiceRequest, principal: Principal) {
        gameStateGuard.ensureSenderIsActivePlayer(request.gameId, principal)
        val gameTopic = "/topic/game/${request.gameId}"
        logger.info("Roll dice request from player ${request.playerId} in game ${request.gameId}")
        try {
            val result = diceService.rollDice(request)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ROLL_DICE,
                    sender = "SERVER",
                    content = "Player ${request.playerId} rolled: ${result.total}",
                    payload = mapOf("dice" to result.dice, "total" to result.total)
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to roll dice for game ${request.gameId}", e)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "SERVER",
                    payload = mapOf("event" to "ROLL_FAILED", "message" to (e.message ?: "Unknown error"))
                )
            )
        }
    }

    private fun broadcastPhase(gameId: Int, newPhase : TurnPhase) {
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_ACTION,
                sender = "server",
                payload = mapOf("turnPhase" to newPhase.name),
            ),
        )
    }

    private fun broadcastPurchase(gameId: Int, result: PurchaseResult) {
        val payload = linkedMapOf<String, String>(
            "turnPhase" to result.turnPhase.name,
            "purchaseType" to result.purchaseType.name,
        )
        result.cardType?.let { payload["cardType"] = it.name }
        result.landmarkType?.let { payload["landmarkType"] = it.name }
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_ACTION,
                sender = "server",
                payload = payload,
            ),
        )
    }

    private fun broadcastPlayerLeftFinishedGame(gameId: Int, playerId: Int) {
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.PLAYER_LEFT_FINISHED_GAME,
                sender = "server",
                payload = mapOf("playerId" to playerId),
            )
        )
    }

    private fun broadcastWinner(gameId: Int, winnerId: Int) {
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_END,
                sender = "server",
                payload = mapOf("winnerId" to winnerId)
            )
        )
    }
}