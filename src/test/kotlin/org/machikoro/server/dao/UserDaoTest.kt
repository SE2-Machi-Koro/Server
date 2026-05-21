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

    // ── getLeaderboard ───────────────────────────────────────────────────────

    @Test
    fun `getLeaderboard returns users sorted by wins descending`() {
        val lowId = dao.create("low")
        val highId = dao.create("high")
        dao.incrementWins(highId)
        dao.incrementWins(highId)
        dao.incrementWins(lowId)

        val result = dao.getLeaderboard(10)

        assertEquals(listOf("high", "low"), result.map { it.username })
    }

    @Test
    fun `getLeaderboard breaks ties by gamesPlayed descending`() {
        val fewId = dao.create("few")
        val manyId = dao.create("many")
        // Both 1 win; "many" has more games played
        dao.incrementWins(fewId)
        dao.incrementWins(manyId)
        dao.incrementGamesPlayed(manyId)
        dao.incrementGamesPlayed(manyId)
        dao.incrementGamesPlayed(fewId)

        val result = dao.getLeaderboard(10)

        assertEquals(listOf("many", "few"), result.map { it.username })
    }

    @Test
    fun `getLeaderboard respects the limit`() {
        repeat(5) { i -> dao.create("user$i") }

        val result = dao.getLeaderboard(3)

        assertEquals(3, result.size)
    }

    @Test
    fun `getLeaderboard returns empty list when no users exist`() {
        val result = dao.getLeaderboard(10)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `deleteByUsernamePrefix removes only matching users and returns count`() {
        dao.create("debug_player1")
        dao.create("debug_player2")
        dao.create("alice")

        val count = dao.deleteByUsernamePrefix("debug_player")

        assertEquals(2, count)
        assertNull(dao.findByUsername("debug_player1"))
        assertNull(dao.findByUsername("debug_player2"))
        assertNotNull(dao.findByUsername("alice"))
    }

    @Test
    fun `deleteByUsernamePrefix returns 0 when no users match prefix`() {
        dao.create("alice")
        dao.create("bob")

        val count = dao.deleteByUsernamePrefix("debug_")

        assertEquals(0, count)
        assertNotNull(dao.findByUsername("alice"))
        assertNotNull(dao.findByUsername("bob"))
    }
}