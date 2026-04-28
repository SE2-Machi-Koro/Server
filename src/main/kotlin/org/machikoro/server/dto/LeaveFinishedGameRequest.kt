package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to leave a finished game — sent to /app/game.leaveFinishedGame")
data class LeaveFinishedGameRequest(
    @Schema(description = "ID of the game to leave", example = "1")
    val gameId: Int,
    @Schema(description = "ID of the player leaving", example = "42")
    val playerId: Int
)
