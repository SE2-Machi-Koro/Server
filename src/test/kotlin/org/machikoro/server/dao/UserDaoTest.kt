package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Users
import org.machikoro.server.exception.UserNotFoundException

class UserDaoTest : AbstractDBSetup() {

    private val dao = UserDao()

    @AfterEach
    fun cleanup() {
        transaction { Users.deleteAll() }
    }

    @Test
    fun `create and findById returns correct user`() {
        val id = dao.create("momo")
        val user = dao.findById(id)
        assertNotNull(user)
        assertEquals("momo", user!!.username)
        assertEquals(0, user.totalWins)
        assertEquals(0, user.totalGamesPlayed)
        assertNull(user.sessionToken)
        assertNull(user.passwordHash)
    }

    @Test
    fun `create with passwordHash persists the hash and findById round-trips it`() {
        val hash = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        val id = dao.create("luffy", passwordHash = hash)
        val user = dao.findById(id)
        assertNotNull(user)
        assertEquals(hash, user!!.passwordHash)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(dao.findById(999))
    }

    @Test
    fun `findByUsername returns correct user`() {
        dao.create("bobs_burger")
        assertNotNull(dao.findByUsername("bobs_burger"))
    }

    @Test
    fun `findByUsername returns null for unknown username`() {
        assertNull(dao.findByUsername("nemo"))
    }

    @Test
    fun `updateSessionToken sets token correctly`() {
        val id = dao.create("dora")
        dao.updateSessionToken(id, "token-123")
        val user = dao.findBySessionToken("token-123")
        assertNotNull(user)
        assertEquals("dora", user!!.username)
    }

    @Test
    fun `updateSessionToken to null clears the token`() {
        val id = dao.create("davethediver")
        dao.updateSessionToken(id, "gigatoken")
        dao.updateSessionToken(id, null)
        assertNull(dao.findBySessionToken("gigatoken"))
    }

    @Test
    fun `updateSessionToken throws when user does not exist`() {
        assertThrows<UserNotFoundException> {
            dao.updateSessionToken(999999, "some-token")
        }
    }

    @Test
    fun `updatePasswordHash replaces the stored hash`() {
        val firstHash = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        val secondHash = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWY"
        val id = dao.create("zoro", passwordHash = firstHash)
        dao.updatePasswordHash(id, secondHash)
        assertEquals(secondHash, dao.findById(id)!!.passwordHash)
    }

    @Test
    fun `updatePasswordHash throws when user does not exist`() {
        assertThrows<UserNotFoundException> {
            dao.updatePasswordHash(999999, "some-hash")
        }
    }

    @Test
    fun `findBySessionToken returns null for unknown token`() {
        assertNull(dao.findBySessionToken("no-token"))
    }

    @Test
    fun `incrementWins increases win count`() {
        val id = dao.create("bisasam")
        dao.incrementWins(id)
        dao.incrementWins(id)
        assertEquals(2, dao.findById(id)!!.totalWins)
    }

    @Test
    fun `incrementWins throws when user does not exist`() {
        assertThrows<UserNotFoundException> {
            dao.incrementWins(999999)
        }
    }

    @Test
    fun `incrementGamesPlayed increases games played count`() {
        val id = dao.create("nami")
        dao.incrementGamesPlayed(id)
        assertEquals(1, dao.findById(id)!!.totalGamesPlayed)
    }

    @Test
    fun `incrementGamesPlayed throws when user does not exist`() {
        assertThrows<UserNotFoundException> {
            dao.incrementGamesPlayed(999999)
        }
    }

    @Test
    fun `delete removes user from db`() {
        val id = dao.create("zoro")
        dao.delete(id)
        assertNull(dao.findById(id))
    }

    @Test
    fun `delete throws when user does not exist`() {
        assertThrows<UserNotFoundException> {
            dao.delete(999999)
        }
    }
}