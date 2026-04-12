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
    fun `findAll returns empty list before init`() {
        assertTrue(marketplaceDao.findAll().isEmpty())
    }

    @Test
    fun `findAll returns all entries after init`() {
        marketplaceDao.initForGame(gameId)
        assertEquals(CardType.entries.size, marketplaceDao.findAll().size)
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

    @Test
    fun `updateQuantity sets quantity to specified value`() {
        marketplaceDao.initForGame(gameId)
        marketplaceDao.updateQuantity(gameId, CardType.WHEAT_FIELD, 10)
        assertEquals(10, marketplaceDao.findByGameIdAndType(gameId, CardType.WHEAT_FIELD)!!.quantityAvailable)
    }

    @Test
    fun `delete removes specific card entry from marketplace`() {
        marketplaceDao.initForGame(gameId)
        marketplaceDao.delete(gameId, CardType.WHEAT_FIELD)
        assertNull(marketplaceDao.findByGameIdAndType(gameId, CardType.WHEAT_FIELD))
    }

    @Test
    fun `delete on non-existent entry does not throw`() {
        assertDoesNotThrow {
            marketplaceDao.delete(gameId, CardType.WHEAT_FIELD)
        }
    }

    @Test
    fun `deleteAllForGame removes all entries for that game`() {
        marketplaceDao.initForGame(gameId)
        marketplaceDao.deleteAllForGame(gameId)
        assertTrue(marketplaceDao.findByGameId(gameId).isEmpty())
    }

    @Test
    fun `deleteAllForGame does not affect other games`() {
        val hostId2 = userDao.create("host_2")
        val gameId2 = gameDao.create(hostId2)
        marketplaceDao.initForGame(gameId)
        marketplaceDao.initForGame(gameId2)
        marketplaceDao.deleteAllForGame(gameId)
        assertEquals(CardType.entries.size, marketplaceDao.findByGameId(gameId2).size)
    }
}