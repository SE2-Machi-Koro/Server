package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to roll dice during the active player's turn — sent to /app/game.rollDice")
data class RollDiceRequest(
    @Schema(description = "ID of the game", example = "1")
    val gameId: Int,
    @Schema(description = "ID of the rolling player", example = "42")
    val playerId: Int,
    @Schema(description = "Whether to roll two dice instead of one", example = "false")
    val rollTwoDice: Boolean = false,
)
