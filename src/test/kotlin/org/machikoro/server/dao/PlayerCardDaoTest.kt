package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Games
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardType

class PlayerCardDaoTest : AbstractDBSetup() {

    private val userDao = UserDao()
    private val gameDao = GameDao()
    private val playerDao = PlayerDao()
    private val playerCardDao = PlayerCardDao()

    private var playerId = 0

    @BeforeEach
    fun seed() {
        val userId = userDao.create("card_user")
        val gameId = gameDao.create(userId)
        playerId = playerDao.create(gameId, userId, turnOrder = 0)
    }

    @AfterEach
    fun cleanup() {
        transaction {
            PlayerCards.deleteAll()
            Players.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `findByPlayerId returns empty list when player has no cards`() {
        assertTrue(playerCardDao.findByPlayerId(playerId).isEmpty())
    }

    @Test
    fun `upsert inserts new card entry`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        val cards = playerCardDao.findByPlayerId(playerId)
        assertEquals(1, cards.size)
        assertEquals(CardType.WHEAT_FIELD, cards[0].cardType)
        assertEquals(1, cards[0].quantity)
    }

    @Test
    fun `upsert updates quantity for existing card`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 3)
        val cards = playerCardDao.findByPlayerId(playerId)
        assertEquals(1, cards.size)
        assertEquals(3, cards[0].quantity)
    }

    @Test
    fun `upsert with quantity zero deletes the row`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 0)
        assertTrue(playerCardDao.findByPlayerId(playerId).isEmpty())
    }

    @Test
    fun `upsert handles multiple different card types`() {
        playerCardDao.upsert(playerId, CardType.WHEAT_FIELD, 1)
        playerCardDao.upsert(playerId, CardType.BAKERY, 2)
        assertEquals(2, playerCardDao.findByPlayerId(playerId).size)
    }
}