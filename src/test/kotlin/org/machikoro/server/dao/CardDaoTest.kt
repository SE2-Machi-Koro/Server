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
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.enums.TriggerCondition

class CardDaoTest : AbstractDBSetup() {

    private val cardActivationNumbersDao = CardActivationNumbersDao()
    private val dao = CardDao(cardActivationNumbersDao)

    @BeforeEach
    fun seed() {
        transaction {
            // Robust cleanup before seeding to avoid Unique Constraint violations
            CardActivationNumbers.deleteAll()
            Cards.deleteAll()

            // 1. Wheat Field (Blue / Wheat symbol / Roll 1)
            val wheatId = Cards.insert {
                it[cardType] = CardType.WHEAT_FIELD
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.WHEAT
                it[triggerCondition] = TriggerCondition.ANY_TURN
                it[paymentSource] = PaymentSource.BANK
            }[Cards.id].value

            CardActivationNumbers.insert {
                it[cardId] = wheatId
                it[number] = 1
            }

            // 2. Bakery (Green / Bread symbol / Roll 2, 3)
            val bakeryId = Cards.insert {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.BREAD
                it[triggerCondition] = TriggerCondition.OWN_TURN
                it[paymentSource] = PaymentSource.BANK
            }[Cards.id].value

            CardActivationNumbers.insert { it[cardId] = bakeryId; it[number] = 2 }
            CardActivationNumbers.insert { it[cardId] = bakeryId; it[number] = 3 }

            // 3. Cafe (Red / Cup symbol / Roll 3)
            val cafeId = Cards.insert {
                it[cardType] = CardType.CAFE
                it[cost] = 2
                it[income] = 1
                it[establishmentType] = EstablishmentType.CUP
                it[triggerCondition] = TriggerCondition.OTHER_TURN
                it[paymentSource] = PaymentSource.ACTIVE_PLAYER
            }[Cards.id].value

            CardActivationNumbers.insert {
                it[cardId] = cafeId
                it[number] = 3
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
        assertEquals(3, dao.findAll().size)
    }

    @Test
    fun `findAllByCardTypes returns only requested types`() {
        val typesToFind = listOf(CardType.WHEAT_FIELD, CardType.CAFE)
        val results = dao.findAllByCardTypes(typesToFind)

        assertEquals(2, results.size)
        assertTrue(results.any { it.cardType == CardType.WHEAT_FIELD })
        assertTrue(results.any { it.cardType == CardType.CAFE })
        assertFalse(results.any { it.cardType == CardType.BAKERY })
    }

    @Test
    fun `findByEstablishmentType finds all cards with specific symbol`() {
        val wheatCards = dao.findByEstablishmentType(EstablishmentType.WHEAT)
        assertEquals(1, wheatCards.size)
        assertEquals(CardType.WHEAT_FIELD, wheatCards[0].cardType)

        val cupCards = dao.findByEstablishmentType(EstablishmentType.CUP)
        assertEquals(1, cupCards.size)
        assertEquals(CardType.CAFE, cupCards[0].cardType)
    }

    @Test
    fun `findByTriggerCondition groups cards by turn activation logic`() {
        val ownTurnCards = dao.findByTriggerCondition(TriggerCondition.OWN_TURN)
        assertEquals(1, ownTurnCards.size)
        assertEquals(CardType.BAKERY, ownTurnCards[0].cardType)

        val anyTurnCards = dao.findByTriggerCondition(TriggerCondition.ANY_TURN)
        assertEquals(1, anyTurnCards.size)
        assertEquals(CardType.WHEAT_FIELD, anyTurnCards[0].cardType)
    }

    @Test
    fun `findByActivationNumber returns multiple cards if multiple trigger on same roll`() {
        // Roll 3 triggers both Bakery (Green) and Cafe (Red)
        val cardsOn3 = dao.findByActivationNumber(3)

        assertEquals(2, cardsOn3.size)
        val types = cardsOn3.map { it.cardType }
        assertTrue(types.contains(CardType.BAKERY))
        assertTrue(types.contains(CardType.CAFE))
    }

    @Test
    fun `findByActivationNumber returns empty list for roll with no mappings`() {
        val results = dao.findByActivationNumber(12)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findByCardType returns fully hydrated model with activation numbers`() {
        val bakery = dao.findByCardType(CardType.BAKERY)

        assertNotNull(bakery)
        assertEquals(CardType.BAKERY, bakery!!.cardType)
        assertEquals(listOf(2, 3), bakery.activationNumbers)
        assertEquals(EstablishmentType.BREAD, bakery.establishmentType)
        assertEquals(PaymentSource.BANK, bakery.paymentSource)
    }
}