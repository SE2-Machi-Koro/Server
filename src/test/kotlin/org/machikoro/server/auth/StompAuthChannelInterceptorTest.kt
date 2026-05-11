package org.machikoro.server.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.exception.InvalidSessionTokenException
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.Message
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.MessageBuilder

class StompAuthChannelInterceptorTest {

    private val userDao = mock<UserDao>()
    private val interceptor = StompAuthChannelInterceptor(userDao)

    @Test
    fun `non-CONNECT frames pass through unchanged without touching UserDao`() {
        val original = stompMessage(StompCommand.SEND, headers = emptyMap())

        val result = interceptor.preSend(original, mock())

        // Reference equality — the interceptor MUST return the same instance for
        // non-CONNECT frames, otherwise it's silently rebuilding messages on the
        // hot path (every SEND, every SUBSCRIBE).
        assertSame(original, result)
        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `CONNECT with valid Bearer token attaches UserPrincipal to the session`() {
        val token = "550e8400-e29b-41d4-a716-446655440000"
        val user = userModel(id = 7, username = "alice", sessionToken = token)
        whenever(userDao.findBySessionToken(token)).thenReturn(user)

        val sessionAttrs = mutableMapOf<String, Any>()
        val result = interceptor.preSend(
            stompMessage(
                StompCommand.CONNECT,
                headers = mapOf("Authorization" to "Bearer $token"),
                sessionAttributes = sessionAttrs,
            ),
            mock(),
        )

        assertNotNull(result)
        val accessor = StompHeaderAccessor.wrap(result!!)
        val expected = UserPrincipal(userId = 7, username = "alice")
        // Session attributes are the durable propagation channel; accessor.user
        // is set as well but doesn't survive across STOMP frames in our setup.
        assertEquals(expected, accessor.user)
        assertEquals(expected, sessionAttrs[USER_PRINCIPAL_KEY])
    }

    @Test
    fun `CONNECT with missing Authorization header throws and does not query UserDao`() {
        assertThrows<InvalidSessionTokenException> {
            interceptor.preSend(
                stompMessage(StompCommand.CONNECT, headers = emptyMap()),
                mock(),
            )
        }

        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `CONNECT with blank Authorization header throws and does not query UserDao`() {
        assertThrows<InvalidSessionTokenException> {
            interceptor.preSend(
                stompMessage(StompCommand.CONNECT, headers = mapOf("Authorization" to "")),
                mock(),
            )
        }

        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `CONNECT with Bearer prefix but blank token throws`() {
        assertThrows<InvalidSessionTokenException> {
            interceptor.preSend(
                stompMessage(StompCommand.CONNECT, headers = mapOf("Authorization" to "Bearer ")),
                mock(),
            )
        }

        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `CONNECT with header missing the Bearer prefix is rejected`() {
        // Defensive: some clients might send the bare token. We treat it as
        // malformed rather than guessing — keeps the contract unambiguous.
        assertThrows<InvalidSessionTokenException> {
            interceptor.preSend(
                stompMessage(StompCommand.CONNECT, headers = mapOf("Authorization" to "raw-token-no-prefix")),
                mock(),
            )
        }

        verify(userDao, never()).findBySessionToken(any())
    }

    @Test
    fun `CONNECT with valid Bearer prefix but unknown token throws and never attaches a principal`() {
        whenever(userDao.findBySessionToken("ghost-token")).thenReturn(null)

        assertThrows<InvalidSessionTokenException> {
            interceptor.preSend(
                stompMessage(StompCommand.CONNECT, headers = mapOf("Authorization" to "Bearer ghost-token")),
                mock(),
            )
        }
    }

    @Test
    fun `CONNECT without sessionAttributes still attaches accessor user and does not crash`() {
        // Defensive: in production sessionAttributes is always non-null at CONNECT
        // because Spring's WebSocket plumbing initialises it during the handshake,
        // but the interceptor must not NPE if that contract ever changes.
        val token = "valid-token"
        val user = userModel(id = 1, username = "bob", sessionToken = token)
        whenever(userDao.findBySessionToken(token)).thenReturn(user)

        val result = interceptor.preSend(
            stompMessage(
                StompCommand.CONNECT,
                headers = mapOf("Authorization" to "Bearer $token"),
                // sessionAttributes intentionally null
            ),
            mock(),
        )

        assertNotNull(result)
        val accessor = StompHeaderAccessor.wrap(result!!)
        assertEquals(UserPrincipal(userId = 1, username = "bob"), accessor.user)
    }

    @Test
    fun `non-CONNECT frame is returned without invoking UserDao even when an Authorization header is present`() {
        // Defensive: SUBSCRIBE / SEND frames inherit the principal attached at CONNECT
        // time. The interceptor MUST NOT re-validate per-frame, otherwise every frame
        // hits the DB and we leak token presence via timing.
        val token = "valid-token"
        val result = interceptor.preSend(
            stompMessage(StompCommand.SUBSCRIBE, headers = mapOf("Authorization" to "Bearer $token")),
            mock(),
        )

        assertNotNull(result)
        verify(userDao, never()).findBySessionToken(any())
    }

    private fun stompMessage(
        command: StompCommand,
        headers: Map<String, String>,
        sessionAttributes: MutableMap<String, Any>? = null,
    ): Message<ByteArray> {
        val accessor = StompHeaderAccessor.create(command)
        headers.forEach { (k, v) -> accessor.addNativeHeader(k, v) }
        if (sessionAttributes != null) accessor.sessionAttributes = sessionAttributes
        return MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
    }

    private fun userModel(
        id: Int,
        username: String,
        passwordHash: String? = null,
        sessionToken: String? = null,
    ) = UserModel(
        id = id,
        username = username,
        passwordHash = passwordHash,
        sessionToken = sessionToken,
        totalWins = 0,
        totalGamesPlayed = 0,
    )
}
