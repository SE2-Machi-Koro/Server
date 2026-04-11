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
    fun `create and findById returns correct player`() {
        val id = playerDao.create(gameId, userId, turnOrder = 0)
        val player = playerDao.findById(id)
        assertNotNull(player)
        assertEquals(gameId, player!!.gameId)
        assertEquals(userId, player.userId)
        assertEquals(0, player.turnOrder)
        assertEquals(3, player.coins)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(playerDao.findById(999))
    }

    @Test
    fun `findByGameId returns all players in game`() {
        val userId2 = userDao.create("player_user_2")
        playerDao.create(gameId, userId, turnOrder = 0)
        playerDao.create(gameId, userId2, turnOrder = 1)
        assertEquals(2, playerDao.findByGameId(gameId).size)
    }

    @Test
    fun `findByGameId returns empty list when no players`() {
        assertTrue(playerDao.findByGameId(gameId).isEmpty())
    }

    @Test
    fun `findByUserIdAndGameId returns correct player`() {
        playerDao.create(gameId, userId, turnOrder = 0)
        val player = playerDao.findByUserIdAndGameId(userId, gameId)
        assertNotNull(player)
        assertEquals(userId, player!!.userId)
        assertEquals(gameId, player.gameId)
    }

    @Test
    fun `findByUserIdAndGameId returns null for wrong combination`() {
        assertNull(playerDao.findByUserIdAndGameId(999, gameId))
    }

    @Test
    fun `updateCoins sets new coin count`() {
        val id = playerDao.create(gameId, userId, turnOrder = 0)
        playerDao.updateCoins(id, 10)
        assertEquals(10, playerDao.findById(id)!!.coins)
    }
}