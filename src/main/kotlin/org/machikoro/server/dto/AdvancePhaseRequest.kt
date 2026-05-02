package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to advance the current turn phase — sent to /app/game.advancePhase")
data class AdvancePhaseRequest(
    @Schema(description = "ID of the game to advance", example = "1")
    val gameId: Int
)
