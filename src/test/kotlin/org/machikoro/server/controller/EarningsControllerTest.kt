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
        whenever(gameSyncService.buildSnapshot(1)).thenReturn(snapshot)

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
        assertEquals(snapshot, payload["state"])
    }

    @Test
    fun `resolveEffects broadcasts error when service throws`() {
        val request = ResolveEffectsRequest(gameId = 1)
        doThrow(GameNotFoundException("Game 1 not found")).whenever(earningsService).resolveEffects(1)

        controller.resolveEffects(request, authedAccessor())

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/1"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("server", message.sender)
        assertEquals("EFFECTS_FAILED", (message.payload as Map<*, *>)["event"])
        assertEquals("Game 1 not found", (message.payload as Map<*, *>)["message"])
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
