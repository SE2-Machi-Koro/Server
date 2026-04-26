package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Games
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users

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
    fun `updateCoins sets new coin count`() {
        val player = playerDao.addPlayer(gameId, userId)
        playerDao.updateCoins(player.id, 10)
        assertEquals(10, playerDao.findById(player.id)!!.coins)
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
    fun `delete removes player from db`() {
        val player = playerDao.addPlayer(gameId, userId)
        playerDao.delete(player.id)
        assertNull(playerDao.findById(player.id))
    }

    @Test
    fun `updateTurnOrder changes player turn order`() {
        val player = playerDao.addPlayer(gameId, userId)
        playerDao.updateTurnOrder(player.id, 5)
        val updated = playerDao.findById(player.id)
        assertEquals(5, updated?.turnOrder)
    }
}
