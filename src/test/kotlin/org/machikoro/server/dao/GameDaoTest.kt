package org.machikoro.server.dao

import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.database.AbstractDBSetup
import org.machikoro.server.database.Games
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase

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
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(gameDao.findById(999))
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
    fun `updateTurnPhase changes phase`() {
        val id = gameDao.create(hostId)
        gameDao.updateTurnPhase(id, TurnPhase.BUY_OR_BUILD)
        assertEquals(TurnPhase.BUY_OR_BUILD, gameDao.findById(id)!!.turnPhase)
    }

    @Test
    fun `updateAfterRoll sets dice result and phase`() {
        val id = gameDao.create(hostId)
        gameDao.updateAfterRoll(id, diceRoll = 6, phase = TurnPhase.RESOLVE_EFFECTS)
        val game = gameDao.findById(id)!!
        assertEquals(6, game.lastDiceRoll)
        assertEquals(TurnPhase.RESOLVE_EFFECTS, game.turnPhase)
    }

    @Test
    fun `advanceTurn resets to ROLL_DICE and clears dice roll`() {
        val id = gameDao.create(hostId)
        gameDao.updateAfterRoll(id, diceRoll = 4, phase = TurnPhase.RESOLVE_EFFECTS)
        gameDao.advanceTurn(id, nextTurnIndex = 1, roundNumber = 2)
        val game = gameDao.findById(id)!!
        assertEquals(1, game.currentTurnIndex)
        assertEquals(2, game.roundNumber)
        assertEquals(TurnPhase.ROLL_DICE, game.turnPhase)
        assertNull(game.lastDiceRoll)
    }
}