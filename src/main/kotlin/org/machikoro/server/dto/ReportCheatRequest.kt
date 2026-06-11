package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Silent self-report that the active player used the Insider Trading cheat — sent to /app/game.reportCheat")
data class ReportCheatRequest(
    @Schema(description = "ID of the game the cheat was used in", example = "1")
    val gameId: Int,
)
