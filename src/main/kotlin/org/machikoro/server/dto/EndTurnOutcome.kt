package org.machikoro.server.dto

import org.machikoro.server.domain.enums.TurnPhase
import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "Result of ending a turn",
    oneOf = [EndTurnOutcome.Continue::class, EndTurnOutcome.Won::class]
)
sealed interface EndTurnOutcome {

    @Schema(description = "Game continues to the next phase")
    data class Continue(
        @field:Schema(description = "Next phase of the turn, always initial - ROLL_DICE", example = "ROLL_DICE")
        val nextPhase: TurnPhase,
    ) : EndTurnOutcome

    @Schema(description = "Turn has ended with a winner")
    data class Won(
        @field:Schema(description = "ID of the winning player", example = "1")
        val winnerId: Int,
    ) : EndTurnOutcome
}