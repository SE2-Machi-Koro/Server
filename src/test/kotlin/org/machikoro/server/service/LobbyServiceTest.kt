package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Fast behavior-level checks for lobby service branching.
 *
 * startGame has DB-backed coverage in LobbyServiceIntegrationTest for the
 * transactional setup path.
 */
class LobbyServiceTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val gameMarketplaceDao = mock<GameMarketplaceDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()

    // Anonymous subclass that bypasses the real Exposed transaction so this
    // unit test doesn't need a database. Methods that don't call
    // runInTransaction (e.g. addUserToLobby) are unaffected.
    private val lobbyService = object : LobbyService(gameDao, playerDao, gameMarketplaceDao, playerLandmarkDao) {
        override fun <T> runInTransaction(block: () -> T): T = block()
    }

    private fun game(id: Int, status: GameStatus) =
        GameModel(
            id = id,
            status = status,
            hostUserId = 1,
            lobbyCode = "ABC123",
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = org.machikoro.server.domain.enums.TurnPhase.ROLL_DICE,
            lastDiceRoll = null,
            hasPurchasedThisTurn = false,
            roundNumber = 1
        )

    private fun player(id: Int) =
        PlayerModel(id = id, gameId = 1, userId = id, turnOrder = 0, coins = 3, lastSeenAt = null)

    // Ensures playerDao.findByGameIdAndUserId returns null by default (new player, not a reconnect).
    // Called before each addUserToLobby test that should not hit the reconnect fast-path.
    private fun noExistingPlayer(gameId: Int, userId: Int) {
        whenever(playerDao.findByGameIdAndUserId(gameId, userId)).thenReturn(null)
    }

    @Test
    fun `addUserToLobby adds player successfully`() {
        val gameId = 1
        val userId = 10
        noExistingPlayer(gameId, userId)
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())
        whenever(playerDao.addPlayer(gameId, userId)).thenReturn(player(1))

        val result = lobbyService.addUserToLobby(gameId, userId)

        assertNotNull(result)
        verify(playerDao).addPlayer(gameId, userId)
    }

    @Test
    fun `addUserToLobby returns existing player immediately on reconnect`() {
        val gameId = 1
        val userId = 10
        val existing = player(userId)
        // Both the pre-lock and in-lock checks should short-circuit here.
        whenever(playerDao.findByGameIdAndUserId(gameId, userId)).thenReturn(existing)

        val result = lobbyService.addUserToLobby(gameId, userId)

        kotlin.test.assertEquals(existing, result)
        // No DB write should occur and no game fetch is needed.
        verify(playerDao, never()).addPlayer(any(), any())
        verify(gameDao, never()).findById(any())
    }

    @Test
    fun `addUserToLobby throws GameNotFoundException`() {
        val gameId = 1
        val userId = 10
        noExistingPlayer(gameId, userId)
        // gameDao.findById is now called inside the lock, so null here still triggers the exception.
        whenever(gameDao.findById(gameId)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.addUserToLobby(gameId, userId)
        }
    }

    @Test
    fun `addUserToLobby throws GameStartedException`() {
        val gameId = 2
        val userId = 10
        noExistingPlayer(gameId, userId)
        // Status check now happens inside the lock against a fresh DB read.
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))

        assertThrows<GameStartedException> {
            lobbyService.addUserToLobby(gameId, userId)
        }
    }

    @Test
    fun `addUserToLobby throws LobbyFullException`() {
        val gameId = 3
        val userId = 10
        noExistingPlayer(gameId, userId)
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(1), player(2), player(3), player(4))
        )

        assertThrows<LobbyFullException> {
            lobbyService.addUserToLobby(gameId, userId)
        }
    }

    @Test
    fun `createLobby creates a game and returns the persisted GameModel`() {
        val hostUserId = 7
        val gameId = 42
        val expected = game(gameId, GameStatus.WAITING)
        // gameDao.create has default args (lobbyCode, maxPlayers) that the
        // production caller leaves to defaults. Mockito sees the full
        // bytecode-level call so we match any() for the optional params.
        whenever(gameDao.create(eq(hostUserId), any(), any())).thenReturn(gameId)
        whenever(gameDao.findById(gameId)).thenReturn(expected)

        val result = lobbyService.createLobby(hostUserId)

        kotlin.test.assertEquals(expected, result)
        verify(gameDao).create(eq(hostUserId), any(), any())
        verify(gameDao).findById(gameId)
    }

    @Test
    fun `createLobby throws GameNotFoundException when the re-fetch returns null`() {
        // Defensive — gameDao.create returned an id but findById missed it. Should
        // never happen with a well-behaved DAO + transaction, but the assertion
        // documents the intent and is cheap to test.
        whenever(gameDao.create(any(), any(), any())).thenReturn(99)
        whenever(gameDao.findById(99)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.createLobby(7)
        }
    }

    @Test
    fun `validateLobbyCode returns game when lobby code exists`() {
        val expected = game(1, GameStatus.WAITING)

        whenever(gameDao.findByLobbyCode("ABC123")).thenReturn(expected)

        val result = lobbyService.validateLobbyCode("ABC123")

        kotlin.test.assertEquals(expected, result)
        verify(gameDao).findByLobbyCode("ABC123")
    }

    @Test
    fun `validateLobbyCode throws GameNotFoundException when lobby code does not exist`() {
        whenever(gameDao.findByLobbyCode("WRONG")).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.validateLobbyCode("WRONG")
        }

        verify(gameDao).findByLobbyCode("WRONG")
    }

    @Test
    fun `joinLobby adds user to lobby found by code`() {
        val lobbyCode = "ABC123"
        val gameId = 1
        val userId = 10
        val expectedPlayer = player(1)

        whenever(gameDao.findByLobbyCode(any())).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())
        whenever(playerDao.addPlayer(gameId, userId)).thenReturn(expectedPlayer)

        val result = lobbyService.joinLobby(lobbyCode, userId)

        kotlin.test.assertEquals(expectedPlayer, result)
        verify(gameDao).findByLobbyCode(any())
        verify(playerDao).addPlayer(gameId, userId)
    }

    @Test
    fun `joinLobby throws GameNotFoundException when lobby code does not exist`() {
        val lobbyCode = "WRONG"

        whenever(gameDao.findByLobbyCode(lobbyCode)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.joinLobby(lobbyCode, 10)
        }

        verify(gameDao).findByLobbyCode(lobbyCode)
    }
}
