package org.machikoro.server.database

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.PaymentSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext

class DataSeederTest : AbstractDBSetup() {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var cardDao: CardDao

    private val landmarkDao = LandmarkDao()

    @Test
    fun `DataSeeder bean is present in context`() {
        val beans = applicationContext.getBeansOfType(DataSeeder::class.java)
        assertTrue(beans.isNotEmpty(), "DataSeeder should be registered as a Spring bean")
    }

    @Test
    fun `run is idempotent`() {
        val seeder = applicationContext.getBean(DataSeeder::class.java)
        // insertIgnore means repeated runs must never throw
        assertDoesNotThrow {
            seeder.run()
            seeder.run()
        }
    }

    @Test
    fun `all 15 establishment cards are seeded`() {
        assertEquals(15, cardDao.findAll().size)
    }

    @Test
    fun `all 4 landmarks are seeded`() {
        assertEquals(4, landmarkDao.findAll().size)
    }

    @Test
    fun `all landmark types are present`() {
        val seeded = landmarkDao.findAll().map { it.landmarkType }.toSet()
        val expected = LandmarkType.entries.toSet()
        assertEquals(expected, seeded)
    }

    @Test
    fun `landmark costs are correct`() {
        val expected = mapOf(
            LandmarkType.TRAIN_STATION to 4,
            LandmarkType.SHOPPING_MALL to 10,
            LandmarkType.AMUSEMENT_PARK to 16,
            LandmarkType.RADIO_TOWER to 22,
        )
        expected.forEach { (type, cost) ->
            val landmark = landmarkDao.findByLandmarkType(type)
            assertNotNull(landmark, "$type should be seeded")
            assertEquals(cost, landmark!!.cost, "$type cost mismatch")
        }
    }

    @Test
    fun `wheat field card has correct properties`() {
        val card = cardDao.findByCardType(CardType.WHEAT_FIELD)
        assertNotNull(card)
        assertEquals(1, card!!.cost)
        assertEquals(1, card.income)
        assertEquals(CardColor.BLUE, card.color)
        assertEquals(EstablishmentType.WHEAT, card.establishmentType)
        assertEquals(PaymentSource.BANK, card.paymentSource)
    }

    @Test
    fun `bakery activates on rolls 2 and 3`() {
        val card = cardDao.findByCardType(CardType.BAKERY)
        assertNotNull(card)
        assertEquals(listOf(2, 3), card!!.activationNumbers.sorted())
    }

    @Test
    fun `fruit and vegetable market activates on rolls 11 and 12`() {
        val card = cardDao.findByCardType(CardType.FRUIT_AND_VEGETABLE_MARKET)
        assertNotNull(card)
        assertEquals(listOf(11, 12), card!!.activationNumbers.sorted())
    }

    @Test
    fun `mine has highest blue card income`() {
        val card = cardDao.findByCardType(CardType.MINE)
        assertNotNull(card)
        assertEquals(5, card!!.income)
        assertEquals(CardColor.BLUE, card.color)
    }

    @Test
    fun `purple cards are stadium and tv station`() {
        val purpleCards = cardDao.findByColor(CardColor.PURPLE).map { it.cardType }.toSet()
        assertEquals(
            setOf(CardType.STADIUM, CardType.TV_STATION),
            purpleCards
        )
    }

    @Test
    fun `red cards take payment from active player`() {
        val redCards = cardDao.findByColor(CardColor.RED)
        assertTrue(redCards.isNotEmpty())
        assertTrue(redCards.all { it.paymentSource == PaymentSource.ACTIVE_PLAYER })
    }
}
