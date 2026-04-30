package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.exception.DuplicateUserException
import org.mockito.kotlin.any
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
        val hash = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        whenever(userDao.findByUsername(username)).thenReturn(null)
        whenever(passwordEncoder.encode(rawPassword)).thenReturn(hash)
        whenever(userDao.create(username, hash)).thenReturn(42)

        val response = service.register(username, rawPassword)

        assertEquals(42, response.id)
        assertEquals(username, response.username)
        verify(passwordEncoder).encode(rawPassword)
        verify(userDao).create(username, hash)
    }

    @Test
    fun `register throws DuplicateUserException when username already exists`() {
        val username = "alice"
        val existing = UserModel(
            id = 1,
            username = username,
            passwordHash = null,
            sessionToken = null,
            totalWins = 0,
            totalGamesPlayed = 0,
        )
        whenever(userDao.findByUsername(username)).thenReturn(existing)

        assertThrows<DuplicateUserException> {
            service.register(username, "hunter2")
        }

        verify(passwordEncoder, never()).encode(any())
        verify(userDao, never()).create(any(), any())
    }

    @Test
    fun `register trims surrounding whitespace and lower-cases the username`() {
        val rawPassword = "hunter2"
        val hash = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        whenever(userDao.findByUsername("alice")).thenReturn(null)
        whenever(passwordEncoder.encode(rawPassword)).thenReturn(hash)
        whenever(userDao.create("alice", hash)).thenReturn(7)

        val response = service.register("  Alice  ", rawPassword)

        assertEquals("alice", response.username)
        verify(userDao).findByUsername("alice")
        verify(userDao).create("alice", hash)
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
}
