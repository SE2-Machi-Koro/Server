package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.DebugSeedResponse
import org.machikoro.server.dto.FillLobbyRequest
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.LoginResponse
import org.machikoro.server.dto.EndGameRequest
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.InvalidSessionTokenException
import org.machikoro.server.exception.NotAdminException
import org.machikoro.server.exception.NotInGameException
import org.machikoro.server.service.DebugService
import org.machikoro.server.service.GameEndBroadcaster
import org.machikoro.server.service.GamePhaseService
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class DebugControllerTest {

    private val debugService = mock<DebugService>()
    private val gameEndBroadcaster = mock<GameEndBroadcaster>()
    private val gamePhaseService = mock<GamePhaseService>()
    private val controller = DebugController(debugService, gameEndBroadcaster, gamePhaseService)

    // --- helpers ---

    private fun game() = GameModel(
        id = 1,
        status = GameStatus.IN_PROGRESS,
        hostUserId = 1,
        lobbyCode = "DEBUG1",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        hasPurchasedThisTurn = false,
        roundNumber = 1,
    )

    private fun gameStateDto() = GameStateDto(
        game = game(),
        players = emptyList(),
        playerCards = emptyMap(),
        playerLandmarks = emptyMap(),
        marketplace = emptyMap(),
        turnOrder = emptyList(),
        activePlayerId = null,
    )

    private fun loginResponse(username: String, userId: Int) =
        LoginResponse(sessionToken = "token-$userId", username = username, userId = userId)

    private fun adminUser() = UserModel(
        id = 99,
        username = "admin_1",
        passwordHash = "hash",
        sessionToken = "admin-token",
        totalWins = 0,
        totalGamesPlayed = 0,
        isAdmin = true,
    )

    // === POST /debug/seed ===

    @Test
    fun `seed returns 200 OK with DebugSeedResponse when service succeeds`() {
        val players = listOf(
            loginResponse("debug_player1", 1),
            loginResponse("debug_player2", 2),
            loginResponse("debug_player3", 3),
            loginResponse("debug_player4", 4),
        )
        val seedResponse = DebugSeedResponse(gameState = gameStateDto(), players = players)
        whenever(debugService.seed()).thenReturn(seedResponse)

        val response = controller.seed("Bearer admin-token")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(seedResponse, response.body)
        assertEquals(4, (response.body as DebugSeedResponse).players.size)
    }

    @Test
    fun `seed propagates unexpected service exception for centralized handling`() {
        whenever(debugService.seed()).thenThrow(RuntimeException("DB unavailable"))

        val exception = assertThrows<RuntimeException> {
            controller.seed("Bearer admin-token")
        }
        assertEquals("DB unavailable", exception.message)
    }

    // === POST /debug/fill-lobby ===

    @Test
    fun `fillLobby returns 200 OK with list of added dummy players when service succeeds`() {
        val request = FillLobbyRequest(lobbyCode = "ABC123")
        val added = listOf(loginResponse("debug_player2", 2), loginResponse("debug_player3", 3))
        whenever(debugService.fillLobby("ABC123")).thenReturn(added)

        val response = controller.fillLobby("Bearer admin-token", request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(added, response.body)
        assertEquals(2, response.body?.size)
    }

    @Test
    fun `fillLobby returns 200 OK with empty list when lobby is already full`() {
        // Service returns empty list when all slots are taken — not an error condition
        val request = FillLobbyRequest(lobbyCode = "FULL99")
        whenever(debugService.fillLobby("FULL99")).thenReturn(emptyList())

        val response = controller.fillLobby("Bearer admin-token", request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(emptyList<LoginResponse>(), response.body)
    }

    @Test
    fun `fillLobby propagates GameNotFoundException for centralized handling`() {
        val request = FillLobbyRequest(lobbyCode = "NOPE")
        whenever(debugService.fillLobby("NOPE")).thenThrow(GameNotFoundException("Lobby not found"))

        val exception = assertThrows<GameNotFoundException> {
            controller.fillLobby("Bearer admin-token", request)
        }
        assertEquals("Lobby not found", exception.message)
    }

    @Test
    fun `fillLobby propagates unexpected service exception for centralized handling`() {
        val request = FillLobbyRequest(lobbyCode = "ABC123")
        whenever(debugService.fillLobby("ABC123")).thenThrow(RuntimeException("Unexpected"))

        val exception = assertThrows<RuntimeException> {
            controller.fillLobby("Bearer admin-token", request)
        }
        assertEquals("Unexpected", exception.message)
    }

    // === DELETE /debug/purge ===

    @Test
    fun `purge returns 200 OK with deleted game count`() {
        whenever(debugService.purgeGames()).thenReturn(5)

        val response = controller.purge("Bearer admin-token")

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(mapOf("deletedGames" to 5), response.body)
    }

    @Test
    fun `purge propagates unexpected service exception for centralized handling`() {
        whenever(debugService.purgeGames()).thenThrow(RuntimeException("DB error"))

        val exception = assertThrows<RuntimeException> {
            controller.purge("Bearer admin-token")
        }
        assertEquals("DB error", exception.message)
    }

    // === POST /debug/reset-lobby ===

    @Test
    fun `resetLobby returns 200 OK with removed player count`() {
        val request = FillLobbyRequest(lobbyCode = "ABC123")
        whenever(debugService.resetLobby("ABC123")).thenReturn(2)

        val response = controller.resetLobby("Bearer admin-token", request)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(mapOf("removedPlayers" to 2), response.body)
    }

    @Test
    fun `resetLobby propagates GameNotFoundException for centralized handling`() {
        val request = FillLobbyRequest(lobbyCode = "NOPE")
        whenever(debugService.resetLobby("NOPE")).thenThrow(GameNotFoundException("not found"))

        val exception = assertThrows<GameNotFoundException> {
            controller.resetLobby("Bearer admin-token", request)
        }

        assertEquals("not found", exception.message)
    }

    // === Admin auth — centralized 401 / 403 handling across all endpoints ===

    @Test
    fun `seed propagates invalid session token for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        val exception = assertThrows<InvalidSessionTokenException> {
            controller.seed("Bearer bad")
        }
        assertEquals("bad token", exception.message)
    }

    @Test
    fun `seed propagates not admin for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        val exception = assertThrows<NotAdminException> {
            controller.seed("Bearer token")
        }
        assertEquals("not admin", exception.message)
    }

    @Test
    fun `purge propagates invalid session token for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        val exception = assertThrows<InvalidSessionTokenException> {
            controller.purge("Bearer bad")
        }
        assertEquals("bad token", exception.message)
    }

    @Test
    fun `purge propagates not admin for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        val exception = assertThrows<NotAdminException> {
            controller.purge("Bearer token")
        }
        assertEquals("not admin", exception.message)
    }

    @Test
    fun `fillLobby propagates invalid session token for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        val exception = assertThrows<InvalidSessionTokenException> {
            controller.fillLobby("Bearer bad", FillLobbyRequest("X"))
        }
        assertEquals("bad token", exception.message)
    }

    @Test
    fun `fillLobby propagates not admin for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        val exception = assertThrows<NotAdminException> {
            controller.fillLobby("Bearer token", FillLobbyRequest("X"))
        }
        assertEquals("not admin", exception.message)
    }

    @Test
    fun `resetLobby propagates invalid session token for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        val exception = assertThrows<InvalidSessionTokenException> {
            controller.resetLobby("Bearer bad", FillLobbyRequest("X"))
        }
        assertEquals("bad token", exception.message)
    }

    @Test
    fun `resetLobby propagates not admin for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        val exception = assertThrows<NotAdminException> {
            controller.resetLobby("Bearer token", FillLobbyRequest("X"))
        }
        assertEquals("not admin", exception.message)
    }

    // === endGame ===

    @Test
    fun `endGame returns 200 OK with Won outcome, broadcasts then cleans up on success`() {
        val admin = adminUser()
        val outcome = EndTurnOutcome.Won(winnerId = 42, roundsPlayed = 5)
        whenever(debugService.validateAdmin(any())).thenReturn(admin)
        // eq(admin) proves the controller threads validateAdmin's result into endGame (no re-resolve).
        whenever(debugService.endGame(eq(7), eq(admin))).thenReturn(outcome)

        val response = controller.endGame("Bearer admin-token", EndGameRequest(gameId = 7))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(outcome, response.body)
        verify(gameEndBroadcaster).broadcast(7, 42, 5)
        verify(gamePhaseService).cleanupFinishedGameData(7)
    }

    @Test
    fun `endGame propagates invalid session token for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        val exception = assertThrows<InvalidSessionTokenException> {
            controller.endGame("Bearer bad", EndGameRequest(gameId = 7))
        }
        assertEquals("bad token", exception.message)
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
        verify(gamePhaseService, never()).cleanupFinishedGameData(any())
    }

    @Test
    fun `endGame propagates missing Authorization header for centralized handling`() {
        whenever(debugService.validateAdmin(isNull())).thenThrow(InvalidSessionTokenException("Missing Authorization header"))

        val exception = assertThrows<InvalidSessionTokenException> {
            controller.endGame(null, EndGameRequest(gameId = 7))
        }
        assertEquals("Missing Authorization header", exception.message)
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame propagates not admin for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        val exception = assertThrows<NotAdminException> {
            controller.endGame("Bearer token", EndGameRequest(gameId = 7))
        }
        assertEquals("not admin", exception.message)
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame propagates GameNotFoundException for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenReturn(adminUser())
        whenever(debugService.endGame(eq(404), any()))
            .thenThrow(GameNotFoundException("Game 404 not found"))

        val exception = assertThrows<GameNotFoundException> {
            controller.endGame("Bearer admin-token", EndGameRequest(gameId = 404))
        }
        assertEquals("Game 404 not found", exception.message)
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame propagates NotInGameException for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenReturn(adminUser())
        whenever(debugService.endGame(eq(7), any()))
            .thenThrow(NotInGameException("not a player"))

        val exception = assertThrows<NotInGameException> {
            controller.endGame("Bearer admin-token", EndGameRequest(gameId = 7))
        }
        assertEquals("not a player", exception.message)
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame propagates game state precondition failure for centralized handling`() {
        whenever(debugService.validateAdmin(any())).thenReturn(adminUser())
        whenever(debugService.endGame(eq(7), any()))
            .thenThrow(CustomWebSocketException(errorCode = "GAME_FINISHED", message = "Game 7 has already ended"))

        val exception = assertThrows<CustomWebSocketException> {
            controller.endGame("Bearer admin-token", EndGameRequest(gameId = 7))
        }
        assertEquals("GAME_FINISHED", exception.errorCode)
        assertEquals("Game 7 has already ended", exception.message)
    }

    @Test
    fun `endGame propagates unexpected exceptions instead of a broad catch`() {
        // #305 DoD: no broad catch(Exception) — unexpected errors bubble to Spring's default 500 handler.
        whenever(debugService.validateAdmin(any())).thenReturn(adminUser())
        whenever(debugService.endGame(eq(7), any()))
            .thenThrow(RuntimeException("DB unavailable"))

        assertThrows<RuntimeException> {
            controller.endGame("Bearer admin-token", EndGameRequest(gameId = 7))
        }
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }
}
