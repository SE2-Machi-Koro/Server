package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to end the current turn — sent to /app/game.endTurn")
data class EndTurnRequest(
    @Schema(description = "ID of the game to end the turn for", example = "1")
    val gameId: Int
)
