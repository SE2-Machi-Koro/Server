package org.machikoro.server.exception

/**
 * Raised when an authenticated caller attempts a game action against a game
 * they are not a player in. Distinct from `NotAdminException` (auth/permission)
 * — this is a semantic precondition failure.
 */
class NotInGameException(message: String) : RuntimeException(message)
