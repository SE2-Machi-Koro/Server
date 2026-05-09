package org.machikoro.server.auth

import org.machikoro.server.dao.UserDao
import org.machikoro.server.exception.InvalidSessionTokenException
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

/**
 * Validates the session token on every STOMP CONNECT frame and attaches a
 * [UserPrincipal] to the WS session so downstream `@MessageMapping` handlers
 * can read the authenticated identity via [userPrincipal].
 *
 * Connections without a valid token are rejected — no anonymous WebSocket
 * sessions. Non-CONNECT frames pass through untouched; they inherit the
 * principal attached at CONNECT time.
 *
 * Failure paths all throw [InvalidSessionTokenException] with the same
 * generic message ("Authentication failed") so the client cannot
 * distinguish "no header" from "invalid token" via the STOMP ERROR frame
 * body. Internal server logs keep the distinction at WARN level for
 * debugging.
 *
 * Why we persist into [SimpMessageHeaderAccessor.sessionAttributes] in
 * addition to [SimpMessageHeaderAccessor.user]: Spring's `accessor.user`
 * does not propagate from CONNECT to subsequent SEND frames in a setup that
 * authenticates via a token in the STOMP CONNECT body (no
 * `WebSocketSession.principal`, no Spring Security WebSocket integration).
 * Session attributes do propagate. See [USER_PRINCIPAL_KEY] for the full
 * reasoning.
 */
@Component
class StompAuthChannelInterceptor(
    private val userDao: UserDao,
) : ChannelInterceptor {
    private val logger = LoggerFactory.getLogger(StompAuthChannelInterceptor::class.java)

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*>? {
        val accessor = StompHeaderAccessor.wrap(message)
        if (accessor.command != StompCommand.CONNECT) return message

        val rawHeader = accessor.getFirstNativeHeader(AUTH_HEADER)
        // Strict: require the "Bearer " prefix. Some clients send the bare token,
        // but accepting both is a footgun — log greps and future header changes
        // become ambiguous. Mismatched format is treated as missing.
        val token = rawHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?.takeIf { it.isNotBlank() }

        if (token == null) {
            logger.warn("WS CONNECT rejected: missing or malformed Authorization header")
            throw InvalidSessionTokenException(GENERIC_AUTH_FAILURE)
        }

        val user = userDao.findBySessionToken(token)
        if (user == null) {
            // Don't log the token value — it could be valid for someone else
            // and even an invalid guess is information we shouldn't echo.
            logger.warn("WS CONNECT rejected: token not recognised")
            throw InvalidSessionTokenException(GENERIC_AUTH_FAILURE)
        }

        val principal = UserPrincipal(userId = user.id, username = user.username)

        accessor.user = principal
        // Session attributes are the durable propagation channel; warn loudly
        // if they're missing so the failure mode is visible rather than silent.
        val attrs = accessor.sessionAttributes
        if (attrs == null) {
            logger.warn(
                "WS CONNECT: sessionAttributes is null on session {} — principal will not propagate to SEND frames",
                accessor.sessionId,
            )
        } else {
            attrs[USER_PRINCIPAL_KEY] = principal
        }

        logger.info("WS CONNECT accepted for user '{}'", sanitizeForLog(user.username))
        // Re-build the message with the mutated accessor's headers so the
        // principal propagates to downstream handlers. The Spring docs example
        // sometimes returns the original message and relies on the message's
        // headers being mutably-wrapped by the STOMP transport — but recreating
        // that contract faithfully in unit tests is brittle, so we always
        // rebuild here. One extra Message allocation per CONNECT, which is
        // negligible against the BCrypt-grade cost of upstream login.
        return MessageBuilder.createMessage(message.payload, accessor.messageHeaders)
    }

    /**
     * Strip CR/LF so a malicious username can't inject extra log lines. Username
     * is already trimmed + lower-cased + length-limited at registration (#162),
     * but charset isn't restricted yet. Cheap belt.
     */
    private fun sanitizeForLog(value: String): String =
        value.replace(Regex("[\r\n]"), "_")

    companion object {
        const val AUTH_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        private const val GENERIC_AUTH_FAILURE = "Authentication failed"
    }
}
