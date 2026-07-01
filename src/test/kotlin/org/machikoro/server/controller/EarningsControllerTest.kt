package org.machikoro.server.controller

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.TvStationTargetRequest
import org.machikoro.server.dto.WebSocketErrorDto
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.interfaces.EarningsService
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import kotlin.test.assertEquals

class EarningsControllerTest {

    private val earningsService: EarningsService = mock()
    private val messagingTemplate: SimpMessagingTemplate = mock()
    private val gameStateGuard: GameStateGuard = mock()
    private val gameSyncService: GameSyncService = mock()
    private val controller = EarningsController(earningsService, messagingTemplate, gameStateGuard, gameSyncService)

    private val alice = UserPrincipal(userId = 1, username = "alice")

    private fun authedAccessor(): SimpMessageHeaderAccessor =
        SimpMessageHeaderAccessor.create().apply { user = alice }

    private fun gameStateDto(gameId: Int) = GameStateDto(
        game = GameModel(
            id = gameId,
            status = GameStatus.IN_PROGRESS,
            hostUserId = 1,
            lobbyCode = "ABC123",
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = TurnPhase.BUY_OR_BUILD,
            lastDiceRoll = 6,
            hasPurchasedThisTurn = false,
            roundNumber = 1,
            rerolledThisTurn = false,
        ),
        players = listOf(
            PlayerModel(id = 1, gameId = gameId, userId = 1, turnOrder = 0, coins = 5, lastSeenAt = null),
        ),
        playerCards = emptyMap(),
        playerLandmarks = emptyMap(),
        marketplace = emptyMap(),
        turnOrder = listOf(1),
        activePlayerId = 1,
    )

    @Test
    fun `resolveEffects calls service and broadcasts success`() {
        val request = ResolveEffectsRequest(gameId = 1)
        val snapshot = gameStateDto(1)
        val coinDeltas = mapOf(1 to 3, 2 to -3)
        whenever(gameSyncService.buildSnapshot(1)).thenReturn(snapshot)
        whenever(earningsService.resolveEffects(1)).thenReturn(coinDeltas)

        controller.resolveEffects(request, authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(1, alice)
        verify(earningsService).resolveEffects(1)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        assertEquals(1, message.gameId)
        val payload = message.payload as Map<*, *>
        assertEquals("EFFECTS_RESOLVED", payload["event"])
        assertEquals(1, payload["gameId"])
        assertEquals("BUY_OR_BUILD", payload["turnPhase"])
        assertEquals(1, payload["activePlayerId"])
        assertEquals(coinDeltas, payload["coinDeltas"])
        assertEquals(snapshot, payload["state"])
    }

    @Test
    fun `resolveEffects broadcasts domain error when service rejects game lookup`() {
        val request = ResolveEffectsRequest(gameId = 1)
        doThrow(GameNotFoundException("Game 1 not found")).whenever(earningsService).resolveEffects(1)

        controller.resolveEffects(request, authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("server", message.sender)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("GAME_NOT_FOUND", payload.code)
        assertEquals("Game 1 not found", payload.message)
        assertEquals("EFFECTS_FAILED", payload.context["event"])
    }

    @Test
    fun `resolveEffects propagates unexpected service failure to global websocket handler`() {
        val request = ResolveEffectsRequest(gameId = 1)
        doThrow(IllegalStateException("database unavailable")).whenever(earningsService).resolveEffects(1)

        val ex = assertThrows<IllegalStateException> {
            controller.resolveEffects(request, authedAccessor())
        }

        assertEquals("database unavailable", ex.message)
        verify(gameStateGuard).ensureSenderIsActivePlayer(1, alice)
        verify(earningsService).resolveEffects(1)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `resolveEffects broadcasts domain rejection code for duplicate resolution`() {
        val request = ResolveEffectsRequest(gameId = 1)
        doThrow(CustomWebSocketException("EFFECTS_ALREADY_RESOLVED", "Effects have already been resolved for this turn"))
            .whenever(earningsService).resolveEffects(1)

        controller.resolveEffects(request, authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())
        val payload = captor.firstValue.payload as WebSocketErrorDto
        assertEquals("EFFECTS_ALREADY_RESOLVED", payload.code)
        assertEquals("EFFECTS_FAILED", payload.context["event"])
    }

    @Test
    fun `resolveEffects propagates NOT_YOUR_TURN and does not call service or broadcast`() {
        val request = ResolveEffectsRequest(gameId = 1)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(1, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.resolveEffects(request, authedAccessor())
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(earningsService, never()).resolveEffects(any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `resolveEffects propagates GAME_NOT_STARTED and does not call service or broadcast`() {
        val request = ResolveEffectsRequest(gameId = 1)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(1, alice))
            .thenThrow(CustomWebSocketException("GAME_NOT_STARTED", "Game 1 has not started yet"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.resolveEffects(request, authedAccessor())
        }
        assertEquals("GAME_NOT_STARTED", ex.errorCode)
        verify(earningsService, never()).resolveEffects(any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `chooseTvStationTarget calls service and broadcasts success`() {
        val request = TvStationTargetRequest(gameId = 1, targetPlayerId = 2)
        val snapshot = gameStateDto(1)
        val coinDeltas = mapOf(1 to 5, 2 to -5)
        whenever(gameSyncService.buildSnapshot(1)).thenReturn(snapshot)
        whenever(earningsService.resolveTvStationTarget(1, 2)).thenReturn(coinDeltas)

        controller.chooseTvStationTarget(request, authedAccessor())

        verify(gameStateGuard).ensureSenderIsActivePlayer(1, alice)
        verify(earningsService).resolveTvStationTarget(1, 2)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        val payload = message.payload as Map<*, *>
        assertEquals("TV_STATION_RESOLVED", payload["event"])
        assertEquals(1, payload["gameId"])
        assertEquals("BUY_OR_BUILD", payload["turnPhase"])
        assertEquals(1, payload["activePlayerId"])
        assertEquals(coinDeltas, payload["coinDeltas"])
        assertEquals(snapshot, payload["state"])
    }

    @Test
    fun `chooseTvStationTarget broadcasts domain rejection for invalid target`() {
        val request = TvStationTargetRequest(gameId = 1, targetPlayerId = 2)
        doThrow(CustomWebSocketException("INVALID_TV_STATION_TARGET", "Player 2 is not a valid TV Station target"))
            .whenever(earningsService).resolveTvStationTarget(1, 2)

        controller.chooseTvStationTarget(request, authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())
        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        val payload = message.payload as WebSocketErrorDto
        assertEquals("INVALID_TV_STATION_TARGET", payload.code)
        assertEquals("TV_STATION_FAILED", payload.context["event"])
    }

    @Test
    fun `chooseTvStationTarget propagates NOT_YOUR_TURN and does not call service or broadcast`() {
        val request = TvStationTargetRequest(gameId = 1, targetPlayerId = 2)
        whenever(gameStateGuard.ensureSenderIsActivePlayer(1, alice))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        val ex = assertThrows<CustomWebSocketException> {
            controller.chooseTvStationTarget(request, authedAccessor())
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
        verify(earningsService, never()).resolveTvStationTarget(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `resolveEffects throws UNAUTHENTICATED when accessor has no principal`() {
        val request = ResolveEffectsRequest(gameId = 1)
        val unauthed = SimpMessageHeaderAccessor.create()

        val ex = assertThrows<CustomWebSocketException> {
            controller.resolveEffects(request, unauthed)
        }
        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(gameStateGuard, never()).ensureSenderIsActivePlayer(any(), any())
        verify(earningsService, never()).resolveEffects(any())
    }
}
