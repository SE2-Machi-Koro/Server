package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to roll dice during the active player's turn — sent to /app/game.rollDice")
data class RollDiceRequest(
    @Schema(description = "ID of the game", example = "1")
    val gameId: Int,
    @Schema(description = "Legacy client-supplied player ID. The server derives the rolling player from the authenticated active turn.")
    val playerId: Int? = null,
    @Schema(description = "Whether to roll two dice instead of one", example = "false")
    val rollTwoDice: Boolean = false,
    @Schema(description = "Legacy client dice count, where 2 means roll two dice")
    val diceCount: Int? = null,
    @Schema(description = "Wrapped WebSocket payload used by older clients")
    val payload: RollDicePayload? = null,
)

@Schema(description = "Nested roll-dice payload accepted for backward-compatible WebSocket clients")
data class RollDicePayload(
    @Schema(description = "ID of the game", example = "1")
    val gameId: Int? = null,
    @Schema(description = "Legacy client dice count, where 2 means roll two dice")
    val diceCount: Int? = null,
    @Schema(description = "Whether to roll two dice instead of one", example = "false")
    val rollTwoDice: Boolean = false,
)
