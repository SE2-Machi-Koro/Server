package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test

class WinConditionServiceTest {

    private val playerDao = mock<PlayerDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameDao = mock<GameDao>()

    private val service = WinConditionService(playerDao, playerLandmarkDao, gameDao)

    private fun player(id: Int, gameId: Int = 1) =
        PlayerModel(id = id, gameId = gameId, userId = id * 10, turnOrder = id, coins = 3)

    private fun gameInPhase(phase: TurnPhase) =
        GameModel(
            id = 1,
            status = GameStatus.IN_PROGRESS,
            hostUserId = 1,
            lobbyCode = "ABC",
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = phase,
            lastDiceRoll = null,
            hasPurchasedThisTurn = false,
            roundNumber = 1
        )

    @Test
    fun `detectWinner returns null for a game with no players`() {
        val gameId = 1

        whenever(gameDao.findById(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(emptyList())

        val result = service.detectWinner(gameId)

        assertNull(result)
        verify(gameDao).findById(gameId)
        verify(playerDao).getPlayers(gameId)
    }

    @Test
    fun `detectWinner returns null when no player has won`() {
        val gameId = 1

        whenever(gameDao.findById(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(player(1), player(2)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(false)

        val result = service.detectWinner(gameId)

        assertNull(result)
    }

    @Test
    fun `detectWinner returns the winning player`() {
        val gameId = 1
        val winner = player(2)

        whenever(gameDao.findById(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(player(1), winner, player(3)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(true)

        val result = service.detectWinner(gameId)

        assertEquals(winner, result)
    }

    @Test
    fun `detectWinner short-circuits after first winner`() {
        val gameId = 1

        whenever(gameDao.findById(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(player(1), player(2), player(3)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(true)

        val result = service.detectWinner(gameId)

        assertEquals(2, result?.id)
        verify(playerLandmarkDao, never()).allBuilt(3)
    }

    @Test
    fun `detectWinner throws if not in END_TURN phase`() {
        val gameId = 1

        whenever(gameDao.findById(gameId))
            .thenReturn(gameInPhase(TurnPhase.BUY_OR_BUILD))

        val ex = assertThrows<CustomWebSocketException> {
            service.detectWinner(gameId)
        }

        assertEquals("NOT_END_TURN_PHASE", ex.errorCode)
        verify(gameDao).findById(gameId)
    }

    @Test
    fun `detectWinner throws if game is missing`() {
        val gameId = 1

        whenever(gameDao.findById(gameId)).thenReturn(null)

        val ex = assertThrows<GameNotFoundException> {
            service.detectWinner(gameId)
        }

        assertEquals("Game $gameId not found", ex.message)
    }
}