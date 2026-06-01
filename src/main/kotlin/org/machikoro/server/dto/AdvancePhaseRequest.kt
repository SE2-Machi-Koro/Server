package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Deprecated phase-advance request. /app/game.advancePhase always rejects gameplay use.")
data class AdvancePhaseRequest(
    @Schema(description = "ID of the game", example = "1")
    val gameId: Int
)
