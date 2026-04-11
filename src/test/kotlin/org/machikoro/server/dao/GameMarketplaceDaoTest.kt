package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.Games
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardType

class GameMarketplaceDaoTest : AbstractDBSetup() {

    private val userDao = UserDao()
    private val gameDao = GameDao()
    private val marketplaceDao = GameMarketplaceDao()

    private var gameId = 0

    @BeforeEach
    fun seed() {
        val hostId = userDao.create("market_host")
        gameId = gameDao.create(hostId)
    }

    @AfterEach
    fun cleanup() {
        transaction {
            GameMarketplace.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `findByGameId returns empty list before init`() {
        assertTrue(marketplaceDao.findByGameId(gameId).isEmpty())
    }

    @Test
    fun `initForGame creates one entry per card type with default supply`() {
        marketplaceDao.initForGame(gameId)
        val entries = marketplaceDao.findByGameId(gameId)
        assertEquals(CardType.entries.size, entries.size)
        assertTrue(entries.all { it.quantityAvailable == 6 })
    }

    @Test
    fun `initForGame respects custom supplyPerCard`() {
        marketplaceDao.initForGame(gameId, supplyPerCard = 4)
        assertTrue(marketplaceDao.findByGameId(gameId).all { it.quantityAvailable == 4 })
    }

    @Test
    fun `findByGameIdAndType returns correct entry after init`() {
        marketplaceDao.initForGame(gameId)
        val entry = marketplaceDao.findByGameIdAndType(gameId, CardType.WHEAT_FIELD)
        assertNotNull(entry)
        assertEquals(CardType.WHEAT_FIELD, entry!!.cardType)
        assertEquals(6, entry.quantityAvailable)
    }

    @Test
    fun `findByGameIdAndType returns null before init`() {
        assertNull(marketplaceDao.findByGameIdAndType(gameId, CardType.WHEAT_FIELD))
    }

    @Test
    fun `isAvailable returns true when quantity is above zero`() {
        marketplaceDao.initForGame(gameId)
        assertTrue(marketplaceDao.isAvailable(gameId, CardType.BAKERY))
    }

    @Test
    fun `isAvailable returns false for uninitialised entry`() {
        assertFalse(marketplaceDao.isAvailable(gameId, CardType.BAKERY))
    }

    @Test
    fun `decrementQuantity reduces available count by one`() {
        marketplaceDao.initForGame(gameId)
        marketplaceDao.decrementQuantity(gameId, CardType.BAKERY)
        assertEquals(5, marketplaceDao.findByGameIdAndType(gameId, CardType.BAKERY)!!.quantityAvailable)
    }

    @Test
    fun `isAvailable returns false when quantity reaches zero`() {
        marketplaceDao.initForGame(gameId, supplyPerCard = 1)
        marketplaceDao.decrementQuantity(gameId, CardType.CAFE)
        assertFalse(marketplaceDao.isAvailable(gameId, CardType.CAFE))
    }
}