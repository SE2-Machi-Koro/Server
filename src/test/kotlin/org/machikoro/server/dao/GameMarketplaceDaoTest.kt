package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Cards
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
        transaction {
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.WHEAT_FIELD; it[Cards.cost] = 1; it[Cards.diceMin] =
                1; it[Cards.diceMax] = 1; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.RANCH; it[Cards.cost] = 1; it[Cards.diceMin] = 2; it[Cards.diceMax] =
                2; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FOREST; it[Cards.cost] = 3; it[Cards.diceMin] = 5; it[Cards.diceMax] =
                5; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.MINE; it[Cards.cost] = 6; it[Cards.diceMin] = 9; it[Cards.diceMax] =
                9; it[Cards.income] = 5
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.APPLE_ORCHARD; it[Cards.cost] = 3; it[Cards.diceMin] =
                10; it[Cards.diceMax] = 10; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.BAKERY; it[Cards.cost] = 1; it[Cards.diceMin] = 2; it[Cards.diceMax] =
                3; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.CONVENIENCE_STORE; it[Cards.cost] = 2; it[Cards.diceMin] =
                4; it[Cards.diceMax] = 4; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.CHEESE_FACTORY; it[Cards.cost] = 5; it[Cards.diceMin] =
                7; it[Cards.diceMax] = 7; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FURNITURE_FACTORY; it[Cards.cost] = 3; it[Cards.diceMin] =
                8; it[Cards.diceMax] = 8; it[Cards.income] = 3
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FRUIT_AND_VEGETABLE_MARKET; it[Cards.cost] = 2; it[Cards.diceMin] =
                11; it[Cards.diceMax] = 11; it[Cards.income] = 2
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.CAFE; it[Cards.cost] = 2; it[Cards.diceMin] = 3; it[Cards.diceMax] =
                3; it[Cards.income] = 1
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.FAMILY_RESTAURANT; it[Cards.cost] = 3; it[Cards.diceMin] =
                9; it[Cards.diceMax] = 10; it[Cards.income] = 2
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.STADIUM; it[Cards.cost] = 6; it[Cards.diceMin] = 6; it[Cards.diceMax] =
                6; it[Cards.income] = 2
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.TV_STATION; it[Cards.cost] = 7; it[Cards.diceMin] = 6; it[Cards.diceMax] =
                6; it[Cards.income] = 0
            }
            Cards.insertIgnore {
                it[Cards.cardType] = CardType.BUSINESS_CENTER; it[Cards.cost] = 8; it[Cards.diceMin] =
                6; it[Cards.diceMax] = 6; it[Cards.income] = 0
            }
        }
        val hostId = userDao.create("market_host")
        gameId = gameDao.create(hostId)
    }

    @AfterEach
    fun cleanup() {
        transaction {
            GameMarketplace.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Cards.deleteAll()
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