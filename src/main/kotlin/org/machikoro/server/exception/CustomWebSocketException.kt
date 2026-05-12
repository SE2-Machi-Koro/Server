package org.machikoro.server.exception

/**
 * Application-level exception for handled WebSocket/STOMP message failures.
 *
 * Open so that domain-specific exceptions (e.g. [GameNotFoundException],
 * [GameStartedException]) can extend it and inherit the routing through
 * `GlobalWebSocketExceptionHandler` while still being catchable by their
 * specific type — see [GameStartedException]'s race-condition catch in
 * `WebSocketController.addUser` for an example of why the type-safe catch
 * matters.
 *
 * @property errorCode stable functional code consumed by clients.
 */
open class CustomWebSocketException(
    val errorCode: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
