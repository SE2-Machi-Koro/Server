package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.CheatFlags
import org.machikoro.server.database.Games
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test

class CheatFlagDaoTest : AbstractDBSetup() {

    @Autowired
    private lateinit var cheatFlagDao: CheatFlagDao

    private var playerId = 0

    @BeforeEach
    fun setupData() {
        transaction {
            CheatFlags.deleteAll()
            Players.deleteAll()
            Games.deleteAll()
            Users.deleteAll()

            val userId = Users.insertAndGetId { it[username] = "cheater" }.value
            val gameId = Games.insertAndGetId {
                it[hostUserId] = userId
                it[lobbyCode] = "ABC1234"
            }.value
            playerId = Players.insertAndGetId {
                it[Players.gameId] = gameId
                it[Players.userId] = userId
                it[turnOrder] = 0
            }.value
        }
    }

    @Test
    fun `setOutstanding then hasOutstanding is true`() {
        cheatFlagDao.setOutstanding(playerId)
        assertTrue(cheatFlagDao.hasOutstanding(playerId))
    }

    @Test
    fun `setOutstanding is idempotent and consume removes the single row`() {
        cheatFlagDao.setOutstanding(playerId)
        cheatFlagDao.setOutstanding(playerId)

        assertEquals(1, cheatFlagDao.consume(playerId))
        assertFalse(cheatFlagDao.hasOutstanding(playerId))
    }

    @Test
    fun `consume returns 0 when there is no outstanding cheat`() {
        assertEquals(0, cheatFlagDao.consume(playerId))
    }

    @Test
    fun `flag is removed via cascade when the player is deleted`() {
        cheatFlagDao.setOutstanding(playerId)

        transaction { Players.deleteWhere { Players.id eq playerId } }

        assertFalse(cheatFlagDao.hasOutstanding(playerId))
    }
}
