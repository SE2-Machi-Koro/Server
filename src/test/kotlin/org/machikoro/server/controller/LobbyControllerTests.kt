package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.service.LobbyService
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class LobbyControllerTests {

    private val lobbyService = mock<LobbyService>()
    private val controller = LobbyController(lobbyService)

    private fun gameStateDto(gameId: Int) = GameStateDto(
        game = GameModel(
            id = gameId,
            status = GameStatus.IN_PROGRESS,
            hostUserId = 10,
            lobbyCode = "ABC123",
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = TurnPhase.ROLL_DICE,
            lastDiceRoll = null,
            hasPurchasedThisTurn = false,
            roundNumber = 1,
        ),
        players = emptyList(),
        playerCards = emptyMap(),
        playerLandmarks = emptyMap(),
        turnOrder = emptyList(),
    )

    @Test
    fun `startGame returns ok when service succeeds`() {
        val gameId = 1
        val requestingUserId = 10
        whenever(lobbyService.startGame(eq(gameId), eq(requestingUserId))).thenReturn(gameStateDto(gameId))

        val response = controller.startGame(gameId, requestingUserId)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `startGame returns bad request on exception`() {
        val gameId = 1
        val requestingUserId = 10
        whenever(lobbyService.startGame(eq(gameId), any())).thenThrow(RuntimeException("test error"))

        val response = controller.startGame(gameId, requestingUserId)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("test error", response.body)
    }
}
