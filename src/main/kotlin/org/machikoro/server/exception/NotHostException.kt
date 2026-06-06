package org.machikoro.server.exception

/**
 * Thrown when a non-host user attempts an action that is restricted to the lobby host.
 * Carries the stable [errorCode] `NOT_HOST` for WebSocket clients.
 */
class NotHostException(message: String) : CustomWebSocketException(
    errorCode = "NOT_HOST",
    message = message,
)
