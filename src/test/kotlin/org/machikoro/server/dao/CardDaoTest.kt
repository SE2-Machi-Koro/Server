package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Cards
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.TriggerCondition
import org.machikoro.server.domain.utils.displayName

class CardDaoTest : AbstractDBSetup() {

    private val cardActivationNumbersDao = CardActivationNumbersDao()
    private val dao = CardDao(cardActivationNumbersDao)

    @BeforeEach
    fun seed() {
        transaction {
            // Insert Wheat Field card
            val wheatId = Cards.insert {
                it[cardType] = CardType.WHEAT_FIELD
                it[cost] = 1
                it[income] = 1
                it[triggerCondition] = TriggerCondition.ANY_TURN
            }[Cards.id].value

            // Add activation numbers for Wheat Field (triggers on [1])
            CardActivationNumbers.insert {
                it[cardId] = wheatId
                it[number] = 1
            }

            // Insert Bakery card
            val bakeryId = Cards.insert {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[triggerCondition] = TriggerCondition.OWN_TURN
            }[Cards.id].value

            // Add activation numbers for Bakery (triggers on [2, 3])
            CardActivationNumbers.insert {
                it[cardId] = bakeryId
                it[number] = 2
            }
            CardActivationNumbers.insert {
                it[cardId] = bakeryId
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
        assertEquals(2, dao.findAll().size)
    }

    @Test
    fun `findByCardType returns correct card`() {
        val card = dao.findByCardType(CardType.WHEAT_FIELD)
        assertNotNull(card)
        assertEquals("Wheat Field", CardType.WHEAT_FIELD.displayName())
        assertEquals(1, card!!.cost)
        assertEquals(1, card.income)
        assertEquals(TriggerCondition.ANY_TURN, card.triggerCondition)
        assertEquals(listOf(1), card.activationNumbers)
    }

    @Test
    fun `findByCardType returns activation numbers`() {
        val card = dao.findByCardType(CardType.BAKERY)
        assertNotNull(card)
        assertEquals(listOf(2, 3), card!!.activationNumbers)
    }

    @Test
    fun `findByCardType returns null for unseeded card`() {
        assertNull(dao.findByCardType(CardType.CAFE))
    }

    @Test
    fun `findByActivationNumber returns cards that activate on that number`() {
        val cards = dao.findByActivationNumber(2)
        assertEquals(1, cards.size)
        assertEquals(CardType.BAKERY, cards[0].cardType)
    }

    @Test
    fun `findByActivationNumber returns multiple cards on same number`() {
        // Wheat Field also triggers on 1, Bakery on 2 and 3
        val cardsOn1 = dao.findByActivationNumber(1)
        assertEquals(1, cardsOn1.size)

        val cardsOn2 = dao.findByActivationNumber(2)
        assertEquals(1, cardsOn2.size)
    }
}