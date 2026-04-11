package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Games
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.springframework.stereotype.Repository

@Repository
class GameDao {

    private fun ResultRow.toGameModel() = GameModel(
        id = this[Games.id].value,
        status = this[Games.status],
        hostUserId = this[Games.hostUserId].value,
        currentTurnIndex = this[Games.currentTurnIndex],
        turnPhase = this[Games.turnPhase],
        lastDiceRoll = this[Games.lastDiceRoll],
        roundNumber = this[Games.roundNumber]
    )

    fun findById(id: Int): GameModel? = transaction {
        Games.selectAll()
            .where { Games.id eq id }
            .singleOrNull()
            ?.toGameModel()
    }

    fun findAllByStatus(status: GameStatus): List<GameModel> = transaction {
        Games.selectAll()
            .where { Games.status eq status }
            .map { it.toGameModel() }
    }

    fun create(hostUserId: Int): Int = transaction {
        Games.insert {
            it[Games.hostUserId] = hostUserId
            it[Games.status] = GameStatus.WAITING
            it[Games.turnPhase] = TurnPhase.ROLL_DICE
        }[Games.id].value
    }

    fun updateStatus(id: Int, status: GameStatus): Unit = transaction {
        Games.update({ Games.id eq id }) {
            it[Games.status] = status
        }
    }

    fun updateTurnPhase(id: Int, phase: TurnPhase): Unit = transaction {
        Games.update({ Games.id eq id }) {
            it[Games.turnPhase] = phase
        }
    }

    fun updateAfterRoll(id: Int, diceRoll: Int, phase: TurnPhase): Unit = transaction {
        Games.update({ Games.id eq id }) {
            it[Games.lastDiceRoll] = diceRoll
            it[Games.turnPhase] = phase
        }
    }

    fun advanceTurn(id: Int, nextTurnIndex: Int, roundNumber: Int): Unit = transaction {
        Games.update({ Games.id eq id }) {
            it[Games.currentTurnIndex] = nextTurnIndex
            it[Games.roundNumber] = roundNumber
            it[Games.turnPhase] = TurnPhase.ROLL_DICE
            it[Games.lastDiceRoll] = null
        }
    }
}