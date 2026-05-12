package org.machikoro.server.exception

/**
 * Raised when a lobby join is attempted on a game that has already reached
 * its `maxPlayers` cap. Carries the stable [errorCode] `LOBBY_FULL`.
 */
class LobbyFullException(message: String) : CustomWebSocketException(
    errorCode = "LOBBY_FULL",
    message = message,
)
