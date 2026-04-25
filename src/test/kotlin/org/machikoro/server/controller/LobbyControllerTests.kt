package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.service.LobbyService
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class LobbyControllerTests {

    private val lobbyService = mock<LobbyService>()
    private val controller = LobbyController(lobbyService)

    @Test
    fun `startGame returns ok when service succeeds`() {
        val gameId = 1
        whenever(lobbyService.startGame(gameId)).thenReturn(
            GameModel(
                id = gameId,
                status = GameStatus.IN_PROGRESS,
                hostUserId = 10,
                currentTurnIndex = 0,
                turnPhase = org.machikoro.server.domain.enums.TurnPhase.ROLL_DICE,
                lastDiceRoll = null,
                roundNumber = 1
            )
        )

        val response = controller.startGame(gameId)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `startGame returns bad request on exception`() {
        val gameId = 1
        whenever(lobbyService.startGame(gameId)).thenThrow(RuntimeException("test error"))

        val response = controller.startGame(gameId)
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("test error", response.body)
    }
}
