package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.DebugEndGameError
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
    fun `seed returns 500 when service throws`() {
        whenever(debugService.seed()).thenThrow(RuntimeException("DB unavailable"))

        val response = controller.seed("Bearer admin-token")

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNull(response.body)
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
    fun `fillLobby returns 500 when service throws GameNotFoundException`() {
        val request = FillLobbyRequest(lobbyCode = "NOPE")
        whenever(debugService.fillLobby("NOPE")).thenThrow(GameNotFoundException("Lobby not found"))

        val response = controller.fillLobby("Bearer admin-token", request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNull(response.body)
    }

    @Test
    fun `fillLobby returns 500 when service throws unexpected exception`() {
        val request = FillLobbyRequest(lobbyCode = "ABC123")
        whenever(debugService.fillLobby("ABC123")).thenThrow(RuntimeException("Unexpected"))

        val response = controller.fillLobby("Bearer admin-token", request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNull(response.body)
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
    fun `purge returns 500 when service throws`() {
        whenever(debugService.purgeGames()).thenThrow(RuntimeException("DB error"))

        val response = controller.purge("Bearer admin-token")

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNull(response.body)
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
    fun `resetLobby returns 500 when service throws`() {
        val request = FillLobbyRequest(lobbyCode = "NOPE")
        whenever(debugService.resetLobby("NOPE")).thenThrow(GameNotFoundException("not found"))

        val response = controller.resetLobby("Bearer admin-token", request)

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertNull(response.body)
    }

    // === Admin auth — 401 / 403 across all endpoints ===

    @Test
    fun `seed returns 401 when session token is invalid`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        assertEquals(HttpStatus.UNAUTHORIZED, controller.seed("Bearer bad").statusCode)
    }

    @Test
    fun `seed returns 403 when user is not admin`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        assertEquals(HttpStatus.FORBIDDEN, controller.seed("Bearer token").statusCode)
    }

    @Test
    fun `purge returns 401 when session token is invalid`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        assertEquals(HttpStatus.UNAUTHORIZED, controller.purge("Bearer bad").statusCode)
    }

    @Test
    fun `purge returns 403 when user is not admin`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        assertEquals(HttpStatus.FORBIDDEN, controller.purge("Bearer token").statusCode)
    }

    @Test
    fun `fillLobby returns 401 when session token is invalid`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        assertEquals(HttpStatus.UNAUTHORIZED, controller.fillLobby("Bearer bad", FillLobbyRequest("X")).statusCode)
    }

    @Test
    fun `fillLobby returns 403 when user is not admin`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        assertEquals(HttpStatus.FORBIDDEN, controller.fillLobby("Bearer token", FillLobbyRequest("X")).statusCode)
    }

    @Test
    fun `resetLobby returns 401 when session token is invalid`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        assertEquals(HttpStatus.UNAUTHORIZED, controller.resetLobby("Bearer bad", FillLobbyRequest("X")).statusCode)
    }

    @Test
    fun `resetLobby returns 403 when user is not admin`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        assertEquals(HttpStatus.FORBIDDEN, controller.resetLobby("Bearer token", FillLobbyRequest("X")).statusCode)
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
    fun `endGame returns 401 when session token is invalid`() {
        whenever(debugService.validateAdmin(any())).thenThrow(InvalidSessionTokenException("bad token"))

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            controller.endGame("Bearer bad", EndGameRequest(gameId = 7)).statusCode,
        )
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
        verify(gamePhaseService, never()).cleanupFinishedGameData(any())
    }

    @Test
    fun `endGame returns 401 when Authorization header is missing`() {
        whenever(debugService.validateAdmin(isNull())).thenThrow(InvalidSessionTokenException("Missing Authorization header"))

        assertEquals(
            HttpStatus.UNAUTHORIZED,
            controller.endGame(null, EndGameRequest(gameId = 7)).statusCode,
        )
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame returns 403 when user is not admin`() {
        whenever(debugService.validateAdmin(any())).thenThrow(NotAdminException("not admin"))

        assertEquals(
            HttpStatus.FORBIDDEN,
            controller.endGame("Bearer token", EndGameRequest(gameId = 7)).statusCode,
        )
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame returns 404 when game does not exist`() {
        whenever(debugService.validateAdmin(any())).thenReturn(adminUser())
        whenever(debugService.endGame(eq(404), any()))
            .thenThrow(GameNotFoundException("Game 404 not found"))

        assertEquals(
            HttpStatus.NOT_FOUND,
            controller.endGame("Bearer admin-token", EndGameRequest(gameId = 404)).statusCode,
        )
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame returns 422 with structured error when caller is not in the game`() {
        whenever(debugService.validateAdmin(any())).thenReturn(adminUser())
        whenever(debugService.endGame(eq(7), any()))
            .thenThrow(NotInGameException("not a player"))

        val response = controller.endGame("Bearer admin-token", EndGameRequest(gameId = 7))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals(DebugEndGameError("NOT_IN_GAME", "not a player"), response.body)
        verify(gameEndBroadcaster, never()).broadcast(any(), any(), any())
    }

    @Test
    fun `endGame returns 422 with structured error when game state precondition fails`() {
        whenever(debugService.validateAdmin(any())).thenReturn(adminUser())
        whenever(debugService.endGame(eq(7), any()))
            .thenThrow(CustomWebSocketException(errorCode = "GAME_FINISHED", message = "Game 7 has already ended"))

        val response = controller.endGame("Bearer admin-token", EndGameRequest(gameId = 7))

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals(DebugEndGameError("GAME_FINISHED", "Game 7 has already ended"), response.body)
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