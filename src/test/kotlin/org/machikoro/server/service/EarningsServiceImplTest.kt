package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.mockito.kotlin.mock

class EarningsServiceImplTest {

    private val service = EarningsServiceImpl(
        playerDao = mock<PlayerDao>(),
        playerCardDao = mock<PlayerCardDao>(),
        cardDao = mock<CardDao>(),
        gameDao = mock<GameDao>(),
    )

    @Test
    fun `computeEarnings - empty list returns zero`() {
        assertEquals(0, service.computeEarnings(emptyList()))
    }

    @Test
    fun `computeEarnings - single card returns quantity times income`() {
        assertEquals(6, service.computeEarnings(listOf(2 to 3)))
    }

    @Test
    fun `computeEarnings - multiple card types sums correctly`() {
        assertEquals(11, service.computeEarnings(listOf(2 to 3, 1 to 5)))
    }

    @Test
    fun `computeEarnings - multiple quantities sums correctly`() {
        assertEquals(20, service.computeEarnings(listOf(3 to 4, 2 to 4)))
    }

    @Test
    fun `computeEarnings - high income and quantity`() {
        assertEquals(100, service.computeEarnings(listOf(10 to 10)))
    }

    @Test
    fun `computeEarnings - zero income card`() {
        assertEquals(0, service.computeEarnings(listOf(5 to 0)))
    }

    @Test
    fun `computeEarnings - single quantity one income`() {
        assertEquals(1, service.computeEarnings(listOf(1 to 1)))
    }

    @Test
    fun `computeEarnings - large multiplier`() {
        assertEquals(1000, service.computeEarnings(listOf(100 to 10)))
    }

    @Test
    fun `computeEarnings - zero quantity`() {
        assertEquals(0, service.computeEarnings(listOf(0 to 5)))
    }

    @Test
    fun `computeEarnings - many cards`() {
        val pairs = (1..20).map { it to 1 }
        assertEquals(210, service.computeEarnings(pairs))
    }

    @Test
    fun `computeEarnings - mixed high and low values`() {
        val pairs = listOf(1 to 1, 100 to 2, 5 to 3, 10 to 10)
        assertEquals(316, service.computeEarnings(pairs))
    }
}