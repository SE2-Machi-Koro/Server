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

    @Test
    fun `addUserToLobby adds player successfully`() {
        val gameId = 1
        val userId = 10
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())
        whenever(playerDao.addPlayer(gameId, userId)).thenReturn(player(1))

        val result = lobbyService.addUserToLobby(gameId, userId)

        assertNotNull(result)
        verify(playerDao).addPlayer(gameId, userId)
    }

    @Test
    fun `addUserToLobby throws GameNotFoundException`() {
        whenever(gameDao.findById(any())).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.addUserToLobby(1, 10)
        }
    }

    @Test
    fun `addUserToLobby throws GameStartedException`() {
        val gameId = 2
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))

        assertThrows<GameStartedException> {
            lobbyService.addUserToLobby(gameId, 10)
        }
    }

    @Test
    fun `addUserToLobby throws LobbyFullException`() {
        val gameId = 3
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(1), player(2), player(3), player(4))
        )

        assertThrows<LobbyFullException> {
            lobbyService.addUserToLobby(gameId, 10)
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

}
