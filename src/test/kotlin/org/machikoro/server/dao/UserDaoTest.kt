package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Users

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
    fun `incrementGamesPlayed increases games played count`() {
        val id = dao.create("nami")
        dao.incrementGamesPlayed(id)
        assertEquals(1, dao.findById(id)!!.totalGamesPlayed)
    }
}