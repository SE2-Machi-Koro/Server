package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.LobbyLeavingOutcome
import org.machikoro.server.dto.LobbyRosterPlayerDto
import org.machikoro.server.exception.GameFinishedException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.machikoro.server.exception.NotEnoughPlayersException
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.exception.PlayerNotFoundException
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

    private fun rosterEntry(playerId: Int, username: String, gameId: Int = 1) =
        LobbyRosterPlayerDto(playerId = playerId, userId = playerId, username = username, gameId = gameId, turnOrder = playerId - 1, coins = 3)

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

        whenever(gameDao.findById(gameId))
            .thenReturn(game(gameId, GameStatus.WAITING))
            .thenReturn(updatedGame)
        whenever(playerDao.getPlayers(gameId)).thenReturn(players)
        whenever(initializationService.initializeGame(gameId)).thenReturn(players)
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
    fun `startGame throws GameStartedException when game is already IN_PROGRESS`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))

        assertThrows<GameStartedException> {
            lobbyService.startGame(gameId)
        }

        // Initialization must never run — state must not be mutated
        verify(initializationService, never()).initializeGame(any())
    }

    @Test
    fun `startGame throws GameFinishedException when game is already FINISHED`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.FINISHED))

        assertThrows<GameFinishedException> {
            lobbyService.startGame(gameId)
        }

        verify(initializationService, never()).initializeGame(any())
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

    @Test
    fun `getLobbyRoster returns player dao roster`() {
        val expected = listOf(
            LobbyRosterPlayerDto(playerId = 10, userId = 10, username = "user10", gameId = 1, turnOrder = 0, coins = 3),
            LobbyRosterPlayerDto(playerId = 20, userId = 20, username = "user20", gameId = 1, turnOrder = 1, coins = 3),
        )
        whenever(playerDao.getLobbyRoster(1)).thenReturn(expected)

        val roster = lobbyService.getLobbyRoster(1)

        assertEquals(expected, roster)
        verify(playerDao).getLobbyRoster(1)
    }

    @Test
    fun `getLobbyRoster returns empty list when game has no players`() {
        whenever(playerDao.getLobbyRoster(1)).thenReturn(emptyList())

        val roster = lobbyService.getLobbyRoster(1)

        assertEquals(0, roster.size)
        verify(playerDao).getLobbyRoster(1)
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

    // === leaveLobby() ===

    @Test
    fun `leaveLobby throws PlayerNotFoundException when player is not in the game`() {
        whenever(playerDao.findByGameIdAndUserId(1, 99))
            .thenReturn(null)

        assertThrows<PlayerNotFoundException> {
            lobbyService.leaveLobby(1, 99)
        }

        verify(playerDao, never())
            .deleteByPlayerId(any())
    }

    @Test
    fun `leaveLobby returns LobbyRemains when real players still remain`() {
        val gameId = 1
        val userId = 10

        val p = player(5).copy(
            gameId = gameId,
            userId = userId
        )

        whenever(playerDao.findByGameIdAndUserId(gameId, userId))
            .thenReturn(p)

        whenever(gameDao.findById(gameId))
            .thenReturn(
                game(gameId, GameStatus.WAITING)
                    .copy(hostUserId = 999)
            )

        whenever(playerDao.getLobbyRoster(gameId))
            .thenReturn(
                listOf(
                    rosterEntry(6, "alice", gameId)
                )
            )

        val result =
            lobbyService.leaveLobby(gameId, userId)

        assertEquals(
            LobbyLeavingOutcome.LobbyRemains(
                userId
            ),
            result
        )

        verify(playerDao)
            .deleteByPlayerId(5)

        verify(playerDao, never())
            .deleteByGameId(any())

        verify(gameDao, never())
            .delete(any())
    }

    @Test
    fun `leaveLobby returns LobbyDeleted when only dummy players remain`() {
        val gameId = 1
        val userId = 10

        val p = player(5).copy(
            gameId = gameId,
            userId = userId
        )

        whenever(playerDao.findByGameIdAndUserId(gameId, userId))
            .thenReturn(p)

        whenever(gameDao.findById(gameId))
            .thenReturn(
                game(gameId, GameStatus.WAITING)
                    .copy(hostUserId = 999)
            )

        whenever(playerDao.getLobbyRoster(gameId))
            .thenReturn(
                listOf(
                    rosterEntry(
                        2,
                        "debug_player2",
                        gameId
                    )
                )
            )

        val result =
            lobbyService.leaveLobby(gameId, userId)

        assertEquals(
            LobbyLeavingOutcome.LobbyDeleted(gameId),
            result
        )

        verify(playerDao)
            .deleteByPlayerId(5)

        verify(playerDao)
            .deleteByGameId(gameId)

        verify(gameDao)
            .delete(gameId)
    }

    @Test
    fun `leaveLobby returns LobbyDeleted when roster is empty after player leaves`() {
        val gameId = 1
        val userId = 10

        val p = player(5).copy(
            gameId = gameId,
            userId = userId
        )

        whenever(playerDao.findByGameIdAndUserId(gameId, userId))
            .thenReturn(p)

        whenever(gameDao.findById(gameId))
            .thenReturn(
                game(gameId, GameStatus.WAITING)
                    .copy(hostUserId = 999)
            )

        whenever(playerDao.getLobbyRoster(gameId))
            .thenReturn(emptyList())

        val result =
            lobbyService.leaveLobby(gameId, userId)

        assertEquals(
            LobbyLeavingOutcome.LobbyDeleted(gameId),
            result
        )

        verify(playerDao)
            .deleteByPlayerId(5)

        verify(playerDao)
            .deleteByGameId(gameId)

        verify(gameDao)
            .delete(gameId)
    }

    @Test
    fun `leaveLobby deletes lobby immediately when host leaves`() {
        val gameId = 1
        val hostUserId = 10

        val host = player(5).copy(
            gameId = gameId,
            userId = hostUserId
        )

        whenever(
            playerDao.findByGameIdAndUserId(
                gameId,
                hostUserId
            )
        ).thenReturn(host)

        whenever(gameDao.findById(gameId))
            .thenReturn(
                game(gameId, GameStatus.WAITING)
                    .copy(hostUserId = hostUserId)
            )

        val result =
            lobbyService.leaveLobby(
                gameId,
                hostUserId
            )

        assertEquals(
            LobbyLeavingOutcome.LobbyDeleted(gameId),
            result
        )

        verify(playerDao, never())
            .deleteByPlayerId(any())

        verify(playerDao)
            .deleteByGameId(gameId)

        verify(gameDao)
            .delete(gameId)
    }

    // === resetLobby() ===

    @Test
    fun `resetLobby removes only dummy players and returns their roster entries`() {
        val lobbyCode = "ABC123"
        val gameId = 1
        whenever(gameDao.findByLobbyCode(lobbyCode)).thenReturn(game(gameId, GameStatus.WAITING))
        val dummies = listOf(rosterEntry(2, "debug_player2", gameId), rosterEntry(3, "debug_player3", gameId))
        whenever(playerDao.getLobbyRoster(gameId)).thenReturn(listOf(rosterEntry(1, "alice", gameId)) + dummies)

        val removed = lobbyService.resetLobby(lobbyCode)

        assertEquals(2, removed.size)
        assertEquals("debug_player2", removed[0].username)
        assertEquals("debug_player3", removed[1].username)
        verify(playerDao).deleteByPlayerId(2)
        verify(playerDao).deleteByPlayerId(3)
        verify(playerDao, never()).deleteByPlayerId(1)
    }

    @Test
    fun `resetLobby returns empty list when no dummy players are present`() {
        val lobbyCode = "ABC123"
        val gameId = 1
        whenever(gameDao.findByLobbyCode(lobbyCode)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.getLobbyRoster(gameId)).thenReturn(
            listOf(rosterEntry(1, "alice", gameId), rosterEntry(2, "bob", gameId))
        )

        val removed = lobbyService.resetLobby(lobbyCode)

        assertTrue(removed.isEmpty())
        verify(playerDao, never()).deleteByPlayerId(any())
    }

    @Test
    fun `resetLobby throws GameNotFoundException when lobby code does not exist`() {
        whenever(gameDao.findByLobbyCode("NOPE")).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.resetLobby("NOPE")
        }

        verify(playerDao, never()).getLobbyRoster(any())
        verify(playerDao, never()).deleteByPlayerId(any())
    }

    // === purgeAllGames() ===

    @Test
    fun `purgeAllGames deletes players then game for every game and returns count`() {
        val games = listOf(game(1, GameStatus.IN_PROGRESS), game(2, GameStatus.WAITING))
        whenever(gameDao.findAll()).thenReturn(games)

        val count = lobbyService.purgeAllGames()

        assertEquals(2, count)
        verify(playerDao).deleteByGameId(1)
        verify(playerDao).deleteByGameId(2)
        verify(gameDao).delete(1)
        verify(gameDao).delete(2)
    }

    @Test
    fun `purgeAllGames returns 0 and makes no deletions when no games exist`() {
        whenever(gameDao.findAll()).thenReturn(emptyList())

        val count = lobbyService.purgeAllGames()

        assertEquals(0, count)
        verify(playerDao, never()).deleteByGameId(any())
        verify(gameDao, never()).delete(any())
    }

    // === toggleReady() ===

    @Test
    fun `toggleReady throws GameNotFoundException when game does not exist`() {
        whenever(gameDao.findById(99)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            lobbyService.toggleReady(99, 10, true)
        }

        verify(playerDao, never()).findByGameIdAndUserId(any(), any())
    }

    @Test
    fun `toggleReady throws PlayerNotFoundException when player is not in the game`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.findByGameIdAndUserId(gameId, 99)).thenReturn(null)

        assertThrows<PlayerNotFoundException> {
            lobbyService.toggleReady(gameId, 99, true)
        }
    }

    @Test
    fun `toggleReady marks player ready and getLobbyRoster reflects it`() {
        val gameId = 1
        val userId = 10
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.findByGameIdAndUserId(gameId, userId))
            .thenReturn(player(userId).copy(gameId = gameId, userId = userId))
        whenever(playerDao.getLobbyRoster(gameId))
            .thenReturn(listOf(rosterEntry(userId, "alice", gameId)))

        lobbyService.toggleReady(gameId, userId, true)

        assertEquals(true, lobbyService.getLobbyRoster(gameId).single().isReady)
    }

    @Test
    fun `toggleReady marks player not ready after toggling back`() {
        val gameId = 1
        val userId = 10
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.findByGameIdAndUserId(gameId, userId))
            .thenReturn(player(userId).copy(gameId = gameId, userId = userId))
        whenever(playerDao.getLobbyRoster(gameId))
            .thenReturn(listOf(rosterEntry(userId, "alice", gameId)))

        lobbyService.toggleReady(gameId, userId, true)
        lobbyService.toggleReady(gameId, userId, false)

        assertEquals(false, lobbyService.getLobbyRoster(gameId).single().isReady)
    }

    @Test
    fun `getLobbyRoster returns isReady true only for players in the ready set`() {
        val gameId = 1
        val readyUserId = 10
        val notReadyUserId = 20
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.findByGameIdAndUserId(gameId, readyUserId))
            .thenReturn(player(readyUserId).copy(gameId = gameId, userId = readyUserId))
        whenever(playerDao.getLobbyRoster(gameId)).thenReturn(listOf(
            rosterEntry(readyUserId, "alice", gameId),
            rosterEntry(notReadyUserId, "bob", gameId),
        ))

        lobbyService.toggleReady(gameId, readyUserId, true)
        val roster = lobbyService.getLobbyRoster(gameId)

        assertEquals(true, roster.find { it.userId == readyUserId }!!.isReady)
        assertEquals(false, roster.find { it.userId == notReadyUserId }!!.isReady)
    }

    @Test
    fun `leaveLobby clears ready state when lobby is deleted`() {
        val gameId = 1
        val userId = 10
        whenever(gameDao.findById(gameId))
            .thenReturn(game(gameId, GameStatus.WAITING).copy(hostUserId = userId))
        whenever(playerDao.findByGameIdAndUserId(gameId, userId))
            .thenReturn(player(5).copy(gameId = gameId, userId = userId))
        whenever(playerDao.getLobbyRoster(gameId))
            .thenReturn(listOf(rosterEntry(userId, "alice", gameId)))

        lobbyService.toggleReady(gameId, userId, true)
        assertEquals(true, lobbyService.getLobbyRoster(gameId).single().isReady)

        // Host leaves → lobby deleted → ready state must be gone
        lobbyService.leaveLobby(gameId, userId)
        assertEquals(false, lobbyService.getLobbyRoster(gameId).single().isReady)
    }

    @Test
    fun `purgeAllGames clears all ready state`() {
        val gameId = 1
        val userId = 10
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.WAITING))
        whenever(playerDao.findByGameIdAndUserId(gameId, userId))
            .thenReturn(player(userId).copy(gameId = gameId, userId = userId))
        whenever(playerDao.getLobbyRoster(gameId))
            .thenReturn(listOf(rosterEntry(userId, "alice", gameId)))
        whenever(gameDao.findAll()).thenReturn(listOf(game(gameId, GameStatus.WAITING)))

        lobbyService.toggleReady(gameId, userId, true)
        assertEquals(true, lobbyService.getLobbyRoster(gameId).single().isReady)

        lobbyService.purgeAllGames()
        assertEquals(false, lobbyService.getLobbyRoster(gameId).single().isReady)
    }

    // === startGame() — playerUsernames ===

    @Test
    fun `startGame snapshot includes playerUsernames resolved from getLobbyRoster`() {
        val gameId = 1
        val players = listOf(player(1), player(2))
        val updatedGame = game(gameId, GameStatus.IN_PROGRESS)
        whenever(gameDao.findById(gameId))
            .thenReturn(game(gameId, GameStatus.WAITING))
            .thenReturn(updatedGame)
        whenever(playerDao.getPlayers(gameId)).thenReturn(players)
        whenever(initializationService.initializeGame(gameId)).thenReturn(players)
        whenever(playerCardDao.findByPlayerIds(any())).thenReturn(emptyMap())
        whenever(playerLandmarkDao.findByPlayerId(any())).thenReturn(emptyList())
        whenever(gameMarketplaceDao.findByGameIdAsMap(gameId)).thenReturn(emptyMap())
        whenever(playerDao.getLobbyRoster(gameId)).thenReturn(
            listOf(rosterEntry(1, "alice", gameId), rosterEntry(2, "bob", gameId))
        )

        val result = lobbyService.startGame(gameId)

        assertEquals(mapOf(1 to "alice", 2 to "bob"), result.playerUsernames)
    }
}
