package org.machikoro.server.dto

enum class MessageType {
    CHAT,
    JOIN,
    LEAVE,
    GAME_START,
    GAME_ACTION,
    GAME_END,
    ERROR
}

data class WebSocketMessage(
    val type: MessageType,
    val sender: String,
    val content: String? = null,
    val payload: Any? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Standard error response for WebSocket operations.
 * Used to communicate errors back to connected clients.
 */
data class ErrorMessage(
    val code: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)
