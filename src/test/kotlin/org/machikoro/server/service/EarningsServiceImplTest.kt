package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao

class EarningsServiceImplTest {

    private val service = EarningsServiceImpl(
        playerDao = mock(PlayerDao::class.java),
        playerCardDao = mock(PlayerCardDao::class.java),
        cardDao = mock(CardDao::class.java),
        gameDao = mock(GameDao::class.java)
    )

    @Test
    fun `zero cards returns zero`() {
        assertEquals(0, service.computeEarnings(emptyList()))
    }

    @Test
    fun `single card returns quantity times income`() {
        assertEquals(6, service.computeEarnings(listOf(2 to 3)))
    }

    @Test
    fun `multiple card types sums correctly`() {
        assertEquals(11, service.computeEarnings(listOf(2 to 3, 1 to 5)))
    }

    @Test
    fun `multiple quantities sums correctly`() {
        assertEquals(20, service.computeEarnings(listOf(3 to 4, 2 to 4)))
    }
}