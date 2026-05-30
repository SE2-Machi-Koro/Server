package org.machikoro.server.database

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.crypto.password.PasswordEncoder

class AdminSeederTest {

    private val userDao = mock<UserDao>()
    private val passwordEncoder = mock<PasswordEncoder>()

    private fun adminUser(id: Int, username: String) = UserModel(
        id = id,
        username = username,
        passwordHash = "hash",
        sessionToken = null,
        totalWins = 0,
        totalGamesPlayed = 0,
        isAdmin = true,
    )

    @Test
    fun `run skips seeding when adminPassword is blank`() {
        val seeder = AdminSeeder(userDao, passwordEncoder, "")

        seeder.run()

        verify(userDao, never()).findByUsername(any())
        verify(userDao, never()).create(any(), any(), any())
    }

    @Test
    fun `run creates all 4 admin accounts when they do not exist`() {
        whenever(userDao.findByUsername(any())).thenReturn(null)
        whenever(passwordEncoder.encode("secret")).thenReturn("hashed")
        whenever(userDao.create(any(), any(), any())).thenReturn(1)

        val seeder = AdminSeeder(userDao, passwordEncoder, "secret")
        seeder.run()

        // All 4 accounts created with isAdmin=true
        verify(userDao, times(4)).create(any(), eq("hashed"), eq(true))
    }

    @Test
    fun `run skips create when admin accounts already exist`() {
        AdminSeeder.ADMIN_USERNAMES.forEachIndexed { i, name ->
            whenever(userDao.findByUsername(name)).thenReturn(adminUser(i + 1, name))
        }

        val seeder = AdminSeeder(userDao, passwordEncoder, "secret")
        seeder.run()

        verify(userDao, never()).create(any(), any(), any())
    }

    @Test
    fun `run creates only missing admin accounts when some already exist`() {
        whenever(userDao.findByUsername("admin_1")).thenReturn(adminUser(1, "admin_1"))
        whenever(userDao.findByUsername("admin_2")).thenReturn(null)
        whenever(userDao.findByUsername("admin_3")).thenReturn(null)
        whenever(userDao.findByUsername("admin_4")).thenReturn(adminUser(4, "admin_4"))
        whenever(passwordEncoder.encode("secret")).thenReturn("hashed")
        whenever(userDao.create(any(), any(), any())).thenReturn(1)

        val seeder = AdminSeeder(userDao, passwordEncoder, "secret")
        seeder.run()

        // Only the 2 missing ones get created
        verify(userDao, times(2)).create(any(), eq("hashed"), eq(true))
    }

    @Test
    fun `ADMIN_USERNAMES contains exactly 4 entries`() {
        assertFalse(AdminSeeder.ADMIN_USERNAMES.isEmpty())
        assert(AdminSeeder.ADMIN_USERNAMES.size == 4)
    }
}