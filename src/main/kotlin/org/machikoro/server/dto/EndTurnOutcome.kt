package org.machikoro.server.dto

import org.machikoro.server.domain.enums.TurnPhase

sealed interface EndTurnOutcome {
    data class Continue(
        val nextPhase: TurnPhase,
    ) : EndTurnOutcome

    data class Won(
        val winnerId: Int,
    ) : EndTurnOutcome
}