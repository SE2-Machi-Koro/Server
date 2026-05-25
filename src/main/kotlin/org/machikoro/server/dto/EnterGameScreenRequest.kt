package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Sent by any player when navigating to the game screen.
 * The server uses this signal to auto-trigger initialization if the game is still WAITING.
 */
@Schema(description = "Signals that a player has navigated to the game screen")
data class EnterGameScreenRequest(
    @Schema(description = "ID of the game being entered", example = "1")
    val gameId: Int,
)