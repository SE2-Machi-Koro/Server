package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Payload sent by the lobby host to trigger the START_GAME event.
 *
 * @property gameId  ID of the game to start.
 *
 * Note: the requesting user ID is **not** included here — it is derived
 * server-side from the authenticated STOMP session via
 * [org.machikoro.server.service.WebSocketConnectionTracker.getUserId]
 * so that a malicious client cannot impersonate the host by forging the field.
 */
@Schema(description = "Request to start a game — must originate from the lobby host")
data class StartGameRequest(
    @Schema(description = "ID of the game to start", example = "1")
    val gameId: Int,
)

