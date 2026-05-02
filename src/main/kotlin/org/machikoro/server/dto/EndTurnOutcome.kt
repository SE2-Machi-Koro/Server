package org.machikoro.server.dto

import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel

sealed interface EndTurnOutcome {
    data class Continue(
        val nextPhase: TurnPhase,
    ) : EndTurnOutcome

    data class Won(
        val winner: PlayerModel
    ) : EndTurnOutcome
}