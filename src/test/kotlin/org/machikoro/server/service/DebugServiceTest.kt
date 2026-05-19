package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.LobbyRosterPlayerDto
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.crypto.password.PasswordEncoder

class DebugServiceTest {

    private val userDao = mock<UserDao>()
    private val lobbyService = mock<LobbyService>()
    private val passwordEncoder = mock<PasswordEncoder>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()

    private val service = DebugService(userDao, lobbyService, passwordEncoder, messagingTemplate)

    // --- helpers ---

    private fun game(id: Int) = GameModel(
        id = id,
        status = GameStatus.WAITING,
        hostUserId = 1,
        lobbyCode = "DEBUG1",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        hasPurchasedThisTurn = false,
        roundNumber = 1,
    )

    private fun player(id: Int, gameId: Int = 10, userId: Int = id) =
        PlayerModel(id = id, gameId = gameId, userId = userId, turnOrder = 0, coins = 3, lastSeenAt = null)

    private fun userModel(id: Int, username: String) = UserModel(
        id = id,
        username = username,
        passwordHash = "existing_hash",
        sessionToken = null,
        totalWins = 0,
        totalGamesPlayed = 0,
    )

    private fun gameStateDto() = GameStateDto(
        game = game(10).copy(status = GameStatus.IN_PROGRESS),
        players = listOf(player(1), player(2), player(3), player(4)),
        playerCards = emptyMap(),
        playerLandmarks = emptyMap(),
        marketplace = emptyMap(),
        turnOrder = listOf(1, 2, 3, 4),
        activePlayerId = 1,
    )

    // Stubs userDao so ensureUser will create a new user
    private fun stubNewUser(username: String, userId: Int) {
        whenever(userDao.findByUsername(username)).thenReturn(null)
        whenever(userDao.create(eq(username), any())).thenReturn(userId)
    }

    // Stubs userDao so ensureUser will reuse an existing user
    private fun stubExistingUser(username: String, userId: Int) {
        whenever(userDao.findByUsername(username)).thenReturn(userModel(userId, username))
    }

    // === seed() ===

    @Test
    fun `seed creates 4 dummy users, creates lobby, joins all, and starts game`() {
        stubNewUser("debug_player1", 1)
        stubNewUser("debug_player2", 2)
        stubNewUser("debug_player3", 3)
        stubNewUser("debug_player4", 4)
        whenever(passwordEncoder.encode(any())).thenReturn("hash")
        val game = game(10)
        whenever(lobbyService.createLobby(1)).thenReturn(game)
        whenever(lobbyService.addUserToLobby(eq(10), any())).thenReturn(player(1, gameId = 10))
        val expectedState = gameStateDto()
        whenever(lobbyService.startGame(10)).thenReturn(expectedState)

        val result = service.seed()

        assertEquals(4, result.players.size)
        assertEquals("debug_player1", result.players[0].username)
        assertEquals("debug_player4", result.players[3].username)
        assertEquals(expectedState, result.gameState)
        verify(userDao, times(4)).create(any(), any())
        verify(lobbyService).createLobby(1)
        verify(lobbyService, times(4)).addUserToLobby(eq(10), any())
        verify(lobbyService).startGame(eq(10), isNull())
    }

    @Test
    fun `seed reuses existing users without calling create`() {
        stubExistingUser("debug_player1", 1)
        stubExistingUser("debug_player2", 2)
        stubExistingUser("debug_player3", 3)
        stubExistingUser("debug_player4", 4)
        whenever(lobbyService.createLobby(1)).thenReturn(game(10))
        whenever(lobbyService.addUserToLobby(any(), any())).thenReturn(player(1))
        whenever(lobbyService.startGame(any(), anyOrNull())).thenReturn(gameStateDto())

        service.seed()

        verify(userDao, never()).create(any(), any())
        verify(passwordEncoder, never()).encode(any())
    }

    @Test
    fun `seed issues distinct session tokens for all 4 players`() {
        stubNewUser("debug_player1", 1)
        stubNewUser("debug_player2", 2)
        stubNewUser("debug_player3", 3)
        stubNewUser("debug_player4", 4)
        whenever(passwordEncoder.encode(any())).thenReturn("hash")
        whenever(lobbyService.createLobby(any())).thenReturn(game(10))
        whenever(lobbyService.addUserToLobby(any(), any())).thenReturn(player(1))
        whenever(lobbyService.startGame(any(), anyOrNull())).thenReturn(gameStateDto())

        val result = service.seed()

        // UUID.randomUUID() per player — all tokens must be unique
        val tokens = result.players.map { it.sessionToken }
        assertEquals(4, tokens.toSet().size)
        verify(userDao, times(4)).updateSessionToken(any(), any())
    }

    // === fillLobby() ===

