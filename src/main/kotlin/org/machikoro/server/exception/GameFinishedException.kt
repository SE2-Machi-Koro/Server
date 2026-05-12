package org.machikoro.server.exception

/**
 * Raised when a lobby join is attempted on a game that has already moved to
 * `FINISHED`. Carries the stable [errorCode] `GAME_FINISHED` — the same code
 * `GameStateGuard.ensureGameIsRunning` raises for the equivalent in-game
 * condition, so the client can handle one signal across both lobby-join and
 * turn-action attempts.
 */
class GameFinishedException(message: String) : CustomWebSocketException(
    errorCode = "GAME_FINISHED",
    message = message,
)
