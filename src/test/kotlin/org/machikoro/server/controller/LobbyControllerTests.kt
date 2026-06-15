package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.service.LobbyService
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class LobbyControllerTests {

    private val lobbyService = mock<LobbyService>()
    private val controller = LobbyController(lobbyService)

    // Host user ID used across tests
    private val hostUserId = 10
    private val hostPrincipal = UserPrincipal(userId = hostUserId, username = "host")

    private fun gameStateDto(gameId: Int) = GameStateDto(
        game = GameModel(
            id = gameId,
            status = GameStatus.IN_PROGRESS,
            hostUserId = hostUserId,
            lobbyCode = "ABC123",
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = TurnPhase.ROLL_DICE,
            lastDiceRoll = null,
            hasPurchasedThisTurn = false,
            roundNumber = 1,
            rerolledThisTurn = false
        ),
        players = emptyList(),
        playerCards = emptyMap(),
        playerLandmarks = emptyMap(),
        marketplace = emptyMap(),
        turnOrder = emptyList(),
    )

    @Test
    fun `startGame returns ok when service succeeds`() {
        val gameId = 1
        whenever(lobbyService.startGame(eq(gameId), eq(hostUserId))).thenReturn(gameStateDto(gameId))

        val response = controller.startGame(gameId, hostPrincipal)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `startGame propagates exception for centralized handling`() {
        val gameId = 1
        whenever(lobbyService.startGame(eq(gameId), any())).thenThrow(RuntimeException("test error"))

        val exception = assertThrows<RuntimeException> {
            controller.startGame(gameId, hostPrincipal)
        }
        assertEquals("test error", exception.message)
    }

    @Test
    fun `non-host cannot start game even if they know the host user ID`() {
        val gameId = 1
        // Non-host principal — identity comes from token, not a forgeable param
        val nonHostPrincipal = UserPrincipal(userId = 99, username = "attacker")
        whenever(lobbyService.startGame(eq(gameId), eq(99)))
            .thenThrow(NotHostException("User 99 is not the host of game $gameId"))

        assertThrows<NotHostException> {
            controller.startGame(gameId, nonHostPrincipal)
        }
    }
}
