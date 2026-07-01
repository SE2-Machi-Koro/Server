package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.database.Games
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.exception.CardNotFoundException

class GameMarketplaceDaoTest : AbstractDBSetup() {

    private val userDao = UserDao()
    private val gameDao = GameDao()
    private val marketplaceDao = GameMarketplaceDao()

    private var gameId = 0

    @BeforeEach
    fun seed() {
        transaction {
            GameMarketplace.deleteAll()
            CardActivationNumbers.deleteAll()
            Games.deleteAll()
            Users.deleteAll()
            Cards.deleteAll()
        }

        transaction {

            fun insertCard(
                type: CardType,
                cost: Int,
                income: Int,
                activationNumbers: List<Int>,
                color: CardColor,
                establishmentType: EstablishmentType,
                paymentSource: PaymentSource
            ) {
                val cardId = Cards.insertAndGetId {
                    it[cardType] = type
                    it[Cards.cost] = cost
                    it[Cards.income] = income
                    it[Cards.color] = color
                    it[Cards.establishmentType] = establishmentType
                    it[Cards.paymentSource] = paymentSource
                }

                activationNumbers.forEach { number ->
                    CardActivationNumbers.insert {
                        it[CardActivationNumbers.cardId] = cardId
                        it[CardActivationNumbers.number] = number
                    }
                }
            }

            insertCard(
                CardType.WHEAT_FIELD,
                cost = 1,
                income = 1,
                activationNumbers = listOf(1),
                establishmentType = EstablishmentType.WHEAT,
                color = CardColor.BLUE,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.RANCH,
                cost = 1,
                income = 1,
                activationNumbers = listOf(2),
                establishmentType = EstablishmentType.COW,
                color = CardColor.BLUE,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.FOREST,
                cost = 3,
                income = 1,
                activationNumbers = listOf(5),
                establishmentType = EstablishmentType.GEAR,
                color = CardColor.BLUE,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.MINE,
                cost = 6,
                income = 5,
                activationNumbers = listOf(9),
                establishmentType = EstablishmentType.GEAR,
                color = CardColor.BLUE,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.APPLE_ORCHARD,
                cost = 3,
                income = 3,
                activationNumbers = listOf(10),
                establishmentType = EstablishmentType.WHEAT,
                color = CardColor.BLUE,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.BAKERY,
                cost = 1,
                income = 1,
                activationNumbers = listOf(2, 3),
                establishmentType = EstablishmentType.BREAD,
                color = CardColor.GREEN,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.CONVENIENCE_STORE,
                cost = 2,
                income = 3,
                activationNumbers = listOf(4),
                establishmentType = EstablishmentType.BREAD,
                color = CardColor.GREEN,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.CHEESE_FACTORY,
                cost = 5,
                income = 3,
                activationNumbers = listOf(7),
                establishmentType = EstablishmentType.FACTORY,
                color = CardColor.GREEN,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.FURNITURE_FACTORY,
                cost = 3,
                income = 3,
                activationNumbers = listOf(8),
                establishmentType = EstablishmentType.FACTORY,
                color = CardColor.GREEN,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.FRUIT_AND_VEGETABLE_MARKET,
                cost = 2,
                income = 2,
                activationNumbers = listOf(11, 12),
                establishmentType = EstablishmentType.FRUIT,
                color = CardColor.GREEN,
                paymentSource = PaymentSource.BANK
            )

            insertCard(
                CardType.CAFE,
                cost = 2,
                income = 1,
                activationNumbers = listOf(3),
                establishmentType = EstablishmentType.CUP,
                color = CardColor.RED,
                paymentSource = PaymentSource.ACTIVE_PLAYER
            )

            insertCard(
                CardType.FAMILY_RESTAURANT,
                cost = 3,
                income = 2,
                activationNumbers = listOf(9, 10),
                establishmentType = EstablishmentType.CUP,
                color = CardColor.RED,
                paymentSource = PaymentSource.ACTIVE_PLAYER
            )

            insertCard(
                CardType.STADIUM,
                cost = 6,
                income = 2,
                activationNumbers = listOf(6),
                establishmentType = EstablishmentType.MAJOR,
                color = CardColor.PURPLE,
                paymentSource = PaymentSource.ALL_PLAYERS
            )

            insertCard(
                CardType.TV_STATION,
                cost = 7,
                income = 0,
                activationNumbers = listOf(6),
                establishmentType = EstablishmentType.MAJOR,
                color = CardColor.PURPLE,
                paymentSource = PaymentSource.CHOSEN_PLAYER
            )

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
    fun `findByGameIdAsMap returns empty map before init`() {
        assertTrue(marketplaceDao.findByGameIdAsMap(gameId).isEmpty())
    }

    @Test
    fun `findByGameIdAsMap returns cardType to quantity after init`() {
        marketplaceDao.initForGame(gameId, supplyPerCard = 4)
        val asMap = marketplaceDao.findByGameIdAsMap(gameId)
        assertEquals(CardType.entries.size, asMap.size)
        assertTrue(asMap.values.all { it == 4 })
        assertEquals(4, asMap[CardType.BAKERY])
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
    fun `isAvailable throws CardNotFoundException when card type does not exist in database`() {
        transaction { Cards.deleteAll() }
        assertThrows<CardNotFoundException> {
            marketplaceDao.isAvailable(gameId, CardType.BAKERY)
        }
    }

    @Test
    fun `decrementQuantity reduces available count by one`() {
        marketplaceDao.initForGame(gameId)
        marketplaceDao.decrementQuantity(gameId, CardType.BAKERY)
        assertEquals(5, marketplaceDao.findByGameIdAndType(gameId, CardType.BAKERY)!!.quantityAvailable)
    }

    @Test
    fun `decrementQuantity throws CardNotFoundException when card type does not exist in database`() {
        transaction { Cards.deleteAll() }
        assertThrows<CardNotFoundException> {
            marketplaceDao.decrementQuantity(gameId, CardType.BAKERY)
        }
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
    fun `updateQuantity throws CardNotFoundException when card type does not exist in database`() {
        transaction { Cards.deleteAll() }
        assertThrows<CardNotFoundException> {
            marketplaceDao.updateQuantity(gameId, CardType.WHEAT_FIELD, 5)
        }
    }

    @Test
    fun `delete removes specific card entry from marketplace`() {
        marketplaceDao.initForGame(gameId)
        marketplaceDao.delete(gameId, CardType.WHEAT_FIELD)
        assertNull(marketplaceDao.findByGameIdAndType(gameId, CardType.WHEAT_FIELD))
    }

    @Test
    fun `delete throws CardNotFoundException when card type does not exist in database`() {
        transaction { Cards.deleteAll() }
        assertThrows<CardNotFoundException> {
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