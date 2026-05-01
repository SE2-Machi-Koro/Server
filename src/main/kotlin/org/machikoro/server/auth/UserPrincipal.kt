package org.machikoro.server.auth

import java.security.Principal

/**
 * The authenticated WebSocket session identity.
 *
 * Attached to a STOMP session by [StompAuthChannelInterceptor] on a successful
 * CONNECT. Downstream `@MessageMapping` handlers can read it as a parameter of
 * type `java.security.Principal` or via `accessor.user`.
 */
data class UserPrincipal(
    val userId: Int,
    val username: String,
) : Principal {
    override fun getName(): String = username
}
