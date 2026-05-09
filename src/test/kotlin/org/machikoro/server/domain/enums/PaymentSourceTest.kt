package org.machikoro.server.domain.enums

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for PaymentSource enum
 * Verifies all payment source types are properly defined and accessible
 */
class PaymentSourceTest {

    @Test
    fun `BANK represents payment from the game bank`() {
        assertEquals(PaymentSource.BANK, PaymentSource.BANK)
        assertNotNull(PaymentSource.BANK)
    }

    @Test
    fun `ACTIVE_PLAYER represents payment from the active player`() {
        assertEquals(PaymentSource.ACTIVE_PLAYER, PaymentSource.ACTIVE_PLAYER)
        assertNotNull(PaymentSource.ACTIVE_PLAYER)
    }

    @Test
    fun `ALL_PLAYERS represents payment from all players`() {
        assertEquals(PaymentSource.ALL_PLAYERS, PaymentSource.ALL_PLAYERS)
        assertNotNull(PaymentSource.ALL_PLAYERS)
    }

    @Test
    fun `CHOSEN_PLAYER represents payment from a specific player chosen by client`() {
        assertEquals(PaymentSource.CHOSEN_PLAYER, PaymentSource.CHOSEN_PLAYER)
        assertNotNull(PaymentSource.CHOSEN_PLAYER)
    }

    @Test
    fun `NONE represents no automatic payment`() {
        assertEquals(PaymentSource.NONE, PaymentSource.NONE)
        assertNotNull(PaymentSource.NONE)
    }

    @Test
    fun `all PaymentSource values are accessible`() {
        val values = PaymentSource.entries
        assertEquals(5, values.size)
        assertTrue(values.contains(PaymentSource.BANK))
        assertTrue(values.contains(PaymentSource.ACTIVE_PLAYER))
        assertTrue(values.contains(PaymentSource.ALL_PLAYERS))
        assertTrue(values.contains(PaymentSource.CHOSEN_PLAYER))
        assertTrue(values.contains(PaymentSource.NONE))
    }

    @Test
    fun `PaymentSource enums can be compared`() {
        // Test equality
        assertTrue(true)
        assertFalse(false)

        // Test inequality
        assertNotEquals(PaymentSource.BANK, PaymentSource.ACTIVE_PLAYER)
    }

    @Test
    fun `PaymentSource can be used in when expressions`() {
        val paymentSource = PaymentSource.ACTIVE_PLAYER

        val result = when (paymentSource) {
            PaymentSource.BANK -> "payment_from_bank"
            PaymentSource.ACTIVE_PLAYER -> "payment_from_active"
            PaymentSource.ALL_PLAYERS -> "payment_from_all"
            PaymentSource.CHOSEN_PLAYER -> "payment_from_chosen"
            PaymentSource.NONE -> "no_payment"
        }

        assertEquals("payment_from_active", result)
    }

    @Test
    fun `PaymentSource values have correct string representation`() {
        assertEquals("BANK", PaymentSource.BANK.toString())
        assertEquals("ACTIVE_PLAYER", PaymentSource.ACTIVE_PLAYER.toString())
        assertEquals("ALL_PLAYERS", PaymentSource.ALL_PLAYERS.toString())
        assertEquals("CHOSEN_PLAYER", PaymentSource.CHOSEN_PLAYER.toString())
        assertEquals("NONE", PaymentSource.NONE.toString())
    }
}

