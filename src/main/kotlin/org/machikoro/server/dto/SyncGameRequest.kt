package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Optional game context for an explicit reconnect sync request")
data class SyncGameRequest(
    @Schema(
        description = "Game ID to sync. If omitted, server resolves the caller's active IN_PROGRESS game.",
        example = "1"
    )
    val gameId: Int? = null,
)

