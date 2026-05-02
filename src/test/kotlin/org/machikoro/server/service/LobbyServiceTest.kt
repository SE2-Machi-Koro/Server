package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
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
    private val playerCardDao = mock<PlayerCardDao>()
    private val gameMarketplaceDao = mock<GameMarketplaceDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val connectionTracker = mock<WebSocketConnectionTracker>()
    private val lobbyService = LobbyService(gameDao, playerDao, playerCardDao, gameMarketplaceDao, playerLandmarkDao, connectionTracker)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun game(id: Int, status: GameStatus, hostUserId: Int = 1) = GameModel(
        id = id,
        status = status,
        hostUserId = hostUserId,
        lobbyCode = "ABC123",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        hasPurchasedThisTurn = false,
        roundNumber = 1,
    )

    private fun player(id: Int, userId: Int = id, gameId: Int = 1) =
        PlayerModel(id = id, gameId = gameId, userId = userId, turnOrder = 0, coins = 3, lastSeenAt = null)

    // ── addUserToLobby ────────────────────────────────────────────────────────

    @Test
    fun `addUserToLobby adds player successfully`() {
        val gameId = 1
        val userId = 10
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())
        whenever(playerDao.addPlayer(gameId, userId)).thenReturn(player(1, userId))

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

    // ── startGame ─────────────────────────────────────────────────────────────

    private fun setupValidStartGame(gameId: Int = 4, hostUserId: Int = 1): List<PlayerModel> {
        val players = listOf(player(1, userId = 1), player(2, userId = 2))
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING, hostUserId))
        whenever(connectionTracker.getConnectedUserIds(gameId)).thenReturn(setOf(1, 2))
        whenever(playerDao.getPlayers(gameId)).thenReturn(players)
        whenever(playerCardDao.findByPlayerId(any())).thenReturn(emptyList())
        return players
    }

    @Test
    fun `createLobby creates game and adds host as player`() {
        val hostUserId = 10
        val gameId = 1
        val createdGame = game(gameId, GameStatus.WAITING).copy(hostUserId = hostUserId)

        whenever(gameDao.create(any(), any(), any())).thenReturn(gameId)
        whenever(playerDao.addPlayer(gameId, hostUserId)).thenReturn(player(1))
        whenever(gameDao.findById(gameId)).thenReturn(createdGame)

        val result = lobbyService.createLobby(hostUserId)

        assertEquals(gameId, result.id)
        assertEquals(hostUserId, result.hostUserId)
        assertEquals(GameStatus.WAITING, result.status)

        verify(gameDao).create(eq(hostUserId), any<String>(), eq(4))
        verify(playerDao).addPlayer(gameId, hostUserId)
        verify(gameDao).findById(gameId)
    }

    @Test
    fun `createLobby throws GameNotFoundException when created game cannot be found`() {
        val hostUserId = 10
        val gameId = 1

        whenever(gameDao.create(any(), any(), any())).thenReturn(gameId)
        whenever(playerDao.addPlayer(gameId, hostUserId)).thenReturn(player(1))
        whenever(gameDao.findById(gameId)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.createLobby(hostUserId)
        }
    }

    @Test
    fun `startGame sets game status to IN_PROGRESS`() {
        val gameId = 4
        setupValidStartGame(gameId, hostUserId = 1)

        val result = lobbyService.startGame(gameId, requestingUserId = 1)

        assertEquals(GameStatus.IN_PROGRESS, result.game.status)
        verify(gameDao).updateStatus(gameId, GameStatus.IN_PROGRESS)
    }

    @Test
    fun `startGame initializes wheat field and bakery for each player`() {
        val gameId = 4
        val players = setupValidStartGame(gameId, hostUserId = 1)

        lobbyService.startGame(gameId, requestingUserId = 1)

        players.forEach { player ->
            verify(playerCardDao).upsert(player.id, CardType.WHEAT_FIELD, 1)
            verify(playerCardDao).upsert(player.id, CardType.BAKERY, 1)
        }
    }

    @Test
    fun `startGame returns a turn order containing all active player IDs`() {
        val gameId = 4
        val players = setupValidStartGame(gameId, hostUserId = 1)
        val expectedIds = players.map { it.id }.toSet()

        val result = lobbyService.startGame(gameId, requestingUserId = 1)

        assertEquals(expectedIds, result.turnOrder.toSet())
        assertEquals(players.size, result.turnOrder.size)
    }

    @Test
    fun `startGame throws GameNotFoundException when game does not exist`() {
        whenever(gameDao.findById(any())).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.startGame(1, requestingUserId = 1)
        }
    }

    @Test
    fun `startGame throws GameStartedException when game is already in progress`() {
        val gameId = 5
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))

        assertThrows<GameStartedException> {
            lobbyService.startGame(gameId, requestingUserId = 1)
        }
    }

    @Test
    fun `startGame throws NotHostException when requester is not the host`() {
        val gameId = 6
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING, hostUserId = 1))

        assertThrows<NotHostException> {
            lobbyService.startGame(gameId, requestingUserId = 99)
        }
    }

    @Test
    fun `startGame removes disconnected players from game state`() {
        val gameId = 7
        val allPlayers = listOf(player(1, userId = 1), player(2, userId = 2), player(3, userId = 3))
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING, hostUserId = 1))
        // Only users 1 and 2 are connected; user 3 has disconnected
        whenever(connectionTracker.getConnectedUserIds(gameId)).thenReturn(setOf(1, 2))
        whenever(playerDao.getPlayers(gameId)).thenReturn(allPlayers)
        whenever(playerCardDao.findByPlayerId(any())).thenReturn(emptyList())

        val result = lobbyService.startGame(gameId, requestingUserId = 1)

        // Player with userId=3 must not appear in the result
        assertEquals(2, result.players.size)
        assert(result.players.none { it.userId == 3 })
        // No cards initialized for the disconnected player (id=3)
        verify(playerCardDao, never()).upsert(eq(3), any(), any())
    }

    @Test
    fun `startGame throws CustomWebSocketException when fewer than 2 connected players`() {
        val gameId = 8
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING, hostUserId = 1))
        whenever(connectionTracker.getConnectedUserIds(gameId)).thenReturn(setOf(1))
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(player(1, userId = 1)))

        val ex = assertThrows<CustomWebSocketException> {
            lobbyService.startGame(gameId, requestingUserId = 1)
        }
        assertEquals("NOT_ENOUGH_PLAYERS", ex.errorCode)
    }
}
