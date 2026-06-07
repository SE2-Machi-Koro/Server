package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Games
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.exception.GameNotFoundException

class GameDaoTest : AbstractDBSetup() {

    private val userDao = UserDao()
    private val gameDao = GameDao()

    private var hostId = 0

    @BeforeEach
    fun seed() {
        hostId = userDao.create("host_user")
    }

    @AfterEach
    fun cleanup() {
        transaction {
            Games.deleteAll()
            Users.deleteAll()
        }
    }

    @Test
    fun `create and findById returns correct game`() {
        val id = gameDao.create(hostId)
        val game = gameDao.findById(id)
        assertNotNull(game)
        assertEquals(GameStatus.WAITING, game!!.status)
        assertEquals(TurnPhase.ROLL_DICE, game.turnPhase)
        assertEquals(hostId, game.hostUserId)
        assertEquals(1, game.roundNumber)
        assertEquals(0, game.currentTurnIndex)
        assertNull(game.lastDiceRoll)
        assertFalse(game.hasPurchasedThisTurn)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(gameDao.findById(999))
    }

    @Test
    fun `findAll returns all games`() {
        gameDao.create(hostId)
        gameDao.create(hostId)
        assertEquals(2, gameDao.findAll().size)
    }

    @Test
    fun `findAllByStatus filters correctly`() {
        gameDao.create(hostId)
        val id2 = gameDao.create(hostId)
        gameDao.updateStatus(id2, GameStatus.IN_PROGRESS)
        assertEquals(1, gameDao.findAllByStatus(GameStatus.WAITING).size)
        assertEquals(1, gameDao.findAllByStatus(GameStatus.IN_PROGRESS).size)
        assertEquals(0, gameDao.findAllByStatus(GameStatus.FINISHED).size)
    }

    @Test
    fun `updateStatus changes game status`() {
        val id = gameDao.create(hostId)
        gameDao.updateStatus(id, GameStatus.IN_PROGRESS)
        assertEquals(GameStatus.IN_PROGRESS, gameDao.findById(id)!!.status)
    }

