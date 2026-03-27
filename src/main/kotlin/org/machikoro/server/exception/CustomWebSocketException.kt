package org.machikoro.server.exception

/**
 * Application-level exception for handled WebSocket/STOMP message failures.
 *
 * @property errorCode stable functional code consumed by clients.
 */
class CustomWebSocketException(
    val errorCode: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
