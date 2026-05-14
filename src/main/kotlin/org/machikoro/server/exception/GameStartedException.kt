package org.machikoro.server.exception

/**
 * Raised when a lobby join is attempted on a game that has already moved to
 * `IN_PROGRESS`. Carries the stable [errorCode] `GAME_STARTED` so the client
 * can distinguish this from other join-time failures.
 *
 * Kept as a concrete subclass (not a raw `CustomWebSocketException("GAME_STARTED", ...)`)
 * so `WebSocketController.addUser` can still catch it specifically for its
 * race-condition remap path without string-comparing error codes.
 */
class GameStartedException(message: String) : CustomWebSocketException(
    errorCode = "GAME_STARTED",
    message = message,
)
