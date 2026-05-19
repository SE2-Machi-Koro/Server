package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameFinishedException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.machikoro.server.exception.NotEnoughPlayersException
import org.machikoro.server.exception.NotHostException
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
    private val userDao = mock<UserDao>()
    private val gameMarketplaceDao = mock<GameMarketplaceDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val initializationService = mock<InitializationService>()
    private val playerCardDao = mock<PlayerCardDao>()
    private val cardDao = mock<CardDao>()
    private val landmarkDao = mock<LandmarkDao>()

    // Anonymous subclass that bypasses the real Exposed transaction so this
    // unit test doesn't need a database. Methods that don't call
    // runInTransaction (e.g. addUserToLobby) are unaffected.
    private val lobbyService = object : LobbyService(
        gameDao,
        playerDao,
        userDao,
        gameMarketplaceDao,
        playerLandmarkDao,
        initializationService,
        playerCardDao,
        cardDao,
        landmarkDao,
    ) {
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
    fun `addUserToLobby throws GameFinishedException when game is finished and user is not a player`() {
        val gameId = 5
        val userId = 10
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.FINISHED))
        // playerDao.findByGameIdAndUserId returns null by default → user is not
        // an existing player, so the reconnect short-circuit doesn't apply and
        // the FINISHED check must fire.

        val ex = assertThrows<GameFinishedException> {
            lobbyService.addUserToLobby(gameId, userId)
        }

        // GAME_FINISHED is the contract code the client matches on — assert
        // both the type and the code so a future refactor that changes the
        // exception hierarchy can't silently drop the code.
        assertEquals("GAME_FINISHED", ex.errorCode)
        // No player row must be inserted into a finished game.
        verify(playerDao, never()).addPlayer(any(), any())
    }

    @Test
    fun `addUserToLobby returns existing player on reconnect to FINISHED game without throwing`() {
        // Pins the design choice: existing players can rejoin a finished game
        // (to pull final state). The reconnect short-circuit runs before any
        // status check, mirroring how IN_PROGRESS games already behave.
        val gameId = 6
        val userId = 11
        val existing = player(99).copy(gameId = gameId, userId = userId)
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.FINISHED))
        whenever(playerDao.findByGameIdAndUserId(gameId, userId)).thenReturn(existing)

        val result = lobbyService.addUserToLobby(gameId, userId)

        assertSame(existing, result)
        verify(playerDao, never()).addPlayer(any(), any())
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

    @Test
    fun `startGame delegates resource initialization to InitializationService`() {
        val gameId = 1
        val players = listOf(player(1), player(2))
        val updatedGame = game(gameId, GameStatus.IN_PROGRESS)
        // Simulate each player starting with WHEAT_FIELD and BAKERY.
        val p1Cards = listOf(
            PlayerCardModel(playerId = 1, cardType = CardType.WHEAT_FIELD, quantity = 1),
            PlayerCardModel(playerId = 1, cardType = CardType.BAKERY, quantity = 1),
        )
        val p2Cards = listOf(
            PlayerCardModel(playerId = 2, cardType = CardType.WHEAT_FIELD, quantity = 1),
            PlayerCardModel(playerId = 2, cardType = CardType.BAKERY, quantity = 1),
        )

        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(players)
        whenever(initializationService.initializeGame(gameId)).thenReturn(players)
        whenever(gameDao.findById(gameId)).thenReturn(updatedGame)
        whenever(playerCardDao.findByPlayerIds(listOf(1, 2))).thenReturn(mapOf(1 to p1Cards, 2 to p2Cards))
        whenever(playerLandmarkDao.findByPlayerId(1)).thenReturn(emptyList())
        whenever(playerLandmarkDao.findByPlayerId(2)).thenReturn(emptyList())
        whenever(gameMarketplaceDao.findByGameIdAsMap(gameId)).thenReturn(emptyMap())

        val result = lobbyService.startGame(gameId)

        verify(initializationService).initializeGame(gameId)
        assertEquals(GameStatus.IN_PROGRESS, result.game.status)
        // Snapshot must carry the initialized cards — not an empty map.
        assertEquals(2, result.playerCards[1]?.size)
        assertEquals(2, result.playerCards[2]?.size)
        assertEquals(setOf(CardType.WHEAT_FIELD, CardType.BAKERY), result.playerCards[1]?.map { it.cardType }?.toSet())
    }

    @Test
    fun `startGame throws NotEnoughPlayersException with only 1 player`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(player(1)))

        assertThrows<NotEnoughPlayersException> {
            lobbyService.startGame(gameId)
        }

        verify(initializationService, never()).initializeGame(any())
    }

    private fun user(id: Int) = UserModel(
        id = id,
        username = "user$id",
        passwordHash = null,
        sessionToken = null,
        totalWins = 0,
        totalGamesPlayed = 0,
    )

    @Test
    fun `getLobbyRoster returns mapped entries for all players whose user record exists`() {
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(10), player(20)))
        whenever(userDao.findById(10)).thenReturn(user(10))
        whenever(userDao.findById(20)).thenReturn(user(20))

        val roster = lobbyService.getLobbyRoster(1)

        assertEquals(2, roster.size)
        assertEquals(10, roster[0]["playerId"])
        assertEquals(10, roster[0]["userId"])
        assertEquals("user10", roster[0]["username"])
        assertEquals(3, roster[0]["coins"])
        assertEquals(20, roster[1]["playerId"])
        assertEquals("user20", roster[1]["username"])
    }

    @Test
    fun `getLobbyRoster silently skips players whose user record is not found`() {
        whenever(playerDao.getPlayers(1)).thenReturn(listOf(player(10), player(99)))
        whenever(userDao.findById(10)).thenReturn(user(10))
        whenever(userDao.findById(99)).thenReturn(null)

        val roster = lobbyService.getLobbyRoster(1)

        // Player 99 has no user record — must be skipped, not cause an error
        assertEquals(1, roster.size)
        assertEquals(10, roster[0]["playerId"])
    }

    @Test
    fun `getLobbyRoster returns empty list when game has no players`() {
        whenever(playerDao.getPlayers(1)).thenReturn(emptyList())

        val roster = lobbyService.getLobbyRoster(1)

        assertEquals(0, roster.size)
    }

    @Test
    fun `startGame throws NotEnoughPlayersException with no players`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())

        assertThrows<NotEnoughPlayersException> {
            lobbyService.startGame(gameId)
        }

        verify(initializationService, never()).initializeGame(any())
    }

    @Test
    fun `startGame throws NotHostException when requesting user is not the host`() {
        val gameId = 1
        val hostUserId = 1
        val requestingUserId = 999

        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING).copy(hostUserId = hostUserId))

        assertThrows<NotHostException> {
            lobbyService.startGame(gameId, requestingUserId)
        }

        verify(initializationService, never()).initializeGame(any())
    }
}
