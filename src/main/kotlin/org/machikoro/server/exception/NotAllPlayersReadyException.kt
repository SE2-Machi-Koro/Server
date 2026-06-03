package org.machikoro.server.exception

/**
 * Raised when startGame is called but at least one player has not toggled ready.
 */
class NotAllPlayersReadyException(message: String) : CustomWebSocketException(
    errorCode = "NOT_ALL_PLAYERS_READY",
    message = message,
)