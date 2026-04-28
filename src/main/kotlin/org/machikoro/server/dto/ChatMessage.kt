package org.machikoro.server.dto

enum class MessageType {
    CHAT,
    JOIN,
    LEAVE,
    GAME_START,
    GAME_ACTION,
    GAME_END,
    PLAYER_LEFT_FINISHED_GAME,
    ERROR,
    ROLL_DICE
}

data class WebSocketMessage(
    val type: MessageType,
    val sender: String,
    val content: String? = null,
    val payload: Any? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val gameId: Int? = null
)