package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate

class GameEndBroadcasterTest {

    private val gameSyncService = mock<GameSyncService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val broadcaster = GameEndBroadcaster(gameSyncService, messagingTemplate)

    private fun snapshot(gameId: Int) = GameStateDto(
        game = GameModel(
            id = gameId, status = GameStatus.FINISHED, hostUserId = 1,
            lobbyCode = "ABC", maxPlayers = 4, currentTurnIndex = 0,
            turnPhase = TurnPhase.END_TURN, lastDiceRoll = null,
            roundNumber = 5, hasPurchasedThisTurn = false,
            rerolledThisTurn = false
        ),
        players = emptyList(),
        playerCards = emptyMap(),
        playerLandmarks = emptyMap(),
        marketplace = emptyMap(),
        turnOrder = emptyList(),
        activePlayerId = null,
    )

    @Test
    fun `broadcast sends GAME_END to game topic with winner, rounds and snapshot in payload`() {
        val state = snapshot(7)
        whenever(gameSyncService.buildSnapshot(7)).thenReturn(state)

        broadcaster.broadcast(gameId = 7, winnerId = 42, roundsPlayed = 5)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/7"), captor.capture())

        val msg = captor.firstValue
        assertEquals(MessageType.GAME_END, msg.type)
        assertEquals(7, msg.gameId)
        @Suppress("UNCHECKED_CAST")
        val payload = msg.payload as Map<String, Any?>
        assertEquals(42, payload["winnerId"])
        assertEquals(5, payload["roundsPlayed"])
        assertSame(state, payload["state"])
    }
}
