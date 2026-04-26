package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class GamePhaseServiceTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val gameStateGuard = mock<GameStateGuard>()
    private val service = GamePhaseService(gameDao, playerDao, gameStateGuard)

    private fun gameInPhase(id: Int, phase: TurnPhase, currentTurnIndex: Int = 0, roundNumber: Int = 1) =
        GameModel(
            id = id,
            status = GameStatus.IN_PROGRESS,
            hostUserId = 1,
            lobbyCode = "ABC1234",
            maxPlayers = 4,
            currentTurnIndex = currentTurnIndex,
            turnPhase = phase,
            lastDiceRoll = null,
            roundNumber = roundNumber,
        )

    @Test
    fun `initial phase is ROLL_DICE`() {
        assertEquals(TurnPhase.ROLL_DICE, service.initialPhase())
    }

    @Test
    fun `ROLL_DICE advances to RESOLVE_EFFECTS`() {
        assertEquals(TurnPhase.RESOLVE_EFFECTS, service.nextPhase(TurnPhase.ROLL_DICE))
    }

    @Test
    fun `RESOLVE_EFFECTS advances to BUY_OR_BUILD`() {
        assertEquals(TurnPhase.BUY_OR_BUILD, service.nextPhase(TurnPhase.RESOLVE_EFFECTS))
    }

    @Test
    fun `BUY_OR_BUILD advances to END_TURN`() {
        assertEquals(TurnPhase.END_TURN, service.nextPhase(TurnPhase.BUY_OR_BUILD))
    }

    @Test
    fun `END_TURN cycles back to ROLL_DICE`() {
        assertEquals(TurnPhase.ROLL_DICE, service.nextPhase(TurnPhase.END_TURN))
    }

    @Test
    fun `nextPhase handles every TurnPhase value`() {
        TurnPhase.entries.forEach { phase ->
            service.nextPhase(phase)
        }
    }

    @Test
    fun `full cycle completes correctly`() {
        var phase = service.initialPhase()
        assertEquals(TurnPhase.ROLL_DICE, phase)

        phase = service.nextPhase(phase)
        assertEquals(TurnPhase.RESOLVE_EFFECTS, phase)

        phase = service.nextPhase(phase)
        assertEquals(TurnPhase.BUY_OR_BUILD, phase)

        phase = service.nextPhase(phase)
        assertEquals(TurnPhase.END_TURN, phase)

        phase = service.nextPhase(phase)
        assertEquals(TurnPhase.ROLL_DICE, phase)
    }

    @Test
    fun `advancePhase reads current phase and persists the next one`() {
        val gameId = 42
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.ROLL_DICE))

        val result = service.advancePhase(gameId)

        assertEquals(TurnPhase.RESOLVE_EFFECTS, result)
        verify(gameStateGuard).ensureGameIsRunning(gameId)
        verify(gameDao).updateTurnPhase(gameId, TurnPhase.RESOLVE_EFFECTS)
    }

    @Test
    fun `advancePhase wraps END_TURN back to ROLL_DICE`() {
        val gameId = 7
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.END_TURN))

        val result = service.advancePhase(gameId)

        assertEquals(TurnPhase.ROLL_DICE, result)
        verify(gameDao).updateTurnPhase(gameId, TurnPhase.ROLL_DICE)
    }

    @Test
    fun `advancePhase rejects FINISHED games and does not persist`() {
        val gameId = 99
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenThrow(CustomWebSocketException("GAME_FINISHED", "Game $gameId has already ended"))

        val ex = assertThrows<CustomWebSocketException> {
            service.advancePhase(gameId)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)
        verify(gameDao, never()).updateTurnPhase(any(), any())
    }

    @Test
    fun `endTurn updates phase and rotates to next player`() {
        val gameId = 21
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.BUY_OR_BUILD, currentTurnIndex = 0, roundNumber = 1))
        whenever(playerDao.findByGameId(gameId)).thenReturn(
            listOf(
                PlayerModel(1, gameId, 10, 0, 3),
                PlayerModel(2, gameId, 11, 1, 3),
            )
        )

        val result = service.endTurn(gameId)

        assertEquals(TurnPhase.ROLL_DICE, result)
        val ordered = inOrder(gameStateGuard, gameDao)
        ordered.verify(gameStateGuard).ensureGameIsRunning(gameId)
        ordered.verify(gameDao).updateTurnPhase(gameId, TurnPhase.END_TURN)
        ordered.verify(gameDao).advanceTurn(gameId, 1, 1)
    }

    @Test
    fun `endTurn wraps back to first player and increments round`() {
        val gameId = 22
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.BUY_OR_BUILD, currentTurnIndex = 1, roundNumber = 3))
        whenever(playerDao.findByGameId(gameId)).thenReturn(
            listOf(
                PlayerModel(1, gameId, 10, 0, 3),
                PlayerModel(2, gameId, 11, 1, 3),
            )
        )

        val result = service.endTurn(gameId)

        assertEquals(TurnPhase.ROLL_DICE, result)
        verify(gameDao).updateTurnPhase(gameId, TurnPhase.END_TURN)
        verify(gameDao).advanceTurn(gameId, 0, 4)
    }

    @Test
    fun `endTurn rejects games outside buy or build phase`() {
        val gameId = 23
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.RESOLVE_EFFECTS, currentTurnIndex = 0, roundNumber = 2))

        assertThrows<IllegalStateException> {
            service.endTurn(gameId)
        }

        verify(gameDao, never()).updateTurnPhase(gameId, TurnPhase.END_TURN)
        verify(gameDao, never()).advanceTurn(any(), any(), any())
        verifyNoMoreInteractions(playerDao)
    }

    @Test
    fun `endTurn rejects FINISHED games and does not advance`() {
        val gameId = 88
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenThrow(CustomWebSocketException("GAME_FINISHED", "Game $gameId has already ended"))

        val ex = assertThrows<CustomWebSocketException> {
            service.endTurn(gameId)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)
        verify(gameDao, never()).updateTurnPhase(any(), any())
        verify(gameDao, never()).advanceTurn(any(), any(), any())
        verifyNoMoreInteractions(playerDao)
    }
    @Test
    fun `finishGame updates game status to FINISHED`() {
        val gameId = 123

        service.finishGame(gameId)

        verify(gameDao).updateStatus(gameId, GameStatus.FINISHED)
    }
}
