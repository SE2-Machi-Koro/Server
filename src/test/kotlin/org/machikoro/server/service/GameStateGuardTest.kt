package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.GameDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GameStateGuardTest {

    private val gameDao = mock<GameDao>()
    private val guard = GameStateGuard(gameDao)

    private fun game(id: Int, status: GameStatus) = GameModel(
        id = id,
        status = status,
        hostUserId = 1,
        lobbyCode = "ABC1234",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        roundNumber = 1,
    )

    @Test
    fun `ensureGameIsRunning returns the loaded game when status is IN_PROGRESS`() {
        val gameId = 1
        val loaded = game(gameId, GameStatus.IN_PROGRESS)
        whenever(gameDao.findById(gameId)).thenReturn(loaded)

        assertSame(loaded, guard.ensureGameIsRunning(gameId))
    }

    @Test
    fun `ensureGameIsRunning returns the loaded game when status is WAITING`() {
        val gameId = 1
        val loaded = game(gameId, GameStatus.WAITING)
        whenever(gameDao.findById(gameId)).thenReturn(loaded)

        assertSame(loaded, guard.ensureGameIsRunning(gameId))
    }

    @Test
    fun `ensureGameIsRunning throws GAME_FINISHED when status is FINISHED`() {
        val gameId = 42
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.FINISHED))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureGameIsRunning(gameId)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)
    }

    @Test
    fun `ensureGameIsRunning throws GameNotFoundException when game does not exist`() {
        val gameId = 99
        whenever(gameDao.findById(gameId)).thenReturn(null)

        assertThrows(GameNotFoundException::class.java) {
            guard.ensureGameIsRunning(gameId)
        }
    }
}