    @Test
    fun `fillLobby adds all 3 dummies when lobby has space and broadcasts LOBBY_JOINED for each`() {
        whenever(lobbyService.validateLobbyCode("ABC123")).thenReturn(game(5))
        stubNewUser("debug_player2", 2)
        stubNewUser("debug_player3", 3)
        stubNewUser("debug_player4", 4)
        whenever(passwordEncoder.encode(any())).thenReturn("hash")
        whenever(lobbyService.addUserToLobby(eq(5), any())).thenReturn(player(1, gameId = 5))

        val result = service.fillLobby("ABC123")

        assertEquals(3, result.size)
        assertEquals("debug_player2", result[0].username)
        assertEquals("debug_player4", result[2].username)
        verify(lobbyService, times(3)).addUserToLobby(eq(5), any())
        verify(messagingTemplate, times(3)).convertAndSend(eq("/topic/public"), any<Any>())
    }

    @Test
    fun `fillLobby stops at first LobbyFullException and returns only already-added players`() {
        whenever(lobbyService.validateLobbyCode("ABC123")).thenReturn(game(5))
        stubNewUser("debug_player2", 2)
        stubNewUser("debug_player3", 3)
        stubNewUser("debug_player4", 4)
        whenever(passwordEncoder.encode(any())).thenReturn("hash")
        // First dummy joins successfully, second fills the lobby
        whenever(lobbyService.addUserToLobby(eq(5), eq(2))).thenReturn(player(2, gameId = 5, userId = 2))
        whenever(lobbyService.addUserToLobby(eq(5), eq(3))).thenThrow(LobbyFullException("Lobby full"))

        val result = service.fillLobby("ABC123")

        assertEquals(1, result.size)
        assertEquals("debug_player2", result[0].username)
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/public"), any<Any>())
    }

    @Test
    fun `fillLobby returns empty list when lobby is already full on first attempt`() {
        whenever(lobbyService.validateLobbyCode("ABC123")).thenReturn(game(5))
        stubNewUser("debug_player2", 2)
        stubNewUser("debug_player3", 3)
        stubNewUser("debug_player4", 4)
        whenever(passwordEncoder.encode(any())).thenReturn("hash")
        whenever(lobbyService.addUserToLobby(eq(5), any())).thenThrow(LobbyFullException("Lobby full"))

        val result = service.fillLobby("ABC123")

        assertEquals(0, result.size)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }

    @Test
    fun `fillLobby skips a dummy on non-full exception and continues adding the rest`() {
        whenever(lobbyService.validateLobbyCode("ABC123")).thenReturn(game(5))
        stubNewUser("debug_player2", 2)
        stubNewUser("debug_player3", 3)
        stubNewUser("debug_player4", 4)
        whenever(passwordEncoder.encode(any())).thenReturn("hash")
        // First dummy fails with an unrelated error, the other two succeed
        whenever(lobbyService.addUserToLobby(eq(5), eq(2))).thenThrow(GameStartedException("Race condition"))
        whenever(lobbyService.addUserToLobby(eq(5), eq(3))).thenReturn(player(3, gameId = 5, userId = 3))
        whenever(lobbyService.addUserToLobby(eq(5), eq(4))).thenReturn(player(4, gameId = 5, userId = 4))

        val result = service.fillLobby("ABC123")

        assertEquals(2, result.size)
        assertEquals("debug_player3", result[0].username)
        assertEquals("debug_player4", result[1].username)
        // Only 2 broadcasts — skipped dummy must not get one
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/public"), any<Any>())
    }

    @Test
    fun `fillLobby propagates GameNotFoundException when lobby code is invalid`() {
        whenever(lobbyService.validateLobbyCode("NOPE")).thenThrow(GameNotFoundException("Not found"))

        assertThrows<GameNotFoundException> {
            service.fillLobby("NOPE")
        }

        verify(lobbyService, never()).addUserToLobby(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }

    // === resetLobby() ===

    @Test
    fun `resetLobby broadcasts LOBBY_LEFT for each removed dummy and returns count`() {
        val removed = listOf(
            LobbyRosterPlayerDto(playerId = 2, userId = 2, username = "debug_player2", gameId = 5, turnOrder = 1, coins = 3),
            LobbyRosterPlayerDto(playerId = 3, userId = 3, username = "debug_player3", gameId = 5, turnOrder = 2, coins = 3),
        )
        whenever(lobbyService.resetLobby("ABC123")).thenReturn(removed)

        val count = service.resetLobby("ABC123")

        assertEquals(2, count)
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/public"), any<Any>())
    }

    @Test
    fun `resetLobby sends no broadcasts and returns 0 when no dummies were removed`() {
        whenever(lobbyService.resetLobby("ABC123")).thenReturn(emptyList())

        val count = service.resetLobby("ABC123")

        assertEquals(0, count)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }

    @Test
    fun `resetLobby propagates GameNotFoundException when lobby code not found`() {
        whenever(lobbyService.resetLobby("NOPE")).thenThrow(GameNotFoundException("not found"))

        assertThrows<GameNotFoundException> {
            service.resetLobby("NOPE")
        }

        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<Any>())
    }

    // === purgeGames() ===

    @Test
    fun `purgeGames delegates to lobbyService and deletes debug users by prefix`() {
        whenever(lobbyService.purgeAllGames()).thenReturn(3)

        val count = service.purgeGames()

        assertEquals(3, count)
        verify(lobbyService).purgeAllGames()
        verify(userDao).deleteByUsernamePrefix(LobbyService.DEBUG_USER_PREFIX)
    }
}