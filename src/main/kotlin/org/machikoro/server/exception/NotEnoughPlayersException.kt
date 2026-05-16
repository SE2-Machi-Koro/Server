package org.machikoro.server.exception

/**
 * Raised when startGame is called with fewer than the minimum required players.
 * Carries the stable [errorCode] `NOT_ENOUGH_PLAYERS`.
 */
class NotEnoughPlayersException(message: String) : CustomWebSocketException(
    errorCode = "NOT_ENOUGH_PLAYERS",
    message = message,
)