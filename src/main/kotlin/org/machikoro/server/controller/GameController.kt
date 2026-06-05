package org.machikoro.server.controller

import org.machikoro.server.auth.requireUserPrincipal
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.EnterGameScreenRequest
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.StartGameRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GameEndBroadcaster
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
    private val gameSyncService: GameSyncService,
    private val gameEndBroadcaster: GameEndBroadcaster,
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

            // Immediately broadcast a GAME_ACTION so clients that parse turn
            // metadata from GAME_ACTION frames know whose turn it is without
            // waiting for the first turn action.
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = buildGameActionPayload(
                        state = gameState,
                        event = "GAME_STARTED",
                    ),
                    gameId = request.gameId,
                )
            )
        } catch (e: CustomWebSocketException) {
            logger.warn("START_GAME rejected for gameId={} [{}]: {}", request.gameId, e.errorCode, e.message)
            broadcastStartFailure(gameTopic, request.gameId, e.message, e.errorCode)
        } catch (e: NotHostException) {
            logger.warn("START_GAME rejected for gameId={}: {}", request.gameId, e.message)
            broadcastStartFailure(gameTopic, request.gameId, e.message, "NOT_HOST")
        }
    }

    /**
     * Called by any player when navigating to the game screen.
     *
     * If the caller is the host and the game is still WAITING, initialization is
     * triggered automatically and GAME_STARTED is broadcast to all subscribers.
     * If the game is already IN_PROGRESS the call is a no-op — [LobbyService.startGame]
     * rejects duplicate starts, satisfying the one-time-only invariant.
     * Non-host players entering before the host are also silently ignored.
     *
     * Message is sent to /app/game.enterScreen.
     */
    @MessageMapping("/game.enterScreen")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.enterScreen",
        description = "Signals navigation to the game screen. Auto-triggers initialization when the host enters a WAITING game.",
        payloadType = EnterGameScreenRequest::class,
    ))
    fun enterGameScreen(@Payload request: EnterGameScreenRequest, headerAccessor: SimpMessageHeaderAccessor) {
        val principal = headerAccessor.requireUserPrincipal()
        val gameId = request.gameId
        val gameTopic = "/topic/game/$gameId"

        try {
            val gameState = lobbyService.startGame(gameId, principal.userId)

            logger.info("Game $gameId initialized on screen entry by userId=${principal.userId}")

            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_STARTED,
                    sender = "server",
                    content = "Game $gameId has started",
                    payload = gameState,
                    gameId = gameId,
                )
            )
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = buildGameActionPayload(state = gameState, event = "GAME_STARTED"),
                    gameId = gameId,
                )
            )
        } catch (e: GameStartedException) {
            // Already initialized — idempotent, only runs once per game
            logger.debug("enterGameScreen: game $gameId already in progress, skipping initialization")
        } catch (e: NotHostException) {
            // Non-host entered screen — host will trigger initialization when they enter
            logger.debug("enterGameScreen: userId=${principal.userId} is not host of game $gameId, skipping")
        }
    }

    /**
     * Retained only to provide a stable error to older clients. Turn phases
     * advance solely as results of rollDice, resolveEffects, and endTurn.
     */
    @MessageMapping("/game.advancePhase")
    @AsyncListener(operation = AsyncOperation(
        channelName = "/game.advancePhase",
        description = "Rejected compatibility endpoint. Use explicit gameplay actions to advance the turn.",
        payloadType = AdvancePhaseRequest::class,
    ))
    fun advancePhase(@Payload request: AdvancePhaseRequest, headerAccessor: SimpMessageHeaderAccessor) {
        requireActivePlayer(request.gameId, headerAccessor)
        throw CustomWebSocketException(
            "DIRECT_PHASE_ADVANCE_FORBIDDEN",
            "Phase progression is controlled by rollDice, resolveEffects, and endTurn actions",
        )
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
        try {
            val result = purchaseService.purchase(
                gameId = request.gameId,
                purchaseType = request.purchaseType,
                cardType = request.cardType,
                landmarkType = request.landmarkType,
            )
            logger.info("Processed {} purchase for game {}", result.purchaseType, request.gameId)
            broadcastPurchase(request.gameId, result)
        } catch (e: CustomWebSocketException) {
            logger.warn("Purchase rejected for game {} [{}]: {}", request.gameId, e.errorCode, e.message)
            broadcastPurchaseFailure(request, e)
        }
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
                broadcastPhase(request.gameId, "TURN_ENDED")
            }
            is EndTurnOutcome.Won -> {
                logger.info("Game ${request.gameId} finished, winner=${result.winnerId}")
                gameEndBroadcaster.broadcast(request.gameId, result.winnerId, result.roundsPlayed)
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
        val rollingPlayer = requireActivePlayer(request.gameId, headerAccessor)
        val gameTopic = "/topic/game/${request.gameId}"
        logger.info("Roll dice request from player ${rollingPlayer.id} in game ${request.gameId}")
        try {
            val result = diceService.rollDice(request, rollingPlayer.id)
            val state = gameSyncService.buildSnapshot(request.gameId)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ROLL_DICE,
                    sender = "SERVER",
                    content = "Player ${rollingPlayer.id} rolled: ${result.total}",
                    payload = mapOf(
                        "event" to "DICE_ROLLED",
                        "turnPhase" to state.game.turnPhase.name,
                        "activePlayerId" to state.activePlayerId,
                        "playerId" to rollingPlayer.id,
                        "result" to result.dice,
                        "total" to result.total,
                        "completed" to result.completed,
                        "timestamp" to System.currentTimeMillis(),
                        "state" to state,
                    ),
                    gameId = request.gameId,
                )
            )
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.GAME_ACTION,
                    sender = "server",
                    payload = buildGameActionPayload(
                        state = state,
                        event = "DICE_ROLLED",
                        "playerId" to rollingPlayer.id,
                        "result" to result.dice,
                        "total" to result.total,
                        "completed" to result.completed,
                    ),
                    gameId = request.gameId,
                )
            )
        } catch (e: CustomWebSocketException) {
            logger.warn("Roll dice rejected for game {} [{}]: {}", request.gameId, e.errorCode, e.message)
            messagingTemplate.convertAndSend(
                gameTopic,
                WebSocketMessage(
                    type = MessageType.ERROR,
                    sender = "SERVER",
                    payload = mapOf("event" to "ROLL_FAILED", "code" to e.errorCode, "message" to e.message)
                )
            )
        }
    }

    private fun broadcastStartFailure(gameTopic: String, gameId: Int, message: String, code: String) {
        messagingTemplate.convertAndSend(
            gameTopic,
            WebSocketMessage(
                type = MessageType.ERROR,
                sender = "server",
                payload = mapOf("event" to "START_FAILED", "code" to code, "message" to message),
                gameId = gameId,
            )
        )
    }

    /**
     * Broadcasts the new turn phase and the active player's user ID to all
     * subscribers of the game topic. The [activePlayerId] identifies the user
     * whose turn it is so clients can compare it against their own user ID.
     */
    private fun broadcastPhase(gameId: Int, event: String) {
        val state = gameSyncService.buildSnapshot(gameId)
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_ACTION,
                sender = "server",
                payload = buildGameActionPayload(
                    state = state,
                    event = event,
                ),
                gameId = gameId,
            ),
        )
    }

    private fun broadcastPurchase(gameId: Int, result: PurchaseResult) {
        val payload = buildGameActionPayload(
            state = gameSyncService.buildSnapshot(gameId),
            event = "PURCHASE_COMPLETED",
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
                gameId = gameId,
            ),
        )
    }

    private fun broadcastPurchaseFailure(request: PurchaseRequest, exception: CustomWebSocketException) {
        val payload = linkedMapOf<String, Any?>(
            "event" to "PURCHASE_FAILED",
            "code" to exception.errorCode,
            "message" to exception.message,
            "purchaseType" to request.purchaseType.name,
        )
        request.cardType?.let { payload["cardType"] = it.name }
        request.landmarkType?.let { payload["landmarkType"] = it.name }

        // Purchase failures use the game-topic WebSocketMessage envelope so
        // clients can leave pending purchase state without parsing raw STOMP
        // user-queue error payloads.
        messagingTemplate.convertAndSend(
            "/topic/game/${request.gameId}",
            WebSocketMessage(
                type = MessageType.ERROR,
                sender = "server",
                payload = payload,
                gameId = request.gameId,
            ),
        )
    }

    private fun buildGameActionPayload(
        state: GameStateDto,
        event: String,
        vararg entries: Pair<String, Any?>,
    ): LinkedHashMap<String, Any?> =
        linkedMapOf<String, Any?>(
            "event" to event,
            "turnPhase" to state.game.turnPhase.name,
            "activePlayerId" to state.activePlayerId,
            "state" to state,
        ).apply {
            entries.forEach { (key, value) -> this[key] = value }
        }

    /**
     * Resolve the authenticated user from the STOMP session and assert they
     * are the active player for [gameId]. Throws `UNAUTHENTICATED` /
     * `NOT_YOUR_TURN` / `NO_ACTIVE_PLAYER` / `GAME_FINISHED` per the helper
     * and guard contracts.
     */
    private fun requireActivePlayer(gameId: Int, headerAccessor: SimpMessageHeaderAccessor): PlayerModel {
        return gameStateGuard.ensureSenderIsActivePlayer(gameId, headerAccessor.requireUserPrincipal())
    }
}
