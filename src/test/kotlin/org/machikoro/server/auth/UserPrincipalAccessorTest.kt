package org.machikoro.server.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import java.security.Principal

class UserPrincipalAccessorTest {

    private val alice = UserPrincipal(userId = 7, username = "alice")

    @Test
    fun `userPrincipal reads from accessor user when set`() {
        val accessor = SimpMessageHeaderAccessor.create().apply { user = alice }

        assertEquals(alice, accessor.userPrincipal())
    }

    @Test
    fun `userPrincipal falls back to sessionAttributes when accessor user is missing`() {
        // Realistic case: SEND frame after CONNECT, where Spring lost the
        // accessor.user but the interceptor's sessionAttributes write survives.
        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionAttributes = mutableMapOf(USER_PRINCIPAL_KEY to alice)
        }

        assertEquals(alice, accessor.userPrincipal())
    }

    @Test
    fun `userPrincipal returns null when neither source is populated`() {
        val accessor = SimpMessageHeaderAccessor.create()

        assertNull(accessor.userPrincipal())
    }

    @Test
    fun `userPrincipal returns null when accessor user is a non-UserPrincipal Principal`() {
        // Defensive: another component might set a foreign Principal type. The
        // helper must not coerce — the cast fails closed.
        val accessor = SimpMessageHeaderAccessor.create().apply {
            user = Principal { "foreign" }
        }

        assertNull(accessor.userPrincipal())
    }

    @Test
    fun `requireUserPrincipal returns the principal when present`() {
        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionAttributes = mutableMapOf(USER_PRINCIPAL_KEY to alice)
        }

        assertEquals(alice, accessor.requireUserPrincipal())
    }

    @Test
    fun `requireUserPrincipal throws UNAUTHENTICATED when no principal is present`() {
        val accessor = SimpMessageHeaderAccessor.create()

        val ex = assertThrows<CustomWebSocketException> {
            accessor.requireUserPrincipal()
        }
        assertEquals("UNAUTHENTICATED", ex.errorCode)
        assertEquals(
            "Authenticated principal not found — connection may not have completed STOMP handshake",
            ex.message,
        )
    }
}
