package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.EnterGameScreenRequest
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.dto.StartGameRequest
import org.machikoro.server.dto.WebSocketErrorDto
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate

class GameControllerTest {

    private val gamePhaseService = mock<GamePhaseService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val purchaseService = mock<PurchaseService>()
    private val diceService = mock<DiceService>()
    private val lobbyService = mock<LobbyService>()
    private val connectionTracker = mock<WebSocketConnectionTracker>()
    private val gameStateGuard = mock<GameStateGuard>()
    private val gameSyncService = mock<GameSyncService>()
    private val gameEndBroadcaster = mock<GameEndBroadcaster>()
    private val controller = GameController(
        gamePhaseService, messagingTemplate,
        purchaseService, diceService, lobbyService, connectionTracker,
        gameStateGuard, gameSyncService, gameEndBroadcaster,
    )

    private val alice = UserPrincipal(userId = 1, username = "alice")

    private val defaultGame = GameModel(
        id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1,
        lobbyCode = "XYZ", maxPlayers = 4, currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE, lastDiceRoll = null,
        hasPurchasedThisTurn = false, roundNumber = 1,
        rerolledThisTurn = false
    )

    private fun authedAccessor(): SimpMessageHeaderAccessor =
        SimpMessageHeaderAccessor.create().apply { user = alice }

    private fun gameStateDto(
        gameId: Int,
        turnPhase: TurnPhase = defaultGame.turnPhase,
        activePlayerId: Int? = 1,
    ) = GameStateDto(
        game = defaultGame.copy(id = gameId, turnPhase = turnPhase),
        players = listOf(
            PlayerModel(id = 1, gameId = gameId, userId = 1, turnOrder = 0, coins = 3, lastSeenAt = null),
            PlayerModel(id = 2, gameId = gameId, userId = 2, turnOrder = 1, coins = 3, lastSeenAt = null),
        ),
        playerCards = emptyMap(),
        playerLandmarks = emptyMap(),
        marketplace = emptyMap(),
        turnOrder = listOf(1, 2),
        activePlayerId = activePlayerId,
    )

    private fun headerWithSession(sessionId: String): SimpMessageHeaderAccessor {
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionId = sessionId
        accessor.sessionAttributes = mutableMapOf()
        return accessor
    }

    // ── startGame ─────────────────────────────────────────────────────────────

