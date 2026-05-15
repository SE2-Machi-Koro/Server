package org.machikoro.server.controller

import org.machikoro.server.auth.requireUserPrincipal
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.StartGameRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.WebSocketConnectionTracker
import io.github.springwolf.core.asyncapi.annotations.AsyncListener
import io.github.springwolf.core.asyncapi.annotations.AsyncOperation
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
    private val purchaseService: PurchaseService,
    private val diceService: DiceService,
    private val lobbyService: LobbyService,
    private val connectionTracker: WebSocketConnectionTracker,
    private val gameStateGuard: GameStateGuard,
    private val playerDao: PlayerDao,
    private val gameSyncService: GameSyncService,
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
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.start",
        description = "Starts the game. Must be sent by the lobby host.",
        payloadType = StartGameRequest::class,
    ))
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

            // Immediately broadcast a GAME_ACTION so clients that only parse
            // activePlayerId from GAME_ACTION frames know whose turn it is
            // without waiting for the first advancePhase or endTurn call.
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = mapOf(
                        "turnPhase" to gameState.game.turnPhase.name,
                        "activePlayerId" to gameState.activePlayerId,
                    ),
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
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.advancePhase",
        description = "Advances the current turn phase. Must be sent by the active player.",
        payloadType = AdvancePhaseRequest::class,
    ))
    fun advancePhase(@Payload request: AdvancePhaseRequest, headerAccessor: SimpMessageHeaderAccessor) {
        requireActivePlayer(request.gameId, headerAccessor)
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
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.purchase",
        description = "Buys an establishment or builds a landmark during BUY_OR_BUILD phase. Must be sent by the active player.",
        payloadType = PurchaseRequest::class,
    ))
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
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.endTurn",
        description = "Ends the active player's turn. Broadcasts next phase or game-won event.",
        payloadType = EndTurnRequest::class,
    ))
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
                gamePhaseService.cleanupFinishedGameData(request.gameId)
            }
        }
    }

    /**
     * Handle dice roll requests and broadcast result to the specific game topic.
     *
     * Message is sent to /app/game.rollDice and broadcast to /topic/game/{gameId}.
     * The result payload contains the player ID, the individual dice values, and
     * a server-side timestamp so all clients see the same result simultaneously.
     */
    @MessageMapping("/game.rollDice")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.rollDice",
        description = "Rolls dice for the active player and broadcasts the result to the game topic.",
        payloadType = RollDiceRequest::class,
    ))
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
     * Broadcasts the new turn phase and the active player's user ID to all
     * subscribers of the game topic. The [activePlayerId] identifies the user
     * whose turn it is so clients can compare it against their own user ID.
     */
    private fun broadcastPhase(gameId: Int, newPhase: TurnPhase) {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        val players = playerDao.getPlayers(gameId)
        val activePlayerId = players.getOrNull(game.currentTurnIndex)?.userId

        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_ACTION,
                sender = "server",
                payload = mapOf(
                    "turnPhase" to newPhase.name,
                    "activePlayerId" to activePlayerId,
                ),
            ),
        )
    }

    private fun broadcastPurchase(gameId: Int, result: PurchaseResult) {
        val payload = linkedMapOf<String, Any?>(
            "event" to "PURCHASE_COMPLETED",
            "turnPhase" to result.turnPhase.name,
            "purchaseType" to result.purchaseType.name,
            "state" to gameSyncService.buildSnapshot(gameId),
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

    /**
     * Resolve the authenticated user from the STOMP session and assert they
     * are the active player for [gameId]. Throws `UNAUTHENTICATED` /
     * `NOT_YOUR_TURN` / `NO_ACTIVE_PLAYER` / `GAME_FINISHED` per the helper
     * and guard contracts.
     */
    private fun requireActivePlayer(gameId: Int, headerAccessor: SimpMessageHeaderAccessor) {
        gameStateGuard.ensureSenderIsActivePlayer(gameId, headerAccessor.requireUserPrincipal())
    }

    private fun requireOwnerOfPlayer(gameId: Int, playerId: Int, headerAccessor: SimpMessageHeaderAccessor) {
        gameStateGuard.ensureSenderOwnsPlayer(gameId, playerId, headerAccessor.requireUserPrincipal())
    }
}
