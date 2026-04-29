package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WinConditionServiceTest {

    private val playerDao = mock<PlayerDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameStateGuard = mock<GameStateGuard>()

    private val service = WinConditionService(playerDao, playerLandmarkDao, gameStateGuard)

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
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId)).thenReturn(emptyList())

        assertNull(service.detectWinner(gameId))
        verify(gameStateGuard).ensureGameIsRunning(gameId)
    }

    @Test
    fun `detectWinner returns null when no player has built all landmarks`() {
        val gameId = 1
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(player(1), player(2)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(false)

        assertNull(service.detectWinner(gameId))
        verify(gameStateGuard).ensureGameIsRunning(gameId)
    }

    @Test
    fun `detectWinner returns the winning player`() {
        val gameId = 1
        val winner = player(2)

        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(player(1), winner, player(3)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(true)

        assertEquals(winner, service.detectWinner(gameId))
        verify(gameStateGuard).ensureGameIsRunning(gameId)
    }

    @Test
    fun `detectWinner short-circuits and does not check players after the first winner`() {
        val gameId = 1

        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(TurnPhase.END_TURN))
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(player(1), player(2), player(3)))
        whenever(playerLandmarkDao.allBuilt(1)).thenReturn(false)
        whenever(playerLandmarkDao.allBuilt(2)).thenReturn(true)

        val result = service.detectWinner(gameId)

        assertEquals(2, result?.id)
        verify(playerLandmarkDao, never()).allBuilt(3)
        verify(gameStateGuard).ensureGameIsRunning(gameId)
    }

    @Test
    fun `detectWinner throws if not in END_TURN phase`() {
        val gameId = 1

        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(TurnPhase.BUY_OR_BUILD))

        val ex = assertThrows<CustomWebSocketException> {
            service.detectWinner(gameId)
        }

        assertEquals("NOT_END_TURN_PHASE", ex.errorCode)
        verify(gameStateGuard).ensureGameIsRunning(gameId)
    }
}

