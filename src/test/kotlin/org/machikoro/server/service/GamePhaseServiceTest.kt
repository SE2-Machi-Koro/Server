package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.GameDao
import org.machikoro.server.domain.enums.TurnPhase
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GamePhaseServiceTest {

    private val gameDao = mock<GameDao>()
    private val service = GamePhaseService(gameDao)

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
        whenever(gameDao.getPhase(gameId)).thenReturn(TurnPhase.ROLL_DICE)

        val result = service.advancePhase(gameId)

        assertEquals(TurnPhase.RESOLVE_EFFECTS, result)
        verify(gameDao).updateTurnPhase(gameId, TurnPhase.RESOLVE_EFFECTS)
    }

    @Test
    fun `advancePhase wraps END_TURN back to ROLL_DICE`() {
        val gameId = 7
        whenever(gameDao.getPhase(gameId)).thenReturn(TurnPhase.END_TURN)

        val result = service.advancePhase(gameId)

        assertEquals(TurnPhase.ROLL_DICE, result)
        verify(gameDao).updateTurnPhase(gameId, TurnPhase.ROLL_DICE)
    }
}
