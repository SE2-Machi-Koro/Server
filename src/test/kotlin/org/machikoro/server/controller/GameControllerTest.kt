package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.LeaveFinishedGameRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.LeaveFinishedGameService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate

class GameControllerTest {

    private val gamePhaseService = mock<GamePhaseService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val leaveFinishedGameService = mock<LeaveFinishedGameService>()
    private val purchaseService = mock<PurchaseService>()
    private val diceService = mock<DiceService>()
    private val controller = GameController(gamePhaseService, messagingTemplate, leaveFinishedGameService, purchaseService, diceService)

    @Test
    fun `endTurn delegates to service with the requested game id`() {
        val gameId = 42
        whenever(gamePhaseService.endTurn(gameId))
            .thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))

        controller.endTurn(EndTurnRequest(gameId))

        verify(gamePhaseService).endTurn(gameId)
    }

    @Test
    fun `endTurn broadcasts resulting phase as GAME_ACTION on game topic`() {
        val gameId = 42
        whenever(gamePhaseService.endTurn(gameId))
            .thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))

        controller.endTurn(EndTurnRequest(gameId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_ACTION, message.type)
        assertEquals("server", message.sender)
        assertEquals(mapOf("turnPhase" to "ROLL_DICE"), message.payload)
    }

    @Test
    fun `endTurn broadcasts GAME_END on game topic when winner exists`() {
        val gameId = 42
        val winner = mock<PlayerModel>()
        whenever(winner.id).thenReturn(1)
        whenever(gamePhaseService.endTurn(gameId))
            .thenReturn(EndTurnOutcome.Won(winner))

        controller.endTurn(EndTurnRequest(gameId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.GAME_END, message.type)
        assertEquals("server", message.sender)
        assertEquals(mapOf("winnerId" to 1), message.payload)
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

        controller.purchase(
            PurchaseRequest(gameId, PurchaseType.ESTABLISHMENT, cardType = CardType.BAKERY)
        )

        verify(purchaseService).purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.BAKERY, null)
    }

    @Test
    fun `purchase broadcasts resulting purchase payload as GAME_ACTION on game topic`() {
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

        controller.purchase(
            PurchaseRequest(gameId, PurchaseType.LANDMARK, landmarkType = LandmarkType.TRAIN_STATION)
        )

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

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

    @Test
    fun `leaveFinishedGame calls service before broadcasting`() {
        val gameId = 1
        val playerId = 10

        controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId))

        val order = inOrder(leaveFinishedGameService, messagingTemplate)
        order.verify(leaveFinishedGameService).leaveFinishedGame(gameId, playerId)
        order.verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), any<WebSocketMessage>())
    }

    @Test
    fun `leaveFinishedGame gets exception from service`() {
        val gameId = 1
        val playerId = 10

        whenever(leaveFinishedGameService.leaveFinishedGame(gameId, playerId))
            .thenThrow(RuntimeException("boom"))

        org.junit.jupiter.api.assertThrows<RuntimeException> {
            controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId))
        }
    }

    @Test
    fun `leaveFinishedGame sends message to correct topic`() {
        val gameId = 5
        val playerId = 20

        controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId))

        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), any<WebSocketMessage>())
    }

    @Test
    fun `leaveFinishedGame payload contains correct playerId and type`() {
        val gameId = 3
        val playerId = 99

        controller.leaveFinishedGame(LeaveFinishedGameRequest(gameId, playerId))

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.PLAYER_LEFT_FINISHED_GAME, message.type)
        assertEquals(mapOf("playerId" to playerId), message.payload)
    }

    @Test
    fun `rollDice broadcasts result to correct game topic`() {
        val gameId = 1
        val playerId = 2
        val request = RollDiceRequest(gameId = gameId, playerId = playerId)
        val response = RollDiceResponse(dice = listOf(3, 4), total = 7)

        whenever(diceService.rollDice(request)).thenReturn(response)

        controller.rollDice(request)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ROLL_DICE, message.type)
        assertEquals("SERVER", message.sender)
        assertEquals("Player $playerId rolled: 7", message.content)
        assertEquals(mapOf("dice" to listOf(3, 4), "total" to 7), message.payload)
    }

    @Test
    fun `rollDice broadcasts error to game topic on failure`() {
        val gameId = 1
        val playerId = 2
        val request = RollDiceRequest(gameId = gameId, playerId = playerId)

        whenever(diceService.rollDice(request)).thenThrow(RuntimeException("dice exploded"))

        controller.rollDice(request)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/$gameId"), captor.capture())

        val message = captor.firstValue
        assertEquals(MessageType.ERROR, message.type)
        assertEquals("SERVER", message.sender)
        assertEquals(
            mapOf("event" to "ROLL_FAILED", "message" to "dice exploded"),
            message.payload
        )
    }
}