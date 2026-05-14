package org.machikoro.server.exception

/**
 * Raised when a service looks up a game id (or lobby code) that doesn't exist.
 * Carries the stable [errorCode] `GAME_NOT_FOUND`.
 *
 * Used by both WS and REST callers — for the REST path (`LobbyController`),
 * the catch-all `Exception` handler returns HTTP 400 with the message, so
 * the inherited `CustomWebSocketException` machinery doesn't change the
 * REST contract.
 */
class GameNotFoundException(message: String) : CustomWebSocketException(
    errorCode = "GAME_NOT_FOUND",
    message = message,
)
