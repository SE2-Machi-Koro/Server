package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to force-end a running game with the caller as winner (debug only)")
data class EndGameRequest(
    @Schema(description = "ID of the game to end", example = "1")
    val gameId: Int,
)
