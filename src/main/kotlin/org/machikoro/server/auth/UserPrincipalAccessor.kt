package org.machikoro.server.auth

import org.springframework.messaging.simp.SimpMessageHeaderAccessor

/**
 * Key under which [StompAuthChannelInterceptor] persists the authenticated
 * [UserPrincipal] in the WebSocket session attributes at CONNECT time.
 *
 * Why session attributes, not [SimpMessageHeaderAccessor.user]:
 * Spring's `accessor.user` only propagates from CONNECT to subsequent frames
 * when either (a) `WebSocketSession.principal` was set during the HTTP
 * handshake or (b) Spring Security's WebSocket integration is wired in. We
 * authenticate via a token carried in the STOMP CONNECT body, which neither
 * mechanism sees, so `accessor.user` dies after the CONNECT frame. Spring DOES
 * propagate session attributes across all frames on the same session, so we
 * use that channel as the durable source of truth.
 */
const val USER_PRINCIPAL_KEY = "userPrincipal"

/**
 * Resolve the authenticated [UserPrincipal] for a STOMP message. Prefers
 * [SimpMessageHeaderAccessor.user] (set by Spring on CONNECT) and falls back
 * to the session attribute populated by [StompAuthChannelInterceptor]. Returns
 * `null` if the session was never authenticated.
 */
fun SimpMessageHeaderAccessor.userPrincipal(): UserPrincipal? =
    user as? UserPrincipal
        ?: sessionAttributes?.get(USER_PRINCIPAL_KEY) as? UserPrincipal
