package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.dto.DebugSeedResponse
import org.machikoro.server.dto.FillLobbyRequest
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.LoginResponse
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.InvalidSessionTokenException
import org.machikoro.server.exception.NotAdminException
import org.machikoro.server.service.DebugService
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus

class DebugControllerTest {

    private val debugService = mock<DebugService>()
    private val controller = DebugController(debugService)

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
}