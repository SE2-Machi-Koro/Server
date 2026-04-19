package org.machikoro.server.service

import org.machikoro.server.domain.enums.TurnPhase
import org.springframework.stereotype.Service

@Service
class GamePhaseService {

    /** Returns the next phase in the Machi Koro turn cycle. */
    fun nextPhase(currentPhase: TurnPhase): TurnPhase = when (currentPhase) {
        TurnPhase.ROLL_DICE -> TurnPhase.RESOLVE_EFFECTS
        TurnPhase.RESOLVE_EFFECTS -> TurnPhase.BUY_OR_BUILD
        TurnPhase.BUY_OR_BUILD -> TurnPhase.END_TURN
        TurnPhase.END_TURN -> TurnPhase.ROLL_DICE
    }

    /** Returns the phase that begins every new turn. */
    fun initialPhase(): TurnPhase = TurnPhase.ROLL_DICE
}
