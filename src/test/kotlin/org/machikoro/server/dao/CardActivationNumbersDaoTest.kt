package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.PaymentSource
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class CardActivationNumbersDaoTest : AbstractDBSetup() {

    @Autowired
    private lateinit var cardActivationNumbersDao: CardActivationNumbersDao

    private var wheatFieldId: Int = 0
    private var bakeryId: Int = 0

    @BeforeEach
    fun setupData() {
        transaction {
            CardActivationNumbers.deleteAll()
            PlayerCards.deleteAll()
            Cards.deleteAll()
        }

        transaction {
            wheatFieldId = (Cards.insert {
                it[cardType] = CardType.WHEAT_FIELD
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.WHEAT
                it[color] = CardColor.BLUE
                it[paymentSource] = PaymentSource.BANK
            } get Cards.id).value

            bakeryId = (Cards.insert {
                it[cardType] = CardType.BAKERY
                it[cost] = 1
                it[income] = 1
                it[establishmentType] = EstablishmentType.BREAD
                it[color] = CardColor.GREEN
                it[paymentSource] = PaymentSource.BANK
            } get Cards.id).value

            CardActivationNumbers.insert {
                it[cardId] = wheatFieldId
                it[number] = 1
            }

            // Bakery: activates on 2 and 3
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

    @Test
    fun `findByCardId returns all activation numbers sorted`() {
        val bakeryNumbers = cardActivationNumbersDao.findByCardId(bakeryId)

        assertEquals(2, bakeryNumbers.size)
        assertEquals(listOf(2, 3), bakeryNumbers)
    }

    @Test
    fun `findByCardId returns empty list for non-existent card`() {
        val numbers = cardActivationNumbersDao.findByCardId(999)
        assertTrue(numbers.isEmpty())
    }

    @Test
    fun `findCardsByActivationNumber returns all card IDs for a roll`() {
        val cardsForRoll3 = cardActivationNumbersDao.findCardsByActivationNumber(3)

        assertEquals(1, cardsForRoll3.size)
        assertEquals(bakeryId, cardsForRoll3.first())
    }

    @Test
    fun `findCardsByActivationNumber returns multiple cards if they share a number`() {
        // Add another card that activates on 1 (like a Ranch)
        val ranchId = transaction {
            val id = (Cards.insert {
                it[cardType] = CardType.RANCH
                it[cost] = 1
                it[income] = 1
                it[color] = CardColor.BLUE
                it[establishmentType] = EstablishmentType.COW
                it[paymentSource] = PaymentSource.BANK
            } get Cards.id).value

            CardActivationNumbers.insert {
                it[cardId] = id
                it[number] = 1
            }
            id
        }

        val cardsForRoll1 = cardActivationNumbersDao.findCardsByActivationNumber(1)

        assertEquals(2, cardsForRoll1.size)
        assertTrue(cardsForRoll1.contains(wheatFieldId))
        assertTrue(cardsForRoll1.contains(ranchId))
    }

    @Test
    fun `findCardsByActivationNumber returns empty list for roll with no cards`() {
        // No cards activate on 12 in this setup
        val cards = cardActivationNumbersDao.findCardsByActivationNumber(12)
        assertTrue(cards.isEmpty())
    }
}