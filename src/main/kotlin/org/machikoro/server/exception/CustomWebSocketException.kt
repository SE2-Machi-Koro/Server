package org.machikoro.server.exception

/**
 * Custom exception for WebSocket operations.
 * Used to communicate application-level errors through WebSocket channels.
 */
class CustomWebSocketException(
    val code: String = "WS_ERROR",
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