    @Test
    fun `startGame broadcasts GAME_STARTED on success`() {
        val gameId = 10
        val sessionId = "session-host"
        val snapshot = gameStateDto(gameId)
        whenever(connectionTracker.getUserId(sessionId)).thenReturn(1)
        whenever(lobbyService.startGame(gameId, 1)).thenReturn(snapshot)

        controller.startGame(StartGameRequest(gameId), headerWithSession(sessionId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_STARTED, message.type)
        assertEquals("server", message.sender)
        assertEquals("Game $gameId has started", message.content)
        assertEquals(gameId, message.gameId)

        val phaseMessage = captor.secondValue
        assertEquals(MessageType.GAME_ACTION, phaseMessage.type)
        @Suppress("UNCHECKED_CAST")
        val payload = phaseMessage.payload as Map<String, Any?>
        assertEquals("GAME_STARTED", payload["event"])
        assertEquals("ROLL_DICE", payload["turnPhase"])
        assertEquals(1, payload["activePlayerId"])
        assertEquals(snapshot, payload["state"])
        assertEquals(gameId, phaseMessage.gameId)
    }

    @Test
    fun `startGame broadcasts ERROR frame when session is unknown`() {
        val gameId = 10
        val sessionId = "unknown-session"
        whenever(connectionTracker.getUserId(sessionId)).thenReturn(null)

        controller.startGame(StartGameRequest(gameId), headerWithSession(sessionId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("UNKNOWN_SESSION", payload.code)
        assertEquals("Unknown session - please reconnect", payload.message)
        assertEquals("START_FAILED", payload.context["event"])
        verify(lobbyService, never()).startGame(any(), any())
    }

    @Test
    fun `startGame broadcasts ERROR frame when session id is missing`() {
        val gameId = 10

        controller.startGame(StartGameRequest(gameId), SimpMessageHeaderAccessor.create())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals(gameId, message.gameId)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("UNKNOWN_SESSION", payload.code)
        assertEquals("Unknown session - please reconnect", payload.message)
        assertEquals("START_FAILED", payload.context["event"])
        verify(connectionTracker, never()).getUserId(any())
        verify(lobbyService, never()).startGame(any(), any())
    }

    @Test
    fun `startGame broadcasts typed ERROR frame when service rejects`() {
        val gameId = 10
        val sessionId = "session-non-host"
        whenever(connectionTracker.getUserId(sessionId)).thenReturn(99)
        whenever(lobbyService.startGame(gameId, 99)).thenThrow(NotHostException("not host"))

        controller.startGame(StartGameRequest(gameId), headerWithSession(sessionId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("NOT_HOST", payload.code)
        assertEquals("not host", payload.message)
        assertEquals("START_FAILED", payload.context["event"])
    }

    @Test
    fun `startGame propagates unexpected service failure to global websocket handler`() {
        val gameId = 10
        val sessionId = "session-host"
        whenever(connectionTracker.getUserId(sessionId)).thenReturn(1)
        whenever(lobbyService.startGame(gameId, 1)).thenThrow(IllegalStateException("database unavailable"))

        val ex = assertThrows<IllegalStateException> {
            controller.startGame(StartGameRequest(gameId), headerWithSession(sessionId))
        }
        assertEquals("database unavailable", ex.message)
        verify(lobbyService).startGame(gameId, 1)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    // ── enterGameScreen ───────────────────────────────────────────────────────

    @Test
    fun `enterGameScreen broadcasts GAME_STARTED when host enters WAITING game`() {
        val gameId = 10
        val snapshot = gameStateDto(gameId)
        whenever(lobbyService.startGame(gameId, alice.userId)).thenReturn(snapshot)

        controller.enterGameScreen(EnterGameScreenRequest(gameId), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val first = captor.firstValue
        assertEquals(MessageType.GAME_STARTED, first.type)
        assertEquals("server", first.sender)
        assertEquals("Game $gameId has started", first.content)
        assertEquals(gameId, first.gameId)

        val second = captor.secondValue
        assertEquals(MessageType.GAME_ACTION, second.type)
        @Suppress("UNCHECKED_CAST")
        val payload = second.payload as Map<String, Any?>
        assertEquals("GAME_STARTED", payload["event"])
        assertEquals(snapshot, payload["state"])
    }

    @Test
    fun `enterGameScreen is a no-op when game is already IN_PROGRESS`() {
        val gameId = 10
        whenever(lobbyService.startGame(gameId, alice.userId))
            .thenThrow(GameStartedException("Game $gameId has already started"))

        controller.enterGameScreen(EnterGameScreenRequest(gameId), authedAccessor())

        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `enterGameScreen is a no-op when caller is not the host`() {
        val gameId = 10
        whenever(lobbyService.startGame(gameId, alice.userId))
            .thenThrow(NotHostException("User ${alice.userId} is not the host of game $gameId"))

        controller.enterGameScreen(EnterGameScreenRequest(gameId), authedAccessor())

        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `enterGameScreen propagates unexpected service failure to global websocket handler`() {
        val gameId = 10
        whenever(lobbyService.startGame(gameId, alice.userId))
            .thenThrow(RuntimeException("unexpected failure"))

        val ex = assertThrows<RuntimeException> {
            controller.enterGameScreen(EnterGameScreenRequest(gameId), authedAccessor())
        }
        assertEquals("unexpected failure", ex.message)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `enterGameScreen throws UNAUTHENTICATED when accessor has no principal`() {
        val unauthed = SimpMessageHeaderAccessor.create()
        val ex = assertThrows<CustomWebSocketException> {
            controller.enterGameScreen(EnterGameScreenRequest(10), unauthed)
        }
        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(lobbyService, never()).startGame(any(), any())
    }

    // ── advancePhase ──────────────────────────────────────────────────────────

    @Test
    fun `advancePhase is rejected without mutating state`() {
        val gameId = 42

        val ex = assertThrows<CustomWebSocketException> {
            controller.advancePhase(AdvancePhaseRequest(gameId), authedAccessor())
        }

        assertEquals("DIRECT_PHASE_ADVANCE_FORBIDDEN", ex.errorCode)
        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
        verify(gameSyncService, never()).buildSnapshot(any())
    }

    @Test
    fun `advancePhase rejection does not invoke end turn behavior`() {
        val gameId = 42

        assertThrows<CustomWebSocketException> {
            controller.advancePhase(AdvancePhaseRequest(gameId), authedAccessor())
        }

        verify(gamePhaseService, never()).endTurn(any())
    }

    @Test
    fun `advancePhase propagates NOT_YOUR_TURN and does not call service`() {
        val gameId = 42
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.advancePhase(AdvancePhaseRequest(gameId), authedAccessor())
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `advancePhase propagates GAME_NOT_STARTED and does not call service`() {
        val gameId = 42
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("GAME_NOT_STARTED", "Game $gameId has not started yet"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.advancePhase(AdvancePhaseRequest(gameId), authedAccessor())
        }
        assertEquals("GAME_NOT_STARTED", ex.errorCode)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    // ── endTurn ───────────────────────────────────────────────────────────────

    @Test
    fun `endTurn delegates to service with the requested game id`() {
        val gameId = 42
        val snapshot = gameStateDto(gameId, turnPhase = TurnPhase.ROLL_DICE)
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(snapshot)

        controller.endTurn(EndTurnRequest(gameId), authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        verify(gamePhaseService).endTurn(gameId)
    }

    @Test
    fun `endTurn broadcasts resulting phase and activePlayerId as GAME_ACTION`() {
        val gameId = 42
        val snapshot = gameStateDto(gameId, turnPhase = TurnPhase.ROLL_DICE)
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(snapshot)

        controller.endTurn(EndTurnRequest(gameId), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals("TURN_ENDED", payload["event"])
        assertEquals("ROLL_DICE", payload["turnPhase"])
        assertEquals(1, payload["activePlayerId"])
        assertEquals(snapshot, payload["state"])
    }

    @Test
    fun `endTurn delegates GAME_END broadcast to GameEndBroadcaster when winner exists`() {
        val gameId = 42
        val winnerId = 1
        val roundsPlayed = 10
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Won(winnerId, roundsPlayed))

        controller.endTurn(EndTurnRequest(gameId), authedAccessor())

        // Broadcast logic itself is owned by GameEndBroadcaster (covered by its own test);
        // here we only verify the controller delegates with the right arguments.
        verify(gameEndBroadcaster).broadcast(gameId, winnerId, roundsPlayed)
        verify(gamePhaseService).cleanupFinishedGameData(gameId)
    }

    @Test
    fun `endTurn propagates NOT_YOUR_TURN and does not call service`() {
        val gameId = 42
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.endTurn(EndTurnRequest(gameId), authedAccessor())
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(gamePhaseService, never()).endTurn(any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `endTurn propagates GAME_NOT_STARTED and does not call service`() {
        val gameId = 42
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("GAME_NOT_STARTED", "Game $gameId has not started yet"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.endTurn(EndTurnRequest(gameId), authedAccessor())
        }
        assertEquals("GAME_NOT_STARTED", ex.errorCode)
        verify(gamePhaseService, never()).endTurn(any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    // ── purchase ──────────────────────────────────────────────────────────────

    @Test
    fun `purchase delegates to service with the requested payload`() {
        val gameId = 42
        val snapshot = gameStateDto(gameId, turnPhase = TurnPhase.BUY_OR_BUILD)
        whenever(purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null))
            .thenReturn(PurchaseResult(turnPhase = TurnPhase.BUY_OR_BUILD, purchaseType = PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY))
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(snapshot)

        controller.purchase(PurchaseRequest(gameId, PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY), authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        verify(purchaseService).purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
    }

    @Test
    fun `purchase broadcasts resulting purchase payload as GAME_ACTION on game topic`() {
        val gameId = 42
        val snapshot = gameStateDto(gameId, turnPhase = TurnPhase.BUY_OR_BUILD)
        whenever(purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION))
            .thenReturn(PurchaseResult(turnPhase = TurnPhase.BUY_OR_BUILD, purchaseType = PurchaseType.LANDMARK, landmarkType = LandmarkType.TRAIN_STATION))
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(snapshot)

        controller.purchase(PurchaseRequest(gameId, PurchaseType.LANDMARK, landmarkType = LandmarkType.TRAIN_STATION), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals("PURCHASE_COMPLETED", payload["event"])
        assertEquals("BUY_OR_BUILD", payload["turnPhase"])
        assertEquals("LANDMARK", payload["purchaseType"])
        assertEquals("TRAIN_STATION", payload["landmarkType"])
        assertEquals(snapshot, payload["state"])
        assertEquals(gameId, message.gameId)
    }

    @Test
    fun `purchase broadcasts handled service rejection as ERROR on game topic`() {
        val gameId = 42
        whenever(purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.STADIUM, null))
            .thenThrow(
                CustomWebSocketException(
                    "DUPLICATE_PURPLE_ESTABLISHMENT",
                    "Player already owns purple establishment STADIUM",
                )
            )

        controller.purchase(PurchaseRequest(gameId, PurchaseType.ESTABLISHMENT, cardType = CardType.STADIUM), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("server", message.sender)
        assertEquals(gameId, message.gameId)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("DUPLICATE_PURPLE_ESTABLISHMENT", payload.code)
        assertEquals("Player already owns purple establishment STADIUM", payload.message)
        assertEquals("PURCHASE_FAILED", payload.context["event"])
        assertEquals("ESTABLISHMENT", payload.context["purchaseType"])
        assertEquals("STADIUM", payload.context["cardType"])
    }

    @Test
    fun `purchase broadcasts landmark rejection context without card type`() {
        val gameId = 42
        whenever(purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION))
            .thenThrow(
                CustomWebSocketException(
                    "LANDMARK_ALREADY_BUILT",
                    "Player already built landmark TRAIN_STATION",
                )
            )

        controller.purchase(PurchaseRequest(gameId, PurchaseType.LANDMARK, landmarkType = LandmarkType.TRAIN_STATION), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("server", message.sender)
        assertEquals(gameId, message.gameId)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("LANDMARK_ALREADY_BUILT", payload.code)
        assertEquals("Player already built landmark TRAIN_STATION", payload.message)
        assertEquals("PURCHASE_FAILED", payload.context["event"])
        assertEquals("LANDMARK", payload.context["purchaseType"])
        assertEquals(LandmarkType.TRAIN_STATION.name, payload.context["landmarkType"])
        assertEquals(false, payload.context.containsKey("cardType"))
    }

    @Test
    fun `purchase propagates NOT_YOUR_TURN and does not call service`() {
        val gameId = 42
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.purchase(PurchaseRequest(gameId, PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY), authedAccessor())
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(purchaseService, never()).purchase(any(), any(), any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `purchase propagates GAME_NOT_STARTED and does not call service`() {
        val gameId = 42
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("GAME_NOT_STARTED", "Game $gameId has not started yet"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.purchase(PurchaseRequest(gameId, PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY), authedAccessor())
        }
        assertEquals("GAME_NOT_STARTED", ex.errorCode)
        verify(purchaseService, never()).purchase(any(), any(), any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    // ── rollDice ──────────────────────────────────────────────────────────────

    @Test
    fun `rollDice broadcasts result with playerId, result and timestamp to correct game topic`() {
        val gameId = 1
        val activePlayer = PlayerModel(id = 9, gameId = gameId, userId = alice.userId, turnOrder = 0, coins = 3, lastSeenAt = null)
        val request = RollDiceRequest(gameId = gameId, playerId = 999)
        val response = RollDiceResponse(result = listOf(3, 4), total = 7)
        val snapshot = gameStateDto(gameId, turnPhase = TurnPhase.RESOLVE_EFFECTS)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice)).thenReturn(activePlayer)
        whenever(diceService.rollDice(request, activePlayer.id)).thenReturn(response)
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(snapshot)

        controller.rollDice(request, authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ROLL_DICE, message.type)
        assertEquals("SERVER", message.sender)
        assertEquals("Player ${activePlayer.id} rolled: 7", message.content)

        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals("DICE_ROLLED", payload["event"])
        assertEquals("RESOLVE_EFFECTS", payload["turnPhase"])
        assertEquals(1, payload["activePlayerId"])
        assertEquals(activePlayer.id, payload["playerId"])
        assertEquals(listOf(3, 4), payload["result"])
        assertEquals(7, payload["total"])
        assertEquals(true, payload["completed"])
        assert(payload["timestamp"] is Long)
        assertEquals(snapshot, payload["state"])
        assertEquals(gameId, message.gameId)

        val actionMessage = captor.secondValue
        assertEquals(MessageType.GAME_ACTION, actionMessage.type)
        assertEquals("server", actionMessage.sender)
        assertEquals(gameId, actionMessage.gameId)
        @Suppress("UNCHECKED_CAST")
        val actionPayload = actionMessage.payload as Map<String, Any?>
        assertEquals("DICE_ROLLED", actionPayload["event"])
        assertEquals("RESOLVE_EFFECTS", actionPayload["turnPhase"])
        assertEquals(1, actionPayload["activePlayerId"])
        assertEquals(activePlayer.id, actionPayload["playerId"])
        assertEquals(listOf(3, 4), actionPayload["result"])
        assertEquals(7, actionPayload["total"])
        assertEquals(true, actionPayload["completed"])
        assertEquals(snapshot, actionPayload["state"])
        verify(diceService).rollDice(request, activePlayer.id)
        verify(gameStateGuard, never()).ensureSenderOwnsPlayer(any(), any(), any())
    }

    @Test
    fun `rollDice propagates unexpected service failure to global websocket handler`() {
        val gameId = 1
        val activePlayer = PlayerModel(id = 9, gameId = gameId, userId = alice.userId, turnOrder = 0, coins = 3, lastSeenAt = null)
        val request = RollDiceRequest(gameId = gameId, playerId = null)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice)).thenReturn(activePlayer)
        whenever(diceService.rollDice(request, activePlayer.id)).thenThrow(RuntimeException("dice exploded"))

        val ex = assertThrows<RuntimeException> {
            controller.rollDice(request, authedAccessor())
        }
        assertEquals("dice exploded", ex.message)
        verify(diceService).rollDice(request, activePlayer.id)
        verify(gameStateGuard, never()).ensureSenderOwnsPlayer(any(), any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `rollDice broadcasts domain error code to game topic on rejected roll`() {
        val gameId = 1
        val activePlayer = PlayerModel(id = 9, gameId = gameId, userId = alice.userId, turnOrder = 0, coins = 3, lastSeenAt = null)
        val request = RollDiceRequest(gameId = gameId, playerId = null)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice)).thenReturn(activePlayer)
        whenever(diceService.rollDice(request, activePlayer.id))
            .thenThrow(CustomWebSocketException("ROLL_ALREADY_COMPLETED", "Dice have already been rolled for this turn"))

        controller.rollDice(request, authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())
        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("SERVER", message.sender)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("ROLL_ALREADY_COMPLETED", payload.code)
        assertEquals("Dice have already been rolled for this turn", payload.message)
        assertEquals("ROLL_FAILED", payload.context["event"])
        verify(diceService).rollDice(request, activePlayer.id)
        verify(gameStateGuard, never()).ensureSenderOwnsPlayer(any(), any(), any())
    }

    @Test
    fun `rollDice propagates NOT_YOUR_TURN and does not call service or broadcast`() {
        val gameId = 1
        val playerId = 2
        val request = RollDiceRequest(gameId = gameId, playerId = playerId)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.rollDice(request, authedAccessor())
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(diceService, never()).rollDice(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `rollDice propagates GAME_NOT_STARTED and does not call service or broadcast`() {
        val gameId = 1
        val request = RollDiceRequest(gameId = gameId, playerId = null)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("GAME_NOT_STARTED", "Game $gameId has not started yet"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.rollDice(request, authedAccessor())
        }
        assertEquals("GAME_NOT_STARTED", ex.errorCode)
        verify(diceService, never()).rollDice(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }
// same tests as with roll dice since they should react the same
    @Test
    fun `rerollDice broadcasts result with playerId, result and timestamp to correct game topic`() {
        val gameId = 1
        val activePlayer = PlayerModel(id = 9, gameId = gameId, userId = alice.userId, turnOrder = 0, coins = 3, lastSeenAt = null)
        val request = RollDiceRequest(gameId = gameId, playerId = 999)
        val response = RollDiceResponse(result = listOf(3, 4), total = 7)
        val snapshot = gameStateDto(gameId, turnPhase = TurnPhase.RESOLVE_EFFECTS)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice)).thenReturn(activePlayer)
        whenever(diceService.rerollDice(request, activePlayer.id)).thenReturn(response)
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(snapshot)

        controller.rerollDice(request, authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ROLL_DICE, message.type)
        assertEquals("SERVER", message.sender)
        assertEquals("Player ${activePlayer.id} rolled: 7", message.content)

        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals("DICE_REROLLED", payload["event"])
        assertEquals("RESOLVE_EFFECTS", payload["turnPhase"])
        assertEquals(1, payload["activePlayerId"])
        assertEquals(activePlayer.id, payload["playerId"])
        assertEquals(listOf(3, 4), payload["result"])
        assertEquals(7, payload["total"])
        assertEquals(true, payload["completed"])
        assert(payload["timestamp"] is Long)
        assertEquals(snapshot, payload["state"])
        assertEquals(gameId, message.gameId)

        val actionMessage = captor.secondValue
        assertEquals(MessageType.GAME_ACTION, actionMessage.type)
        assertEquals("server", actionMessage.sender)
        assertEquals(gameId, actionMessage.gameId)
        @Suppress("UNCHECKED_CAST")
        val actionPayload = actionMessage.payload as Map<String, Any?>
        assertEquals("DICE_REROLLED", actionPayload["event"])
        assertEquals("RESOLVE_EFFECTS", actionPayload["turnPhase"])
        assertEquals(1, actionPayload["activePlayerId"])
        assertEquals(activePlayer.id, actionPayload["playerId"])
        assertEquals(listOf(3, 4), actionPayload["result"])
        assertEquals(7, actionPayload["total"])
        assertEquals(true, actionPayload["completed"])
        assertEquals(snapshot, actionPayload["state"])
        verify(diceService).rerollDice(request, activePlayer.id)
        verify(gameStateGuard, never()).ensureSenderOwnsPlayer(any(), any(), any())
    }

    @Test
    fun `rerollDice propagates unexpected service failure to global websocket handler`() {
        val gameId = 1
        val activePlayer = PlayerModel(id = 9, gameId = gameId, userId = alice.userId, turnOrder = 0, coins = 3, lastSeenAt = null)
        val request = RollDiceRequest(gameId = gameId, playerId = null)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice)).thenReturn(activePlayer)
        whenever(diceService.rerollDice(request, activePlayer.id)).thenThrow(RuntimeException("dice exploded"))

        val ex = assertThrows<RuntimeException> {
            controller.rerollDice(request, authedAccessor())
        }

        assertEquals("dice exploded", ex.message)
        verify(diceService).rerollDice(request, activePlayer.id)
        verify(gameStateGuard, never()).ensureSenderOwnsPlayer(any(), any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `rerollDice broadcasts domain error code to game topic on rejected roll`() {
        val gameId = 1
        val activePlayer = PlayerModel(id = 9, gameId = gameId, userId = alice.userId, turnOrder = 0, coins = 3, lastSeenAt = null)
        val request = RollDiceRequest(gameId = gameId, playerId = null)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice)).thenReturn(activePlayer)
        whenever(diceService.rerollDice(request, activePlayer.id))
            .thenThrow(CustomWebSocketException("REROLL_ALREADY_COMPLETED", "Dice have already been rerolled for this turn"))

        controller.rerollDice(request, authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())
        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("SERVER", message.sender)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("REROLL_ALREADY_COMPLETED", payload.code)
        assertEquals("Dice have already been rerolled for this turn", payload.message)
        assertEquals("REROLL_FAILED", payload.context["event"])
        verify(diceService).rerollDice(request, activePlayer.id)
        verify(gameStateGuard, never()).ensureSenderOwnsPlayer(any(), any(), any())
    }

    @Test
    fun `rerollDice propagates NOT_YOUR_TURN and does not call service or broadcast`() {
        val gameId = 1
        val playerId = 2
        val request = RollDiceRequest(gameId = gameId, playerId = playerId)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.rerollDice(request, authedAccessor())
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(diceService, never()).rerollDice(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `rerollDice propagates GAME_NOT_STARTED and does not call service or broadcast`() {
        val gameId = 1
        val request = RollDiceRequest(gameId = gameId, playerId = null)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(gameId, alice))
            .thenThrow(CustomWebSocketException("GAME_NOT_STARTED", "Game $gameId has not started yet"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.rerollDice(request, authedAccessor())
        }
        assertEquals("GAME_NOT_STARTED", ex.errorCode)
        verify(diceService, never()).rerollDice(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    // ── UNAUTHENTICATED at the controller boundary ────────────────────────────

    private fun assertUnauthenticated(call: (SimpMessageHeaderAccessor) -> Unit) {
        val unauthed = SimpMessageHeaderAccessor.create()
        val ex = assertThrows<CustomWebSocketException> { call(unauthed) }
        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(gameStateGuard, never()).ensureSenderIsActivePlayer(any(), any())
        verify(gameStateGuard, never()).ensureSenderOwnsPlayer(any(), any(), any())
    }

    @Test
    fun `advancePhase throws UNAUTHENTICATED when accessor has no principal`() {
        assertUnauthenticated { controller.advancePhase(AdvancePhaseRequest(42), it) }
    }

    @Test
    fun `purchase throws UNAUTHENTICATED when accessor has no principal`() {
        assertUnauthenticated {
            controller.purchase(PurchaseRequest(42, PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY), it)
        }
        verify(purchaseService, never()).purchase(any(), any(), any(), any())
    }

    @Test
    fun `endTurn throws UNAUTHENTICATED when accessor has no principal`() {
        assertUnauthenticated { controller.endTurn(EndTurnRequest(42), it) }
        verify(gamePhaseService, never()).endTurn(any())
    }

    @Test
    fun `rollDice throws UNAUTHENTICATED when accessor has no principal`() {
        assertUnauthenticated { controller.rerollDice(RollDiceRequest(gameId = 42, playerId = 1), it) }
        verify(diceService, never()).rerollDice(any(), any())
    }

}
