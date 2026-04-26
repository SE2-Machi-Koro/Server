package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.LeaveGameRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.LeaveFinishedGameService
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate

class GameControllerTest {

    private val gamePhaseService = mock<GamePhaseService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val leaveFinishedGameService = mock<LeaveFinishedGameService>()
    private val controller = GameController(gamePhaseService, messagingTemplate, leaveFinishedGameService)

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
    fun `leaveFinishedGame calls service before broadcasting`() {
        val gameId = 1
        val playerId = 10

        controller.leaveFinishedGame(LeaveGameRequest(gameId, playerId))

        val order = org.mockito.kotlin.inOrder(leaveFinishedGameService, messagingTemplate)
        order.verify(leaveFinishedGameService).leaveFinishedGame(gameId, playerId)
        order.verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), any<WebSocketMessage>())
    }

    @Test
    fun `leaveGame gets exception from service`() {
        val gameId = 1
        val playerId = 10

        whenever(leaveFinishedGameService.leaveFinishedGame(gameId, playerId))
            .thenThrow(RuntimeException("boom"))

        org.junit.jupiter.api.assertThrows<RuntimeException> {
            controller.leaveFinishedGame(LeaveGameRequest(gameId, playerId))
        }
    }
    @Test
    fun `leaveGame sends message to correct topic`() {
        val gameId = 5
        val playerId = 20

        controller.leaveFinishedGame(LeaveGameRequest(gameId, playerId))

        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), any<WebSocketMessage>())
    }
    @Test
    fun `leaveGame payload contains correct playerId`() {
        val gameId = 3
        val playerId = 99

        controller.leaveFinishedGame(LeaveGameRequest(gameId, playerId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(mapOf("playerId" to playerId), message.payload)
    }
}
