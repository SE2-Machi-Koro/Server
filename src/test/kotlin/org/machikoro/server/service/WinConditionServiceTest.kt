package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.models.PlayerModel
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WinConditionServiceTest {

    private val playerDao = mock<PlayerDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val service = WinConditionService(playerDao, playerLandmarkDao)

    private fun player(id: Int, gameId: Int = 1) =
        PlayerModel(id = id, gameId = gameId, userId = id * 10, turnOrder = id, coins = 3)

    @Test
    fun `hasPlayerWon returns true when all landmarks are built`() {
        val playerId = 42
        whenever(playerLandmarkDao.allBuilt(playerId)).thenReturn(true)

        assertTrue(service.hasPlayerWon(playerId))
    }

    @Test
    fun `hasPlayerWon returns false when not all landmarks are built`() {
        val playerId = 42
        whenever(playerLandmarkDao.allBuilt(playerId)).thenReturn(false)

        assertFalse(service.hasPlayerWon(playerId))
    }

    @Test
    fun `detectWinner returns null for a game with no players`() {
        val gameId = 1
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())

        assertNull(service.detectWinner(gameId))
    }

    @Test
    fun `detectWinner returns null when no player has built all landmarks`() {
        val gameId = 1
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(player(1), player(2)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(false)

        assertNull(service.detectWinner(gameId))
    }

    @Test
    fun `detectWinner returns the winning player`() {
        val gameId = 1
        val winner = player(2)
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(player(1), winner, player(3)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(true)

        assertEquals(winner, service.detectWinner(gameId))
    }

    @Test
    fun `detectWinner short-circuits and does not check players after the first winner`() {
        val gameId = 1
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(player(1), player(2), player(3)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(true)

        val result = service.detectWinner(gameId)

        assertEquals(2, result?.id)
        verify(playerLandmarkDao, never()).allBuilt(3)
    }
}
