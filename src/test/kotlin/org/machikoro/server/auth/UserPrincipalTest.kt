package org.machikoro.server.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UserPrincipalTest {

    @Test
    fun `getName returns the username per the Principal contract`() {
        val principal = UserPrincipal(userId = 7, username = "alice")

        assertEquals("alice", principal.name)
    }
}
