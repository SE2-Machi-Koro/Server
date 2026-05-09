package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.utils.displayName

class CardDaoTest : AbstractDBSetup() {

    private val cardActivationNumbersDao = CardActivationNumbersDao()
    private val dao = CardDao(cardActivationNumbersDao)

    @BeforeEach
    fun seed() {
        transaction {
            // Clean up to ensure a fresh state and avoid Unique Constraint violations
            CardActivationNumbers.deleteAll()
            Cards.deleteAll()

            // 1. Wheat Field (Blue card, triggers on 1)
            val wheatId = Cards.insert {
                it[cardType] = CardType.WHEAT_FIELD
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.WHEAT
                it[color] = CardColor.BLUE
                it[paymentSource] = PaymentSource.BANK
            }[Cards.id].value

            CardActivationNumbers.insert {
                it[cardId] = wheatId
                it[number] = 1
            }

            // 2. Bakery (Green card, triggers on 2 and 3)
            val bakeryId = Cards.insert {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.BREAD
                it[color] = CardColor.GREEN
                it[paymentSource] = PaymentSource.BANK
            }[Cards.id].value

            CardActivationNumbers.insert { it[cardId] = bakeryId; it[number] = 2 }
            CardActivationNumbers.insert { it[cardId] = bakeryId; it[number] = 3 }

            // 3. Cafe (Red card, triggers on 3)
            val cafeId = Cards.insert {
                it[cardType] = CardType.CAFE
                it[cost] = 2
                it[income] = 1
                it[establishmentType] = EstablishmentType.CUP
                it[color] = CardColor.RED
                it[paymentSource] = PaymentSource.ACTIVE_PLAYER
            }[Cards.id].value

            CardActivationNumbers.insert {
                it[cardId] = cafeId
                it[number] = 3
            }

            // 4. Stadium (Purple/Major card, triggers on 6)
            val stadiumId = Cards.insert {
                it[cardType] = CardType.STADIUM
                it[cost] = 6
                it[income] = 2
                it[establishmentType] = EstablishmentType.MAJOR
                it[color] = CardColor.PURPLE
                it[paymentSource] = PaymentSource.ALL_PLAYERS
            }[Cards.id].value

            CardActivationNumbers.insert {
                it[cardId] = stadiumId
                it[number] = 6
            }
        }
    }

    @AfterEach
    fun cleanup() {
        transaction {
            CardActivationNumbers.deleteAll()
            Cards.deleteAll()
        }
    }

    @Test
    fun `findAll returns all seeded cards`() {
        assertEquals(4, dao.findAll().size)
    }

    @Test
    fun `findAllByCardTypes returns only requested subset`() {
        val types = listOf(CardType.WHEAT_FIELD, CardType.STADIUM)
        val results = dao.findAllByCardTypes(types)

        assertEquals(2, results.size)
        assertTrue(results.any { it.cardType == CardType.WHEAT_FIELD })
        assertTrue(results.any { it.cardType == CardType.STADIUM })
    }

    @Test
    fun `findByCardType returns fully hydrated model`() {
        val card = dao.findByCardType(CardType.WHEAT_FIELD)

        assertNotNull(card)
        assertEquals("Wheat Field", CardType.WHEAT_FIELD.displayName())
        assertEquals(1, card!!.cost)
        assertEquals(1, card.income)
        assertEquals(CardColor.BLUE, card.color)
        assertEquals(EstablishmentType.WHEAT, card.establishmentType)
        assertEquals(listOf(1), card.activationNumbers)
    }

    @Test
    fun `findByCardType returns activation numbers for multi-number cards`() {
        val card = dao.findByCardType(CardType.BAKERY)
        assertNotNull(card)
        assertEquals(listOf(2, 3), card!!.activationNumbers)
    }

    @Test
    fun `findByCardType returns null for unseeded card`() {
        assertNull(dao.findByCardType(CardType.MINE))
    }

    @Test
    fun `findByActivationNumber returns multiple cards on same number`() {
        // Both Bakery (2,3) and Cafe (3) activate on 3
        val cardsOn3 = dao.findByActivationNumber(3)

        assertEquals(2, cardsOn3.size)
        val types = cardsOn3.map { it.cardType }
        assertTrue(types.contains(CardType.BAKERY))
        assertTrue(types.contains(CardType.CAFE))
    }

    @Test
    fun `findByEstablishmentType filters cards by symbol`() {
        val majorCards = dao.findByEstablishmentType(EstablishmentType.MAJOR)

        assertEquals(1, majorCards.size)
        assertEquals(CardType.STADIUM, majorCards[0].cardType)
    }

    @Test
    fun `findByColor filters cards by color`() {
        val redCards = dao.findByColor(CardColor.RED)

        assertEquals(1, redCards.size)
        assertEquals(CardType.CAFE, redCards[0].cardType)
    }

    @Test
    fun `findByActivationNumber returns empty list for roll with no mappings`() {
        val results = dao.findByActivationNumber(12)
        assertTrue(results.isEmpty())
    }
}