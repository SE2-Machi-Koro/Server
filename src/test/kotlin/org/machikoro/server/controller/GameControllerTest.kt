package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.WinConditionService
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate

class GameControllerTest {

    private val gamePhaseService = mock<GamePhaseService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val winConditionService = mock<WinConditionService>()
    private val controller = GameController(gamePhaseService, messagingTemplate, winConditionService)

    @Test
    fun `advancePhase delegates to service with the requested game id`() {
        val gameId = 42
        whenever(gamePhaseService.advancePhase(gameId)).thenReturn(TurnPhase.RESOLVE_EFFECTS)

        controller.advancePhase(AdvancePhaseRequest(gameId))

        verify(gamePhaseService).advancePhase(gameId)
    }

    @Test
    fun `advancePhase broadcasts new phase as GAME_ACTION on topic public`() {
        val gameId = 42
        whenever(gamePhaseService.advancePhase(gameId)).thenReturn(TurnPhase.RESOLVE_EFFECTS)

        controller.advancePhase(AdvancePhaseRequest(gameId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/public"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        assertEquals(mapOf("turnPhase" to "RESOLVE_EFFECTS"), message.payload)
    }

    @Test
    fun `endTurn delegates to service with the requested game id`() {
        val gameId = 42
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(TurnPhase.ROLL_DICE)

        controller.endTurn(EndTurnRequest(gameId))

        verify(gamePhaseService).endTurn(gameId)
    }

    @Test
    fun `endTurn broadcasts resulting phase as GAME_ACTION on topic public`() {
        val gameId = 42
        whenever(winConditionService.detectWinner(gameId)).thenReturn(null)
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(TurnPhase.ROLL_DICE)

        controller.endTurn(EndTurnRequest(gameId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/public"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        assertEquals(mapOf("turnPhase" to "ROLL_DICE"), message.payload)
    }

    @Test
    fun `endTurn broadcasts GAME_END when winner exists`() {
        val gameId = 42
        val winner = mock<PlayerModel>()

        whenever(winner.id).thenReturn(1)
        whenever(winConditionService.detectWinner(gameId)).thenReturn(winner)

        controller.endTurn(EndTurnRequest(gameId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/public"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_END, message.type)
        assertEquals("server", message.sender)
        assertEquals(mapOf("winnerId" to 1), message.payload)
    }

}