    @Test
    fun `updateStatus throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.updateStatus(999999, GameStatus.IN_PROGRESS)
        }
    }

    @Test
    fun `updateTurnPhase changes phase`() {
        val id = gameDao.create(hostId)
        gameDao.updateTurnPhase(id, TurnPhase.BUY_OR_BUILD)
        assertEquals(TurnPhase.BUY_OR_BUILD, gameDao.findById(id)!!.turnPhase)
    }

    @Test
    fun `updateTurnPhase throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.updateTurnPhase(999999, TurnPhase.ROLL_DICE)
        }
    }

    @Test
    fun `getPhase returns current phase`() {
        val id = gameDao.create(hostId)
        assertEquals(TurnPhase.ROLL_DICE, gameDao.getPhase(id))
        gameDao.updateTurnPhase(id, TurnPhase.RESOLVE_EFFECTS)
        assertEquals(TurnPhase.RESOLVE_EFFECTS, gameDao.getPhase(id))
    }

    @Test
    fun `getPhase throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.getPhase(999999)
        }
    }

    @Test
    fun `updateAfterRoll sets dice result and phase`() {
        val id = gameDao.create(hostId)
        gameDao.updateAfterRoll(id, diceRoll = 6, phase = TurnPhase.RESOLVE_EFFECTS)
        val game = gameDao.findById(id)!!
        assertEquals(6, game.lastDiceRoll)
        assertEquals(TurnPhase.RESOLVE_EFFECTS, game.turnPhase)
        assertFalse(game.hasPurchasedThisTurn)
    }

    @Test
    fun `updateAfterRoll throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.updateAfterRoll(999999, diceRoll = 6, phase = TurnPhase.RESOLVE_EFFECTS)
        }
    }

    @Test
    fun `tryRecordDiceRoll records exactly one roll in a turn`() {
        val id = gameDao.create(hostId)

        assertTrue(gameDao.tryRecordDiceRoll(id, diceRoll = 4))
        assertFalse(gameDao.tryRecordDiceRoll(id, diceRoll = 6))

        val game = gameDao.findById(id)!!
        assertEquals(4, game.lastDiceRoll)
        assertEquals(TurnPhase.RESOLVE_EFFECTS, game.turnPhase)
    }

    @Test
    fun `tryTransitionPhase rejects a stale expected phase`() {
        val id = gameDao.create(hostId)

        assertFalse(gameDao.tryTransitionPhase(id, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD))
        assertEquals(TurnPhase.ROLL_DICE, gameDao.findById(id)!!.turnPhase)
    }

    @Test
    fun `updateHasPurchasedThisTurn changes purchase state`() {
        val id = gameDao.create(hostId)
        gameDao.updateHasPurchasedThisTurn(id, true)
        assertTrue(gameDao.findById(id)!!.hasPurchasedThisTurn)
    }

    @Test
    fun `tryMarkPurchasedThisTurn succeeds once and then rejects repeats`() {
        val id = gameDao.create(hostId)

        assertTrue(gameDao.tryMarkPurchasedThisTurn(id))
        assertFalse(gameDao.tryMarkPurchasedThisTurn(id))
        assertTrue(gameDao.findById(id)!!.hasPurchasedThisTurn)
    }

    @Test
    fun `updateHasPurchasedThisTurn throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.updateHasPurchasedThisTurn(999999, true)
        }
    }

    @Test
    fun `advanceTurn resets to ROLL_DICE clears dice roll and purchase state`() {
        val id = gameDao.create(hostId)
        gameDao.updateAfterRoll(id, diceRoll = 4, phase = TurnPhase.RESOLVE_EFFECTS)
        gameDao.updateHasPurchasedThisTurn(id, true)
        gameDao.advanceTurn(id, nextTurnIndex = 1, roundNumber = 2)
        val game = gameDao.findById(id)!!
        assertEquals(1, game.currentTurnIndex)
        assertEquals(2, game.roundNumber)
        assertEquals(TurnPhase.ROLL_DICE, game.turnPhase)
        assertNull(game.lastDiceRoll)
        assertFalse(game.hasPurchasedThisTurn)
    }

    @Test
    fun `advanceTurn throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.advanceTurn(999999, nextTurnIndex = 1, roundNumber = 2)
        }
    }

    @Test
    fun `existsByLobbyCode returns true for existing code`() {
        val id = gameDao.create(hostId)
        val code = gameDao.findById(id)!!.lobbyCode
        assertTrue(gameDao.existsByLobbyCode(code))
    }

    @Test
    fun `existsByLobbyCode returns false for unknown code`() {
        assertFalse(gameDao.existsByLobbyCode("XXXXXXX"))
    }

    @Test
    fun `findByLobbyCode returns game when code exists`() {
        val id = gameDao.create(hostId)

        val expected = gameDao.findById(id)
        val code = expected!!.lobbyCode

        val result = gameDao.findByLobbyCode(code)

        assertNotNull(result)
        assertEquals(id, result!!.id)
        assertEquals(code, result.lobbyCode)
    }

    @Test
    fun `findByLobbyCode returns null for unknown code`() {
        assertNull(gameDao.findByLobbyCode("INVALID"))
    }

    @Test
    fun `delete removes game from db`() {
        val id = gameDao.create(hostId)
        gameDao.delete(id)
        assertNull(gameDao.findById(id))
    }

    @Test
    fun `delete throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.delete(999999)
        }
    }

    @Test
    fun `updateRerolledThisTurn changes rerolled flag when game exists`() {
        val id = gameDao.create(hostId)
        // Initially created game has rerolledThisTurn = false
        gameDao.updateRerolledThisTurn(id, true)
        val game = gameDao.findById(id)!!
        assertTrue(game.rerolledThisTurn)
    }

    @Test
    fun `updateRerolledThisTurn throws when game does not exist`() {
        assertThrows<GameNotFoundException> {
            gameDao.updateRerolledThisTurn(999999, true)
        }
    }

    @Test
    fun `tryMarkRerolledThisTurn succeeds once and then rejects repeats`() {
        val id = gameDao.create(hostId)

        // first attempt should flip false -> true
        assertTrue(gameDao.tryMarkRerolledThisTurn(id))
        // subsequent attempts should fail (already true)
        assertFalse(gameDao.tryMarkRerolledThisTurn(id))

        // verify flag persisted
        assertTrue(gameDao.findById(id)!!.rerolledThisTurn)
    }
}
