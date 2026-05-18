package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Result of seeding a debug game — includes a started game and credentials for all dummy players")
data class DebugSeedResponse(
    @Schema(description = "Full game state for the freshly started debug game")
    val gameState: GameStateDto,
    @Schema(description = "Credentials for each dummy player in join order (index 0 is the host)")
    val players: List<LoginResponse>,
)