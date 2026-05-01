package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.exception.DuplicateUserException
import org.machikoro.server.exception.InvalidCredentialsException
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.password.PasswordEncoder

class AuthServiceTest {

    private val userDao = mock<UserDao>()
    private val passwordEncoder = mock<PasswordEncoder>()

    private val service = AuthService(userDao, passwordEncoder)

    @Test
    fun `register persists user with hashed password and returns id and username`() {
        val username = "alice"
        val rawPassword = "hunter2"
        whenever(userDao.findByUsername(username)).thenReturn(null)
        whenever(passwordEncoder.encode(rawPassword)).thenReturn(SAMPLE_BCRYPT_HASH)
        whenever(userDao.create(username, SAMPLE_BCRYPT_HASH)).thenReturn(42)

        val response = service.register(username, rawPassword)

        assertEquals(42, response.id)
        assertEquals(username, response.username)
        verify(passwordEncoder).encode(rawPassword)
        verify(userDao).create(username, SAMPLE_BCRYPT_HASH)
    }

    @Test
    fun `register throws DuplicateUserException when username already exists`() {
        val username = "alice"
        whenever(userDao.findByUsername(username)).thenReturn(
            userModel(id = 1, username = username, passwordHash = null),
        )

        assertThrows<DuplicateUserException> {
            service.register(username, "hunter2")
        }

        verify(passwordEncoder, never()).encode(any())
        verify(userDao, never()).create(any(), any())
    }

    @Test
    fun `register trims surrounding whitespace and lower-cases the username`() {
        val rawPassword = "hunter2"
        whenever(userDao.findByUsername("alice")).thenReturn(null)
        whenever(passwordEncoder.encode(rawPassword)).thenReturn(SAMPLE_BCRYPT_HASH)
        whenever(userDao.create("alice", SAMPLE_BCRYPT_HASH)).thenReturn(7)

        val response = service.register("  Alice  ", rawPassword)

        assertEquals("alice", response.username)
        verify(userDao).findByUsername("alice")
        verify(userDao).create("alice", SAMPLE_BCRYPT_HASH)
    }

    @Test
    fun `register rejects passwords longer than 72 characters`() {
        val tooLong = "x".repeat(73)

        assertThrows<IllegalArgumentException> {
            service.register("alice", tooLong)
        }

        verify(userDao, never()).findByUsername(any())
        verify(userDao, never()).create(any(), any())
    }

    @Test
    fun `register rejects blank username`() {
        assertThrows<IllegalArgumentException> {
            service.register("   ", "hunter2")
        }

        verify(userDao, never()).findByUsername(any())
        verify(userDao, never()).create(any(), any())
    }

    @Test
    fun `register rejects blank password`() {
        assertThrows<IllegalArgumentException> {
            service.register("alice", "")
        }

        verify(userDao, never()).findByUsername(any())
        verify(userDao, never()).create(any(), any())
    }

    @Test
    fun `register rejects username longer than 50 characters`() {
        val tooLong = "a".repeat(51)

        assertThrows<IllegalArgumentException> {
            service.register(tooLong, "hunter2")
        }

        verify(userDao, never()).findByUsername(any())
        verify(userDao, never()).create(any(), any())
    }

    @Test
    fun `login persists fresh token and returns it on correct credentials`() {
        val user = userModel(id = 7, username = "alice", passwordHash = SAMPLE_BCRYPT_HASH)
        whenever(userDao.findByUsername("alice")).thenReturn(user)
        whenever(passwordEncoder.matches("hunter2", SAMPLE_BCRYPT_HASH)).thenReturn(true)

        val response = service.login("alice", "hunter2")

        assertEquals("alice", response.username)
        assertNotNull(response.sessionToken)
        val tokenCaptor = argumentCaptor<String>()
        verify(userDao).updateSessionToken(eq(7), tokenCaptor.capture())
        assertEquals(response.sessionToken, tokenCaptor.firstValue)
    }

    @Test
    fun `login rejects unknown username with the same generic message as wrong password`() {
        whenever(userDao.findByUsername("ghost")).thenReturn(null)

        val unknownUserError = assertThrows<InvalidCredentialsException> {
            service.login("ghost", "hunter2")
        }

        // Compare against the wrong-password message to lock down the no-enumeration contract.
        val user = userModel(id = 1, username = "alice", passwordHash = SAMPLE_BCRYPT_HASH)
        whenever(userDao.findByUsername("alice")).thenReturn(user)
        whenever(passwordEncoder.matches("wrong", SAMPLE_BCRYPT_HASH)).thenReturn(false)
        val wrongPasswordError = assertThrows<InvalidCredentialsException> {
            service.login("alice", "wrong")
        }

        assertEquals(wrongPasswordError.message, unknownUserError.message)
        verify(userDao, never()).updateSessionToken(any(), any())
    }

