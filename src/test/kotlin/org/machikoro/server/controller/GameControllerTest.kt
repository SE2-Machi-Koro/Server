package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate

class GameControllerTest {

    private val gamePhaseService = mock<GamePhaseService>()
    private val purchaseService = mock<PurchaseService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val controller = GameController(gamePhaseService, purchaseService, messagingTemplate)

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
    fun `purchase delegates to service with the requested payload`() {
        val gameId = 42
        whenever(
            purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
        ).thenReturn(
            PurchaseResult(
                turnPhase = TurnPhase.BUY_OR_BUILD,
                purchaseType = PurchaseType.ESTABLISHMENT,
                cardType = CardType.BAKERY,
            )
        )

        controller.purchase(PurchaseRequest(gameId, PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY))

        verify(purchaseService).purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
    }

    @Test
    fun `purchase broadcasts resulting purchase payload as GAME_ACTION on topic public`() {
        val gameId = 42
        whenever(
            purchaseService.purchase(gameId, PurchaseType.LANDMARK, null, LandmarkType.TRAIN_STATION)
        ).thenReturn(
            PurchaseResult(
                turnPhase = TurnPhase.BUY_OR_BUILD,
                purchaseType = PurchaseType.LANDMARK,
                landmarkType = LandmarkType.TRAIN_STATION,
            )
        )

        controller.purchase(PurchaseRequest(gameId, PurchaseType.LANDMARK, landmarkType = LandmarkType.TRAIN_STATION))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/public"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        assertEquals(
            mapOf(
                "turnPhase" to "BUY_OR_BUILD",
                "purchaseType" to "LANDMARK",
                "landmarkType" to "TRAIN_STATION",
            ),
            message.payload,
        )
    }
}
