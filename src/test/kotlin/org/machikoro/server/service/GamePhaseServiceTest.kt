package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.TurnPhase

class GamePhaseServiceTest {

    private val service = GamePhaseService()

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
}
