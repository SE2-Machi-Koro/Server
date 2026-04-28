package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to resolve card effects after a dice roll — sent to /app/game.resolveEffects")
data class ResolveEffectsRequest(
    @Schema(description = "ID of the game to resolve effects for", example = "1")
    val gameId: Int
)