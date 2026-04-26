package org.machikoro.server.service

import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
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
    fun `leaveGame removes player`() {
        val gameId = 1
        val playerId = 10

        whenever(gameStateGuard.ensureGameIsFinished(gameId))
            .thenReturn(mock())

        whenever(playerDao.findById(playerId))
            .thenReturn(PlayerModel(playerId, gameId, 1, 0, 3))

        whenever(playerDao.findByGameId(gameId))
            .thenReturn(listOf()) // empty after delete

        service.leaveGame(gameId, playerId)

        verify(playerDao).delete(playerId)
    }

    @Test
    fun `leaveGame deletes game if no players left`() {
        val gameId = 1
        val playerId = 10

        whenever(gameDao.findById(gameId))
            .thenReturn(
                GameModel(
                    id = gameId,
                    status = GameStatus.FINISHED,
                    hostUserId = 1,
                    currentTurnIndex = 0,
                    turnPhase = TurnPhase.END_TURN,
                    lastDiceRoll = null,
                    roundNumber = 1
                )
            )

        whenever(playerDao.findById(playerId))
            .thenReturn(PlayerModel(playerId, gameId, 1, 0, 3))

        whenever(playerDao.findByGameId(gameId))
            .thenReturn(
                listOf(PlayerModel(playerId, gameId, 1, 0, 3)),
                emptyList()
            )

        service.leaveGame(gameId, playerId)

        verify(gameDao).delete(gameId)
    }

    @Test
    fun `leaveGame does not delete game if players remain`() {
        val gameId = 1
        val playerId = 10

        whenever(gameStateGuard.ensureGameIsFinished(gameId))
            .thenReturn(mock())

        whenever(playerDao.findById(playerId))
            .thenReturn(PlayerModel(playerId, gameId, 1, 0, 3))

        whenever(playerDao.findByGameId(gameId))
            .thenReturn(listOf(
                PlayerModel(2,  gameId, 2, 1, 3)
            ))

        service.leaveGame(gameId, playerId)

        verify(gameDao, never()).delete(gameId)
    }

    @Test
    fun `leaveGame throws if player not found`() {
        val gameId = 1
        val playerId = 10

        whenever(gameStateGuard.ensureGameIsFinished(gameId))
            .thenReturn(mock())

        whenever(playerDao.findById(playerId))
            .thenReturn(null)

        assertThrows<IllegalStateException> {
            service.leaveGame(gameId, playerId)
        }

        verify(playerDao, never()).delete(playerId)
    }

    @Test
    fun `leaveGame calls gameStateGuard`() {
        val gameId = 1
        val playerId = 10

        whenever(gameStateGuard.ensureGameIsFinished(gameId))
            .thenReturn(mock())

        whenever(playerDao.findById(playerId))
            .thenReturn(PlayerModel(playerId, gameId, 1, 0, 3))

        whenever(playerDao.findByGameId(gameId))
            .thenReturn(emptyList())

        service.leaveGame(gameId, playerId)

        verify(gameStateGuard).ensureGameIsFinished(gameId)
    }
}