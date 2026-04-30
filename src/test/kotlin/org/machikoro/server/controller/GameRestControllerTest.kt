package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.LeaveFinishedGameRequest
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.LeaveFinishedGameService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.WinConditionService
import org.machikoro.server.service.interfaces.EarningsService
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class GameRestControllerTest {

    private val purchaseService = mock<PurchaseService>()
    private val gamePhaseService = mock<GamePhaseService>()
    private val winConditionService = mock<WinConditionService>()
    private val leaveFinishedGameService = mock<LeaveFinishedGameService>()
    private val diceService = mock<DiceService>()
    private val earningsService = mock<EarningsService>()

    private val controller = GameRestController(
        purchaseService,
        gamePhaseService,
        winConditionService,
        leaveFinishedGameService,
        diceService,
        earningsService,
    )

    @Test
    fun `purchase returns ok and delegates to service`() {
        val request = PurchaseRequest(
            gameId = 1,
            purchaseType = PurchaseType.ESTABLISHMENT,
            cardType = CardType.BAKERY,
        )
        val result = PurchaseResult(
            turnPhase = TurnPhase.BUY_OR_BUILD,
            purchaseType = PurchaseType.ESTABLISHMENT,
            cardType = CardType.BAKERY,
        )
        whenever(
            purchaseService.purchase(
                gameId = 1,
                purchaseType = PurchaseType.ESTABLISHMENT,
                cardType = CardType.BAKERY,
                landmarkType = null,
            )
        ).thenReturn(result)

        val response = controller.purchase(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(result, response.body)
    }

    @Test
    fun `purchase returns bad request when service throws`() {
        val request = PurchaseRequest(
            gameId = 1,
            purchaseType = PurchaseType.ESTABLISHMENT,
            cardType = CardType.BAKERY,
        )
        whenever(
            purchaseService.purchase(
                gameId = 1,
                purchaseType = PurchaseType.ESTABLISHMENT,
                cardType = CardType.BAKERY,
                landmarkType = null,
            )
        ).thenThrow(RuntimeException("boom"))

        val response = controller.purchase(request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("boom", response.body)
    }

    @Test
    fun `endTurn without winner returns next phase and does not finish the game`() {
        val gameId = 7
        whenever(winConditionService.detectWinner(gameId)).thenReturn(null)
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(TurnPhase.ROLL_DICE)

        val response = controller.endTurn(EndTurnRequest(gameId))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(EndTurnResponse(turnPhase = TurnPhase.ROLL_DICE, winnerId = null), response.body)
        verify(gamePhaseService, never()).finishGame(gameId)
    }

    @Test
    fun `endTurn with winner finishes the game and returns the winner id`() {
        val gameId = 7
        val winner = mock<PlayerModel>()
        whenever(winner.id).thenReturn(99)
        whenever(winConditionService.detectWinner(gameId)).thenReturn(winner)

        val response = controller.endTurn(EndTurnRequest(gameId))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(EndTurnResponse(turnPhase = null, winnerId = 99), response.body)
        verify(gamePhaseService).finishGame(gameId)
        verify(gamePhaseService, never()).endTurn(gameId)
    }

    @Test
    fun `endTurn returns bad request when service throws`() {
        val gameId = 7
        whenever(winConditionService.detectWinner(gameId)).thenReturn(null)
        whenever(gamePhaseService.endTurn(gameId)).thenThrow(IllegalStateException("not in BUY_OR_BUILD"))

        val response = controller.endTurn(EndTurnRequest(gameId))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("not in BUY_OR_BUILD", response.body)
    }

    @Test
    fun `leave returns ok and delegates to service`() {
        val response = controller.leave(LeaveFinishedGameRequest(gameId = 3, playerId = 42))

        assertEquals(HttpStatus.OK, response.statusCode)
        verify(leaveFinishedGameService).leaveFinishedGame(3, 42)
    }

    @Test
    fun `leave returns bad request when service throws`() {
        whenever(leaveFinishedGameService.leaveFinishedGame(3, 42))
            .thenThrow(IllegalArgumentException("Player not in game"))

        val response = controller.leave(LeaveFinishedGameRequest(gameId = 3, playerId = 42))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("Player not in game", response.body)
    }

    @Test
    fun `rollDice returns ok with dice response`() {
        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val expected = RollDiceResponse(dice = listOf(3, 4), total = 7)
        whenever(diceService.rollDice(request)).thenReturn(expected)

        val response = controller.rollDice(request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(expected, response.body)
    }

    @Test
    fun `rollDice returns bad request when service throws`() {
        val request = RollDiceRequest(gameId = 1, playerId = 2)
        whenever(diceService.rollDice(request)).thenThrow(RuntimeException("dice exploded"))

        val response = controller.rollDice(request)

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("dice exploded", response.body)
    }

    @Test
    fun `resolveEffects returns ok and delegates to service`() {
        val response = controller.resolveEffects(ResolveEffectsRequest(gameId = 5))

        assertEquals(HttpStatus.OK, response.statusCode)
        verify(earningsService).resolveEffects(5)
    }

    @Test
    fun `resolveEffects returns bad request when service throws`() {
        whenever(earningsService.resolveEffects(5))
            .thenThrow(RuntimeException("effects failed"))

        val response = controller.resolveEffects(ResolveEffectsRequest(gameId = 5))

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("effects failed", response.body)
    }
}
