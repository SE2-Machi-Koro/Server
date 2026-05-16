package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A single entry in the global leaderboard representing one player's lifetime stats")
data class LeaderboardEntryDto(
    @Schema(description = "Position on the leaderboard, starting at 1", example = "1")
    val rank: Int,
    @Schema(description = "Unique ID of the player", example = "42")
    val userId: Int,
    @Schema(description = "Display name of the player", example = "alice")
    val username: String,
    @Schema(description = "Total number of games the player has won", example = "17")
    val totalWins: Int,
    @Schema(description = "Total number of games the player has participated in", example = "30")
    val totalGamesPlayed: Int,
)