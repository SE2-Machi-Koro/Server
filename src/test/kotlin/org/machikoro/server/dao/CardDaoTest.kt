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
import org.machikoro.server.domain.enums.CardType

class CardDaoTest : AbstractDBSetup() {

    private val dao = CardDao()

    @BeforeEach
    fun seed() {
        transaction {
            Cards.insert {
                it[cardType] = CardType.WHEAT_FIELD
                it[name] = "Wheat Field"
                it[cost] = 1
                it[diceMin] = 1
                it[diceMax] = 1
                it[income] = 1
            }
            Cards.insert {
                it[cardType] = CardType.BAKERY
                it[name] = "Bakery"
                it[cost] = 1
                it[diceMin] = 2
                it[diceMax] = 3
                it[income] = 1
            }
        }
    }

    @AfterEach
    fun cleanup() {
        transaction { Cards.deleteAll() }
    }

    @Test
    fun `findAll returns all seeded cards`() {
        assertEquals(2, dao.findAll().size)
    }

    @Test
    fun `findByCardType returns correct card`() {
        val card = dao.findByCardType(CardType.WHEAT_FIELD)
        assertNotNull(card)
        assertEquals("Wheat Field", card!!.name)
        assertEquals(1, card.cost)
        assertEquals(1, card.diceMin)
        assertEquals(1, card.diceMax)
        assertEquals(1, card.income)
    }

    @Test
    fun `findByCardType returns null for unseeded card`() {
        assertNull(dao.findByCardType(CardType.CAFE))
    }
}