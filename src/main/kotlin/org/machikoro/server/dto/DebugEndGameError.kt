package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Structured 4xx error body returned by `POST /debug/end-game` when a request is
 * rejected (debug only). Mirrors the WebSocket layer's error shape — a stable
 * machine-readable [error] code plus a human-readable [message], never a raw null.
 */
@Schema(description = "Structured error returned by POST /debug/end-game when a request is rejected")
data class DebugEndGameError(
    @Schema(description = "Stable machine-readable error code", example = "NOT_IN_GAME")
    val error: String,
    @Schema(description = "Human-readable explanation of the rejection", example = "User alice is not a player in game 5")
    val message: String,
)
