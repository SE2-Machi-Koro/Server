package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Games
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.PlayerNotFoundException

class PlayerDaoTest : AbstractDBSetup() {

    private val userDao = UserDao()
    private val gameDao = GameDao()
    private val playerDao = PlayerDao()

    private var userId = 0
    private var gameId = 0

    @BeforeEach
    fun seed() {
        userId = userDao.create("player_user")
        gameId = gameDao.create(userId)
    }

    @AfterEach
    fun cleanup() {
        transaction {
            Players.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `addPlayer and findById returns correct player`() {
        val player = playerDao.addPlayer(gameId, userId)
        val id = player.id
        val foundPlayer = playerDao.findById(id)
        assertNotNull(foundPlayer)
        assertEquals(gameId, foundPlayer!!.gameId)
        assertEquals(userId, foundPlayer.userId)
        assertEquals(0, foundPlayer.turnOrder)
        assertEquals(3, foundPlayer.coins)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(playerDao.findById(999))
    }

    @Test
    fun `getPlayers returns all players in game`() {
        val userId2 = userDao.create("player_user_2")
        playerDao.addPlayer(gameId, userId)
        playerDao.addPlayer(gameId, userId2)
        assertEquals(2, playerDao.getPlayers(gameId).size)
    }

    @Test
    fun `getPlayers returns empty list when no players`() {
        assertTrue(playerDao.getPlayers(gameId).isEmpty())
    }

    @Test
    fun `getLobbyRoster returns usernames and preserves turn order`() {
        val userId2 = userDao.create("player_user_2")
        val player1 = playerDao.addPlayer(gameId, userId)
        val player2 = playerDao.addPlayer(gameId, userId2)
        playerDao.updateCoins(player2.id, 7)

        val roster = playerDao.getLobbyRoster(gameId)

        assertEquals(2, roster.size)
        assertEquals(player1.id, roster[0].playerId)
        assertEquals(userId, roster[0].userId)
        assertEquals("player_user", roster[0].username)
        assertEquals(gameId, roster[0].gameId)
        assertEquals(0, roster[0].turnOrder)
        assertEquals(3, roster[0].coins)
        assertEquals(player2.id, roster[1].playerId)
        assertEquals(userId2, roster[1].userId)
        assertEquals("player_user_2", roster[1].username)
        assertEquals(gameId, roster[1].gameId)
        assertEquals(1, roster[1].turnOrder)
        assertEquals(7, roster[1].coins)
    }

    @Test
    fun `updateCoins sets new coin count`() {
        val player = playerDao.addPlayer(gameId, userId)
        playerDao.updateCoins(player.id, 10)
        assertEquals(10, playerDao.findById(player.id)!!.coins)
    }

    @Test
    fun `updateCoins throws when player does not exist`() {
        assertThrows<PlayerNotFoundException> {
            playerDao.updateCoins(999999, 10)
        }
    }

    @Test
    fun `findAll returns all players in db`() {
        val player1 = playerDao.addPlayer(gameId, userId)
        val userId2 = userDao.create("player_user_2")
        val player2 = playerDao.addPlayer(gameId, userId2)
        val allPlayers = playerDao.findAll()
        assertTrue(allPlayers.any { it.id == player1.id })
        assertTrue(allPlayers.any { it.id == player2.id })
    }

    @Test
    fun `deleteByPlayerId removes player from db`() {
        val player = playerDao.addPlayer(gameId, userId)
        playerDao.deleteByPlayerId(player.id)
        assertNull(playerDao.findById(player.id))
    }

    @Test
    fun `deleteByPlayerId throws when player does not exist`() {
        assertThrows<PlayerNotFoundException> {
            playerDao.deleteByPlayerId(999999)
        }
    }

    @Test
    fun `updateTurnOrder changes player turn order`() {
        val player = playerDao.addPlayer(gameId, userId)
        playerDao.updateTurnOrder(player.id, 5)
        val updated = playerDao.findById(player.id)
        assertEquals(5, updated?.turnOrder)
    }

    @Test
    fun `updateTurnOrder throws when player does not exist`() {
        assertThrows<PlayerNotFoundException> {
            playerDao.updateTurnOrder(999999, 5)
        }
    }

    @Test
    fun `countByGameId returns 0 when no players exist`() {
        val count = playerDao.countByGameId(gameId)
        assertEquals(0, count)
    }

    @Test
    fun `countByGameId decreases after player deletion`() {
        val player = playerDao.addPlayer(gameId, userId)
        assertEquals(1, playerDao.countByGameId(gameId))
        playerDao.deleteByPlayerId(player.id)
        assertEquals(0, playerDao.countByGameId(gameId))
    }


    @Test
    fun `addPlayer assigns incrementing turn order`() {
        val userId2 = userDao.create("player_user_2")
        val player1 = playerDao.addPlayer(gameId, userId)
        val player2 = playerDao.addPlayer(gameId, userId2)
        assertEquals(0, player1.turnOrder)
        assertEquals(1, player2.turnOrder)
    }

    @Test
    fun `findByGameIdAndUserId returns player when mapping exists`() {
        val player = playerDao.addPlayer(gameId, userId)

        val found = playerDao.findByGameIdAndUserId(gameId, userId)

        assertNotNull(found)
        assertEquals(player.id, found!!.id)
    }

    @Test
    fun `findByGameIdAndUserId returns null when mapping is missing`() {
        assertNull(playerDao.findByGameIdAndUserId(gameId, 999999))
    }

    @Test
    fun `findActiveGameIdByUserId returns newest in-progress game`() {
        val olderGameId = gameDao.create(userId)
        val newerGameId = gameDao.create(userId)

        playerDao.addPlayer(olderGameId, userId)
        playerDao.addPlayer(newerGameId, userId)

        gameDao.updateStatus(olderGameId, GameStatus.IN_PROGRESS)
        gameDao.updateStatus(newerGameId, GameStatus.IN_PROGRESS)

        assertEquals(newerGameId, playerDao.findActiveGameIdByUserId(userId))
    }

    @Test
    fun `findActiveGameIdByUserId ignores waiting games`() {
        val waitingGameId = gameDao.create(userId)
        playerDao.addPlayer(waitingGameId, userId)

        assertNull(playerDao.findActiveGameIdByUserId(userId))
    }

    @Test
    fun `findWaitingGameIdsByUserId returns only waiting memberships`() {
        val waitingGameId = gameDao.create(userId)
        val activeGameId = gameDao.create(userId)
        val finishedGameId = gameDao.create(userId)

        playerDao.addPlayer(waitingGameId, userId)
        playerDao.addPlayer(activeGameId, userId)
        playerDao.addPlayer(finishedGameId, userId)
        gameDao.updateStatus(activeGameId, GameStatus.IN_PROGRESS)
        gameDao.updateStatus(finishedGameId, GameStatus.FINISHED)

        assertEquals(listOf(waitingGameId), playerDao.findWaitingGameIdsByUserId(userId))
    }

    @Test
    fun `findWaitingMembershipByUserId returns newest waiting membership`() {
        val olderWaitingGameId = gameDao.create(userId)
        val newerWaitingGameId = gameDao.create(userId)
        val activeGameId = gameDao.create(userId)
        playerDao.addPlayer(olderWaitingGameId, userId)
        val expectedPlayer = playerDao.addPlayer(newerWaitingGameId, userId)
        playerDao.addPlayer(activeGameId, userId)
        gameDao.updateStatus(activeGameId, GameStatus.IN_PROGRESS)

        val membership = playerDao.findWaitingMembershipByUserId(userId)

        assertNotNull(membership)
        assertEquals(newerWaitingGameId, membership!!.game.id)
        assertEquals(expectedPlayer.id, membership.player.id)
        assertEquals(GameStatus.WAITING, membership.game.status)
    }

    @Test
    fun `findWaitingMembershipByUserId returns null when user has no waiting lobby`() {
        val activeGameId = gameDao.create(userId)
        playerDao.addPlayer(activeGameId, userId)
        gameDao.updateStatus(activeGameId, GameStatus.IN_PROGRESS)

        assertNull(playerDao.findWaitingMembershipByUserId(userId))
    }

    @Test
    fun `findCurrentMembershipByUserId prefers newest in-progress game over waiting lobby`() {
        val waitingGameId = gameDao.create(userId)
        val activeGameId = gameDao.create(userId)
        val waitingPlayer = playerDao.addPlayer(waitingGameId, userId)
        val activePlayer = playerDao.addPlayer(activeGameId, userId)
        gameDao.updateStatus(activeGameId, GameStatus.IN_PROGRESS)

        val membership = playerDao.findCurrentMembershipByUserId(userId)

        assertNotNull(membership)
        assertEquals(activeGameId, membership!!.game.id)
        assertEquals(activePlayer.id, membership.player.id)
        assertNotEquals(waitingPlayer.id, membership.player.id)
    }

    @Test
    fun `findCurrentMembershipByUserId returns waiting lobby when no in-progress game exists`() {
        val olderWaitingGameId = gameDao.create(userId)
        val newerWaitingGameId = gameDao.create(userId)
        playerDao.addPlayer(olderWaitingGameId, userId)
        val expectedPlayer = playerDao.addPlayer(newerWaitingGameId, userId)

        val membership = playerDao.findCurrentMembershipByUserId(userId)

        assertNotNull(membership)
        assertEquals(newerWaitingGameId, membership!!.game.id)
        assertEquals(expectedPlayer.id, membership.player.id)
        assertEquals(GameStatus.WAITING, membership.game.status)
    }

    @Test
    fun `findCurrentMembershipByUserId ignores finished games`() {
        val finishedGameId = gameDao.create(userId)
        playerDao.addPlayer(finishedGameId, userId)
        gameDao.updateStatus(finishedGameId, GameStatus.FINISHED)

        assertNull(playerDao.findCurrentMembershipByUserId(userId))
    }

    @Test
    fun `updateLastSeen stores a timestamp for player`() {
        val player = playerDao.addPlayer(gameId, userId)
        val before = System.currentTimeMillis()

        playerDao.updateLastSeen(player.id)

        val updated = playerDao.findById(player.id)
        assertNotNull(updated?.lastSeenAt)
        assertTrue(updated!!.lastSeenAt!! >= before)
    }

    @Test
    fun `updateLastSeen throws when player does not exist`() {
        assertThrows<PlayerNotFoundException> {
            playerDao.updateLastSeen(999999)
        }
    }

    @Test
    fun `deleteByGameId removes all players from game`() {
        val userId2 = userDao.create("player_user_2")

        playerDao.addPlayer(gameId, userId)
        playerDao.addPlayer(gameId, userId2)

        assertEquals(2, playerDao.countByGameId(gameId))

        playerDao.deleteByGameId(gameId)

        assertTrue(playerDao.getPlayers(gameId).isEmpty())
    }

    @Test
    fun `deleteByGameId does not throw when game has no players`() {
        assertDoesNotThrow { playerDao.deleteByGameId(gameId) }
    }
}
