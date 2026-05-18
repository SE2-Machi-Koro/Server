package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to fill an existing lobby with dummy players")
data class FillLobbyRequest(
    @Schema(description = "Lobby code of the game to fill", example = "ABC123")
    val lobbyCode: String,
)