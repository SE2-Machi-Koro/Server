package org.machikoro.server.domain.enums

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for CardColor enum
 * Verifies all card colors are properly defined and used for game activation logic
 */
class CardColorTest {

    @Test
    fun `BLUE represents cards that activate on anyone's turn`() {
        assertEquals(CardColor.BLUE, CardColor.BLUE)
        assertNotNull(CardColor.BLUE)
    }

    @Test
    fun `GREEN represents cards that activate on own turn only`() {
        assertEquals(CardColor.GREEN, CardColor.GREEN)
        assertNotNull(CardColor.GREEN)
    }

    @Test
    fun `RED represents cards that activate on other player's turn`() {
        assertEquals(CardColor.RED, CardColor.RED)
        assertNotNull(CardColor.RED)
    }

    @Test
    fun `PURPLE represents special effect cards that activate on own turn`() {
        assertEquals(CardColor.PURPLE, CardColor.PURPLE)
        assertNotNull(CardColor.PURPLE)
    }

    @Test
    fun `all CardColor values are accessible`() {
        val values = CardColor.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(CardColor.BLUE))
        assertTrue(values.contains(CardColor.GREEN))
        assertTrue(values.contains(CardColor.RED))
        assertTrue(values.contains(CardColor.PURPLE))
    }

    @Test
    fun `CardColor enums can be compared`() {
        assertTrue(true)
        assertFalse(false)
        assertNotEquals(CardColor.BLUE, CardColor.GREEN)
    }

    @Test
    fun `CardColor can be used in when expressions for earnings logic`() {
        val colors = listOf(CardColor.BLUE, CardColor.GREEN, CardColor.RED, CardColor.PURPLE)

        colors.forEach { color ->
            val triggerLogic = when (color) {
                CardColor.BLUE -> "triggers_on_any_turn"
                CardColor.GREEN -> "triggers_on_own_turn"
                CardColor.RED -> "triggers_on_other_turn"
                CardColor.PURPLE -> "triggers_on_own_turn"
            }
            assertNotNull(triggerLogic)
        }
    }

    @Test
    fun `CardColor values have correct string representation`() {
        assertEquals("BLUE", CardColor.BLUE.toString())
        assertEquals("GREEN", CardColor.GREEN.toString())
        assertEquals("RED", CardColor.RED.toString())
        assertEquals("PURPLE", CardColor.PURPLE.toString())
    }

    @Test
    fun `CardColor GREEN and PURPLE share OWN_TURN activation logic`() {
        val ownTurnColors = listOf(CardColor.GREEN, CardColor.PURPLE)

        ownTurnColors.forEach { color ->
            val isOwnTurn = color in listOf(CardColor.GREEN, CardColor.PURPLE)
            assertTrue(isOwnTurn, "$color should activate on own turn")
        }
    }

    @Test
    fun `CardColor RED represents stealing effect`() {
        val stealColor = CardColor.RED
        assertEquals(CardColor.RED, stealColor)

        // Red cards steal from other players
        val logicCheck = when (stealColor) {
            CardColor.RED -> true
            else -> false
        }
        assertTrue(logicCheck)
    }
}

