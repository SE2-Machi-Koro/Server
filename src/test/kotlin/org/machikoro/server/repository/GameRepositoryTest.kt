package org.machikoro.server.repository

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Games
import org.machikoro.server.database.Users
import org.machikoro.server.database.initDatabase
import org.machikoro.server.domain.TurnPhase
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

@Testcontainers
@SpringBootTest
class GameRepositoryTest : AbstractDBSetup() {

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var repository: GameRepository

    private var gameId: Int = 0

    @BeforeEach
    fun setUp() {
        initDatabase(dataSource)
        transaction {
            Games.deleteAll()
            Users.deleteAll()

            val userId = Users.insert {
                it[username] = "test-player"
            } get Users.id

            gameId = (Games.insert {
                it[hostUserId] = userId
            } get Games.id).value
        }
    }

    @Test
    fun `getPhase returns default phase for new game`() {
        val phase = repository.getPhase(gameId)

        assertEquals(TurnPhase.ROLL_DICE, phase)
    }

    @Test
    fun `getPhase throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            repository.getPhase(999999)
        }
    }

    @Test
    fun `updatePhase changes the stored phase`() {
        repository.updatePhase(gameId, TurnPhase.RESOLVE_EFFECTS)

        assertEquals(TurnPhase.RESOLVE_EFFECTS, repository.getPhase(gameId))
    }

    @Test
    fun `updatePhase throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            repository.updatePhase(999999, TurnPhase.ROLL_DICE)
        }
    }

    @Test
    fun `updatePhase can cycle through all phases`() {
        TurnPhase.entries.forEach { phase ->
            repository.updatePhase(gameId, phase)
            assertEquals(phase, repository.getPhase(gameId))
        }
    }
}
