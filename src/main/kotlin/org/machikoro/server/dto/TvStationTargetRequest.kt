package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "Request to resolve a pending TV Station steal by choosing the opponent to take " +
        "5 coins from — sent to /app/game.chooseTvStationTarget while the game is in AWAIT_TV_TARGET."
)
data class TvStationTargetRequest(
    @Schema(description = "ID of the game with the pending TV Station steal", example = "1")
    val gameId: Int,
    @Schema(description = "ID of the opponent the active player steals 5 coins from", example = "2")
    val targetPlayerId: Int,
)
