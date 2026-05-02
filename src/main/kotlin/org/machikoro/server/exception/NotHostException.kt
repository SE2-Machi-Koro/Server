package org.machikoro.server.exception

/**
 * Thrown when a non-host user attempts an action that is restricted to the lobby host.
 */
class NotHostException(message: String) : RuntimeException(message)

