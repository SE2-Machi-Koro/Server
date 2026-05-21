package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Type of WebSocket message")
enum class MessageType {
    CHAT,
    JOIN,
    LEAVE,
    LOBBY_CREATED,
    LOBBY_JOINED,
    HOST_LEFT,
    LOBBY_ROSTER,
    GAME_START,
    GAME_STARTED,
    SYNC,
    GAME_ACTION,
    GAME_END,
    LOBBY_LEFT,
    PLAYER_LEFT_FINISHED_GAME,
    ERROR,
    ROLL_DICE,
}

@Schema(description = "Message broadcast to all clients via WebSocket")
data class WebSocketMessage(
    @Schema(description = "Type of message", example = "GAME_ACTION")
    val type: MessageType,
    @Schema(description = "Who sent the message", example = "server")
    val sender: String,
    @Schema(description = "Optional text content", example = "Player joined")
    val content: String? = null,
    @Schema(description = "Optional structured payload depending on message type")
    val payload: Any? = null,
    @Schema(description = "Unix timestamp in milliseconds", example = "1714000000000")
    val timestamp: Long = System.currentTimeMillis(),
    @Schema(description = "ID of the game this message belongs to", example = "1")
    val gameId: Int? = null
)