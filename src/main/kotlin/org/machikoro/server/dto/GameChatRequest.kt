package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Request to send a chat message in an active game")
data class GameChatRequest(
    @Schema(description = "ID of the target game", example = "1")
    val gameId: Int,
    @Schema(description = "Chat message text (max 300 characters)", example = "Good roll!")
    val message: String,
)