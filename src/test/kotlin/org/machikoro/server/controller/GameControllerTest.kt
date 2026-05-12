package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.LeaveFinishedGameRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.dto.StartGameRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.LeaveFinishedGameService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.WebSocketConnectionTracker
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate

class GameControllerTest {

    private val gamePhaseService = mock<GamePhaseService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val leaveFinishedGameService = mock<LeaveFinishedGameService>()
    private val purchaseService = mock<PurchaseService>()
    private val diceService = mock<DiceService>()
    private val lobbyService = mock<LobbyService>()
    private val connectionTracker = mock<WebSocketConnectionTracker>()
    private val gameStateGuard = mock<GameStateGuard>()
    private val playerDao = mock<PlayerDao>()
    private val controller = GameController(
        gamePhaseService, messagingTemplate, leaveFinishedGameService,
        purchaseService, diceService, lobbyService, connectionTracker,
        gameStateGuard, playerDao,
    )

    private val alice = UserPrincipal(userId = 1, username = "alice")

    private val defaultGame = GameModel(
        id = 1, status = GameStatus.IN_PROGRESS, hostUserId = 1,
        lobbyCode = "XYZ", maxPlayers = 4, currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE, lastDiceRoll = null,
        hasPurchasedThisTurn = false, roundNumber = 1,
    )

    private val defaultPlayers = listOf(
        PlayerModel(id = 1, gameId = 1, userId = 10, turnOrder = 0, coins = 3, lastSeenAt = null),
        PlayerModel(id = 2, gameId = 1, userId = 20, turnOrder = 1, coins = 3, lastSeenAt = null),
    )

    /** Authenticated accessor with [alice] resolvable via the helper. */
    private fun authedAccessor(): SimpMessageHeaderAccessor =
        SimpMessageHeaderAccessor.create().apply { user = alice }

    // ── startGame ─────────────────────────────────────────────────────────────

    private fun gameStateDto(gameId: Int) = GameStateDto(
        game = defaultGame.copy(id = gameId),
        players = listOf(
            PlayerModel(id = 1, gameId = gameId, userId = 1, turnOrder = 0, coins = 3, lastSeenAt = null),
            PlayerModel(id = 2, gameId = gameId, userId = 2, turnOrder = 1, coins = 3, lastSeenAt = null),
        ),
        playerCards = emptyMap(),
        turnOrder = listOf(1, 2),
    )

    private fun headerWithSession(sessionId: String): SimpMessageHeaderAccessor {
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionId = sessionId
        accessor.sessionAttributes = mutableMapOf()
        return accessor
    }