    @Test
    fun `login rejects wrong password with InvalidCredentialsException`() {
        val user = userModel(id = 1, username = "alice", passwordHash = SAMPLE_BCRYPT_HASH)
        whenever(userDao.findByUsername("alice")).thenReturn(user)
        whenever(passwordEncoder.matches("wrong", SAMPLE_BCRYPT_HASH)).thenReturn(false)

        assertThrows<InvalidCredentialsException> {
            service.login("alice", "wrong")
        }

        verify(userDao, never()).updateSessionToken(any(), any())
    }

    @Test
    fun `login still runs BCrypt against a dummy hash when user has null passwordHash`() {
        val user = userModel(id = 1, username = "alice", passwordHash = null)
        whenever(userDao.findByUsername("alice")).thenReturn(user)
        // The encoder is now always called — the result against the dummy hash
        // is irrelevant (it's false), the point is that it ran for timing parity.
        whenever(passwordEncoder.matches(eq("hunter2"), any())).thenReturn(false)

        assertThrows<InvalidCredentialsException> {
            service.login("alice", "hunter2")
        }

        verify(passwordEncoder).matches(eq("hunter2"), any())
        verify(userDao, never()).updateSessionToken(any(), any())
    }

    @Test
    fun `login runs BCrypt against a dummy hash when user is unknown for timing parity`() {
        whenever(userDao.findByUsername("ghost")).thenReturn(null)
        whenever(passwordEncoder.matches(eq("any-password"), any())).thenReturn(false)

        assertThrows<InvalidCredentialsException> {
            service.login("ghost", "any-password")
        }

        // Critical: the encoder MUST run on the unknown-user path so the
        // response time matches the user-found-but-wrong-password path.
        // Skipping it would leak username existence via timing.
        verify(passwordEncoder).matches(eq("any-password"), any())
        verify(userDao, never()).updateSessionToken(any(), any())
    }

    @Test
    fun `login normalizes username with trim and lowercase before lookup`() {
        val user = userModel(id = 1, username = "alice", passwordHash = SAMPLE_BCRYPT_HASH)
        whenever(userDao.findByUsername("alice")).thenReturn(user)
        whenever(passwordEncoder.matches("hunter2", SAMPLE_BCRYPT_HASH)).thenReturn(true)

        val response = service.login("  Alice  ", "hunter2")

        assertEquals("alice", response.username)
        verify(userDao).findByUsername("alice")
    }

    @Test
    fun `login rejects blank username`() {
        assertThrows<IllegalArgumentException> {
            service.login("   ", "hunter2")
        }

        verify(userDao, never()).findByUsername(any())
        verify(userDao, never()).updateSessionToken(any(), any())
    }

    @Test
    fun `login rejects blank password`() {
        assertThrows<IllegalArgumentException> {
            service.login("alice", "")
        }

        verify(userDao, never()).findByUsername(any())
        verify(userDao, never()).updateSessionToken(any(), any())
    }

    @Test
    fun `logout clears session token when token is found`() {
        val token = "550e8400-e29b-41d4-a716-446655440000"
        val user = userModel(id = 7, username = "alice", passwordHash = null)
        whenever(userDao.findBySessionToken(token)).thenReturn(user)

        service.logout(token)

        verify(userDao).updateSessionToken(eq(7), isNull())
    }

    @Test
    fun `logout is a silent no-op when token is unknown`() {
        whenever(userDao.findBySessionToken("stale")).thenReturn(null)

        service.logout("stale")

        verify(userDao, never()).updateSessionToken(any(), any())
    }

    @Test
    fun `logout rejects blank session token`() {
        assertThrows<IllegalArgumentException> {
            service.logout("")
        }

        verify(userDao, never()).findBySessionToken(any())
        verify(userDao, never()).updateSessionToken(any(), any())
    }

    private fun userModel(
        id: Int,
        username: String,
        passwordHash: String?,
        sessionToken: String? = null,
    ) = UserModel(
        id = id,
        username = username,
        passwordHash = passwordHash,
        sessionToken = sessionToken,
        totalWins = 0,
        totalGamesPlayed = 0,
    )

    companion object {
        private const val SAMPLE_BCRYPT_HASH =
            "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    }
}
