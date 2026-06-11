package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Accuse another player of cheating — sent to /app/game.accuse")
data class AccuseRequest(
    @Schema(description = "ID of the game", example = "1")
    val gameId: Int,
    @Schema(description = "PlayerModel.id of the accused player", example = "2")
    val accusedPlayerId: Int,
)
