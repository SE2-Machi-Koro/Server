package org.machikoro.server.controller

import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.LeaveFinishedGameRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GamePhaseService.EndTurnOutcome
import org.machikoro.server.service.LeaveFinishedGameService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

@Controller
class GameController(
    private val gamePhaseService: GamePhaseService,
    private val messagingTemplate: SimpMessagingTemplate,
    private val leaveFinishedGameService: LeaveFinishedGameService,
    private val purchaseService: PurchaseService,
    private val diceService: DiceService
) {
    private val logger = LoggerFactory.getLogger(GameController::class.java)

    @MessageMapping("/game.purchase")
    fun purchase(@Payload request: PurchaseRequest) {
        val result = purchaseService.purchase(
            gameId = request.gameId,
            purchaseType = request.purchaseType,
            cardType = request.cardType,
            landmarkType = request.landmarkType,
        )
        logger.info("Processed {} purchase for game {}", result.purchaseType, request.gameId)

        if (result.purchaseType == PurchaseType.LANDMARK) {
            val winner = winConditionService.detectWinner(request.gameId)
            if (winner != null) {
                logger.info("Game ${request.gameId} has ended. Winner: ${winner.id}")
                gamePhaseService.finishGame(request.gameId)
                broadcastWinner(request.gameId, winner)
                return
            }
        }

        broadcastPurchase(request.gameId, result)
    }

    @MessageMapping("/game.endTurn")
    fun endTurn(@Payload request: EndTurnRequest) {
        when (val result = gamePhaseService.endTurn(request.gameId)) {
            is EndTurnOutcome.Continue -> {
                logger.info("Ended turn for game ${request.gameId}, new phase ${result.nextPhase}")
                broadcastPhase(request.gameId, result.nextPhase)
            }
            is EndTurnOutcome.Won -> {
                logger.info("Game ${request.gameId} finished, winner=${result.winner.id}")
                broadcastWinner(request.gameId, result.winner)
            }
        }
    }

    @MessageMapping("/game.leave")
    fun leaveFinishedGame(@Payload request: LeaveFinishedGameRequest) {
        leaveFinishedGameService.leaveFinishedGame(request.gameId, request.playerId)
        logger.info("${request.playerId} left game ${request.gameId}")
        broadcastPlayerLeftFinishedGame(request.gameId, request.playerId)
    }

    /**
     * Handle dice roll requests and broadcast result to the specific game topic.
     * Message is sent to /app/game.rollDice and broadcast to /topic/game/{gameId}
     */
    @MessageMapping("/game.rollDice")
    fun rollDice(@Payload request: RollDiceRequest) {
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

    private fun broadcastWinner(gameId: Int, winner: PlayerModel) {
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_END,
                sender = "server",
                payload = mapOf("winnerId" to winner.id)
            )
        )
    }
}