    @Test
    fun `startGame broadcasts GAME_STARTED on success`() {
        val gameId = 10
        val sessionId = "session-host"
        whenever(connectionTracker.getUserId(sessionId)).thenReturn(1)
        whenever(lobbyService.startGame(gameId, 1)).thenReturn(gameStateDto(gameId))

        controller.startGame(StartGameRequest(gameId), headerWithSession(sessionId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_STARTED, message.type)
        assertEquals("server", message.sender)
        assertEquals("Game $gameId has started", message.content)
        assertEquals(gameId, message.gameId)
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
        @Suppress("UNCHECKED_CAST")
        assertEquals("START_FAILED", (message.payload as Map<String, Any>)["event"])
        verify(lobbyService, never()).startGame(any(), any())
    }

    @Test
    fun `startGame broadcasts ERROR frame when service throws`() {
        val gameId = 10
        val sessionId = "session-non-host"
        whenever(connectionTracker.getUserId(sessionId)).thenReturn(99)
        whenever(lobbyService.startGame(gameId, 99)).thenThrow(NotHostException("not host"))

        controller.startGame(StartGameRequest(gameId), headerWithSession(sessionId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        @Suppress("UNCHECKED_CAST")
        assertEquals("START_FAILED", (message.payload as Map<String, Any>)["event"])
    }

    // ── advancePhase ──────────────────────────────────────────────────────────

    @Test
    fun `advancePhase delegates to service with the requested game id`() {
        val gameId = 42
        whenever(gamePhaseService.advancePhase(gameId)).thenReturn(TurnPhase.RESOLVE_EFFECTS)
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(defaultGame.copy(id = gameId))
        whenever(playerDao.getPlayers(gameId)).thenReturn(defaultPlayers)

        controller.advancePhase(AdvancePhaseRequest(gameId), authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        verify(gamePhaseService).advancePhase(gameId)
    }

    @Test
    fun `advancePhase broadcasts new phase and activeUserId as GAME_ACTION`() {
        val gameId = 42
        whenever(gamePhaseService.advancePhase(gameId)).thenReturn(TurnPhase.RESOLVE_EFFECTS)
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(defaultGame.copy(id = gameId))
        whenever(playerDao.getPlayers(gameId)).thenReturn(defaultPlayers)

        controller.advancePhase(AdvancePhaseRequest(gameId), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals("RESOLVE_EFFECTS", payload["turnPhase"])
        assertEquals(10, payload["activeUserId"])
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
        verify(gamePhaseService, never()).advancePhase(any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    // ── endTurn ───────────────────────────────────────────────────────────────

    @Test
    fun `endTurn delegates to service with the requested game id`() {
        val gameId = 42
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(defaultGame.copy(id = gameId))
        whenever(playerDao.getPlayers(gameId)).thenReturn(defaultPlayers)

        controller.endTurn(EndTurnRequest(gameId), authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        verify(gamePhaseService).endTurn(gameId)
    }

    @Test
    fun `endTurn broadcasts resulting phase and activeUserId as GAME_ACTION`() {
        val gameId = 42
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))
        whenever(gameStateGuard.ensureGameIsRunning(gameId)).thenReturn(defaultGame.copy(id = gameId))
        whenever(playerDao.getPlayers(gameId)).thenReturn(defaultPlayers)

        controller.endTurn(EndTurnRequest(gameId), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals("ROLL_DICE", payload["turnPhase"])
        assertEquals(10, payload["activeUserId"])
    }

    @Test
    fun `endTurn broadcasts GAME_END on game topic when winner exists`() {
        val gameId = 42
        val winnerId = 1
        val roundsPlayed = 10
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Won(winnerId, roundsPlayed))

        controller.endTurn(EndTurnRequest(gameId), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_END, message.type)
        assertEquals(mapOf("winnerId" to winnerId, "roundsPlayed" to roundsPlayed), message.payload)
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

    // ── purchase ──────────────────────────────────────────────────────────────

    @Test
    fun `purchase delegates to service with the requested payload`() {
        val gameId = 42
        whenever(purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null))
            .thenReturn(PurchaseResult(turnPhase = TurnPhase.BUY_OR_BUILD, purchaseType = PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY))

        controller.purchase(PurchaseRequest(gameId, PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY), authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        verify(purchaseService).purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
    }

    @Test
    fun `purchase broadcasts resulting purchase payload as GAME_ACTION on game topic`() {
        val gameId = 42
        whenever(purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION))
            .thenReturn(PurchaseResult(turnPhase = TurnPhase.BUY_OR_BUILD, purchaseType = PurchaseType.LANDMARK, landmarkType = LandmarkType.TRAIN_STATION))

        controller.purchase(PurchaseRequest(gameId, PurchaseType.LANDMARK, landmarkType = LandmarkType.TRAIN_STATION), authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals("BUY_OR_BUILD", payload["turnPhase"])
        assertEquals("LANDMARK", payload["purchaseType"])
        assertEquals("TRAIN_STATION", payload["landmarkType"])
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

    // ── leaveFinishedGame ─────────────────────────────────────────────────────

    @Test
    fun `leaveFinishedGame calls service before broadcasting`() {
        val gameId = 1
        val playerId = 10
        controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId), authedAccessor())
        val order = inOrder(gameStateGuard, leaveFinishedGameService, messagingTemplate)
        order.verify(gameStateGuard).ensureSenderOwnsPlayer(gameId, playerId, alice)
        order.verify(leaveFinishedGameService).leaveFinishedGame(gameId, playerId)
        order.verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), any<WebSocketMessage>())
    }

    @Test
    fun `leaveFinishedGame gets exception from service`() {
        val gameId = 1
        val playerId = 10
        whenever(leaveFinishedGameService.leaveFinishedGame(gameId, playerId)).thenThrow(RuntimeException("boom"))
        org.junit.jupiter.api.assertThrows<RuntimeException> {
            controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId), authedAccessor())
        }
    }

    @Test
    fun `leaveFinishedGame sends message to correct topic`() {
        val gameId = 5
        val playerId = 20
        controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId), authedAccessor())
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), any<WebSocketMessage>())
    }

    @Test
    fun `leaveFinishedGame payload contains correct playerId and type`() {
        val gameId = 3
        val playerId = 99
        controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId), authedAccessor())
        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())
        val message = captor.firstValue
        assertEquals(MessageType.PLAYER_LEFT_FINISHED_GAME, message.type)
        assertEquals(mapOf("playerId" to playerId), message.payload)
    }

    @Test
    fun `leaveFinishedGame propagates NOT_YOUR_PLAYER and does not call service`() {
        val gameId = 3
        val playerId = 99
        whenever(gameStateGuard.ensureSenderOwnsPlayer(gameId, playerId, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_PLAYER", "You do not own player 99"))
        val ex = assertThrows<CustomWebSocketException> {
            controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId), authedAccessor())
        }
        assertEquals("NOT_YOUR_PLAYER", ex.errorCode)
        verify(leaveFinishedGameService, never()).leaveFinishedGame(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    // ── rollDice ──────────────────────────────────────────────────────────────

    @Test
    fun `rollDice broadcasts result with playerId, result and timestamp to correct game topic`() {
        val gameId = 1
        val playerId = 2
        val request = RollDiceRequest(gameId = gameId, playerId = playerId)
        val response = RollDiceResponse(dice = listOf(3, 4), total = 7)
        whenever(diceService.rollDice(request)).thenReturn(response)

        controller.rollDice(request, authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(gameId, alice)
        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ROLL_DICE, message.type)
        assertEquals("SERVER", message.sender)
        assertEquals("Player $playerId rolled: 7", message.content)

        @Suppress("UNCHECKED_CAST")
        val payload = message.payload as Map<String, Any?>
        assertEquals(playerId, payload["playerId"])
        assertEquals(listOf(3, 4), payload["result"])
        assert(payload["timestamp"] is Long)
    }

    @Test
    fun `rollDice broadcasts error to game topic on failure`() {
        val gameId = 1
        val playerId = 2
        val request = RollDiceRequest(gameId = gameId, playerId = playerId)
        whenever(diceService.rollDice(request)).thenThrow(RuntimeException("dice exploded"))

        controller.rollDice(request, authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())
        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals(mapOf("event" to "ROLL_FAILED", "message" to "dice exploded"), message.payload)
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
        verify(diceService, never()).rollDice(any())
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
        verify(gamePhaseService, never()).advancePhase(any())
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
        assertUnauthenticated { controller.rollDice(RollDiceRequest(gameId = 42, playerId = 1), it) }
        verify(diceService, never()).rollDice(any())
    }

    @Test
    fun `leaveFinishedGame throws UNAUTHENTICATED when accessor has no principal`() {
        assertUnauthenticated { controller.leaveFinishedGame(LeaveFinishedGameRequest(42, 99), it) }
        verify(leaveFinishedGameService, never()).leaveFinishedGame(any(), any())
    }
}