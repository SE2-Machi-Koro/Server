package org.machikoro.server.service

import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.models.PlayerModel
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test

class LeaveFinishedGameServiceTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val gameStateGuard = mock<GameStateGuard>()

    private val service = LeaveFinishedGameService(gameDao, playerDao, gameStateGuard)

    @Test
    fun `leaveFinishedGame removes player`() {
        val gameId = 1
        val playerId = 10

        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(PlayerModel(playerId, gameId, 1, 0, 3)))

        whenever(playerDao.countByGameId(gameId))
            .thenReturn(0)

        service.leaveFinishedGame(gameId, playerId)

        verify(playerDao).delete(playerId)
    }

    @Test
    fun `leaveFinishedGame deletes game if no players left`() {
        val gameId = 1
        val playerId = 10

        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(PlayerModel(playerId, gameId, 1, 0, 3)))

        whenever(playerDao.countByGameId(gameId))
            .thenReturn(0)

        service.leaveFinishedGame(gameId, playerId)

        verify(gameDao).delete(gameId)
    }

    @Test
    fun `leaveFinishedGame does not delete game if players remain`() {
        val gameId = 1
        val playerId = 10

        whenever(playerDao.getPlayers(gameId))
            .thenReturn(
                listOf(
                    PlayerModel(playerId, gameId, 1, 0, 3),
                    PlayerModel(2, gameId, 2, 1, 3)
                )
            )

        whenever(playerDao.countByGameId(gameId))
            .thenReturn(1)

        service.leaveFinishedGame(gameId, playerId)

        verify(playerDao).delete(playerId)
        verify(gameDao, never()).delete(gameId)
    }

    @Test
    fun `leaveFinishedGame throws if player not in game`() {
        val gameId = 1
        val playerId = 10

        whenever(playerDao.getPlayers(gameId))
            .thenReturn(emptyList())

        assertThrows<IllegalArgumentException> {
            service.leaveFinishedGame(gameId, playerId)
        }

        verify(playerDao, never()).delete(playerId)
    }

    @Test
    fun `leaveFinishedGame calls gameStateGuard`() {
        val gameId = 1
        val playerId = 10

        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(PlayerModel(playerId, gameId, 1, 0, 3)))

        whenever(playerDao.countByGameId(gameId))
            .thenReturn(0)

        service.leaveFinishedGame(gameId, playerId)

        verify(gameStateGuard).ensureGameIsFinished(gameId)
    }
}