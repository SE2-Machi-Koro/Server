package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Payload sent by the lobby host to trigger the START_GAME event.
 *
 * @property gameId            ID of the game to start.
 * @property requestingUserId  User ID of the caller; must match the game's hostUserId.
 */
@Schema(description = "Request to start a game — must originate from the lobby host")
data class StartGameRequest(
    @Schema(description = "ID of the game to start", example = "1")
    val gameId: Int,

    @Schema(description = "User ID of the requester (must be the host)", example = "42")
    val requestingUserId: Int,
)

