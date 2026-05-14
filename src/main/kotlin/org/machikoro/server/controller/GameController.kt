package org.machikoro.server.controller

import org.machikoro.server.auth.requireUserPrincipal
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.EndTurnOutcome
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
    private val playerDao: PlayerDao,
) {
    private val logger = LoggerFactory.getLogger(GameController::class.java)

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
            logger.info("Game ${request.gameId} started — players=${gameState.players.size}, turnOrder=${gameState.turnOrder}")
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

    @MessageMapping("/game.advancePhase")
    fun advancePhase(@Payload request: AdvancePhaseRequest, headerAccessor: SimpMessageHeaderAccessor) {
        requireActivePlayer(request.gameId, headerAccessor)
        val newPhase = gamePhaseService.advancePhase(request.gameId)
        logger.info("Advanced phase for game ${request.gameId} to $newPhase")
        broadcastPhase(request.gameId, newPhase)
    }

    @MessageMapping("/game.purchase")
    fun purchase(@Payload request: PurchaseRequest, headerAccessor: SimpMessageHeaderAccessor) {
        requireActivePlayer(request.gameId, headerAccessor)
        val result = purchaseService.purchase(
            gameId = request.gameId,
            purchaseType = request.purchaseType,
            cardType = request.cardType,
            landmarkType = request.landmarkType,
        )
        logger.info("Processed {} purchase for game {}", result.purchaseType, request.gameId)
        broadcastPurchase(request.gameId, result)
    }

    /**
     * Ends the active player's turn, checks for a winner, and either finishes
     * the game or advances to the next player's turn.
     *
     * Message is sent to /app/game.endTurn and broadcast to /topic/game/{gameId}.
     */
    @MessageMapping("/game.endTurn")
    fun endTurn(@Payload request: EndTurnRequest, headerAccessor: SimpMessageHeaderAccessor) {
        requireActivePlayer(request.gameId, headerAccessor)
        when (val result = gamePhaseService.endTurn(request.gameId)) {
            is EndTurnOutcome.Continue -> {
                logger.info("Ended turn for game ${request.gameId}, new phase ${result.nextPhase}")
                broadcastPhase(request.gameId, result.nextPhase)
            }
            is EndTurnOutcome.Won -> {
                logger.info("Game ${request.gameId} finished, winner=${result.winnerId}")
                broadcastWin(request.gameId, result.winnerId, result.roundsPlayed)
            }
        }
    }

    /**
     * Removes a player from a finished game.
     *
     * Message is sent to /app/game.leave and broadcast to /topic/game/{gameId}.
     */
    @MessageMapping("/game.leave")
    fun leaveFinishedGame(@Payload request: LeaveFinishedGameRequest, headerAccessor: SimpMessageHeaderAccessor) {
        requireOwnerOfPlayer(request.gameId, request.playerId, headerAccessor)
        leaveFinishedGameService.leaveFinishedGame(request.gameId, request.playerId)
        logger.info("${request.playerId} left game ${request.gameId}")
        broadcastPlayerLeftFinishedGame(request.gameId, request.playerId)
    }

    /**
     * Handle dice roll requests and broadcast result to the specific game topic.
     *
     * Message is sent to /app/game.rollDice and broadcast to /topic/game/{gameId}.
     * The result payload contains the player ID, the individual dice values, and
     * a server-side timestamp so all clients see the same result simultaneously.
     */
    @MessageMapping("/game.rollDice")
    fun rollDice(@Payload request: RollDiceRequest, headerAccessor: SimpMessageHeaderAccessor) {
        requireActivePlayer(request.gameId, headerAccessor)
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
                    payload = mapOf(
                        "playerId" to request.playerId,
                        "result" to result.dice,
                        "timestamp" to System.currentTimeMillis()
                    )
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

    /**
     * Broadcasts the new turn phase and the active user ID to all subscribers
     * of the game topic. The [activeUserId] identifies the user whose turn it
     * is so clients can compare it against their own user ID.
     */
    private fun broadcastPhase(gameId: Int, newPhase: TurnPhase) {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        val players = playerDao.getPlayers(gameId)
        val activeUserId = players.getOrNull(game.currentTurnIndex)?.userId

        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_ACTION,
                sender = "server",
                payload = mapOf(
                    "turnPhase" to newPhase.name,
                    "activeUserId" to activeUserId,
                ),
            ),
        )
    }

    private fun broadcastPurchase(gameId: Int, result: PurchaseResult) {
        val payload = linkedMapOf<String, Any?>(
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

    private fun broadcastWin(gameId: Int, winnerId: Int, roundsPlayed: Int) {
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_END,
                sender = "server",
                payload = mapOf(
                    "winnerId" to winnerId,
                    "roundsPlayed" to roundsPlayed,
                ),
            ),
        )
    }

    private fun requireActivePlayer(gameId: Int, headerAccessor: SimpMessageHeaderAccessor) {
        gameStateGuard.ensureSenderIsActivePlayer(gameId, headerAccessor.requireUserPrincipal())
    }

    private fun requireOwnerOfPlayer(gameId: Int, playerId: Int, headerAccessor: SimpMessageHeaderAccessor) {
        gameStateGuard.ensureSenderOwnsPlayer(gameId, playerId, headerAccessor.requireUserPrincipal())
    }
}