package org.machikoro.server.domain.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType

class CardTypeMappingTest {

    @Test
    fun `validateAllCardsAreMapped should pass when all enum entries are in mapping`() {
        // If this throws IllegalStateException, a new CardType was added
        // to the enum but not to the CardTypeMapping object.
        val result = CardTypeMapping.validateAllCardsAreMapped()
        assertTrue(result)
    }

    @Test
    fun `getEstablishmentType returns correct symbol for specific cards`() {
        assertEquals(EstablishmentType.WHEAT, CardTypeMapping.getEstablishmentType(CardType.WHEAT_FIELD))
        assertEquals(EstablishmentType.BREAD, CardTypeMapping.getEstablishmentType(CardType.BAKERY))
        assertEquals(EstablishmentType.CUP, CardTypeMapping.getEstablishmentType(CardType.CAFE))
        assertEquals(EstablishmentType.MAJOR, CardTypeMapping.getEstablishmentType(CardType.STADIUM))
        assertEquals(EstablishmentType.FACTORY, CardTypeMapping.getEstablishmentType(CardType.CHEESE_FACTORY))
    }

    @Test
    fun `all mapped values should be non-null`() {
        CardType.entries.forEach { cardType ->
            assertNotNull(
                CardTypeMapping.getEstablishmentType(cardType),
                "CardType $cardType should have a valid EstablishmentType mapping"
            )
        }
    }

    @Test
    fun `verify specific category groupings`() {
        // Test that "Industrial" cards are correctly mapped to GEAR
        val gearCards = listOf(CardType.FOREST, CardType.MINE)
        gearCards.forEach { card ->
            assertEquals(EstablishmentType.GEAR, CardTypeMapping.getEstablishmentType(card))
        }

        // Test that "Food" cards are correctly mapped to CUP
        val cupCards = listOf(CardType.CAFE, CardType.FAMILY_RESTAURANT)
        cupCards.forEach { card ->
            assertEquals(EstablishmentType.CUP, CardTypeMapping.getEstablishmentType(card))
        }
    }
}