package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.entities.GameEntity
import org.machikoro.server.database.Games
import org.machikoro.server.database.entities.UserEntity
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Repository

@Repository
class GameDao {

    fun findById(id: Int): GameModel? = transaction {
        GameEntity.findById(id)?.toModel()
    }

    fun findAllByStatus(status: GameStatus): List<GameModel> = transaction {
        GameEntity.find { Games.status eq status }
            .map { it.toModel() }
    }

    fun create(hostUserId: Int): Int = transaction {
        GameEntity.new {
            hostUser = UserEntity.findById(hostUserId)
                ?: error("User $hostUserId not found")
            status = GameStatus.WAITING
            turnPhase = TurnPhase.ROLL_DICE
            currentTurnIndex = 0
            roundNumber = 1
            lastDiceRoll = null
        }.id.value
    }

    fun updateStatus(id: Int, status: GameStatus): Unit = transaction {
        GameEntity.findById(id)?.apply {
            this.status = status
        }
    }

    fun getPhase(id: Int): TurnPhase = transaction {
        GameEntity.findById(id)?.turnPhase
            ?: throw GameNotFoundException("Game $id not found")
    }

    fun updateTurnPhase(id: Int, phase: TurnPhase): Unit = transaction {
        val game = GameEntity.findById(id)
            ?: throw GameNotFoundException("Game $id not found")
        game.turnPhase = phase
    }

    fun updateAfterRoll(id: Int, diceRoll: Int, phase: TurnPhase): Unit = transaction {
        GameEntity.findById(id)?.apply {
            lastDiceRoll = diceRoll
            turnPhase = phase
        }
    }

    fun advanceTurn(id: Int, nextTurnIndex: Int, roundNumber: Int): Unit = transaction {
        GameEntity.findById(id)?.apply {
            currentTurnIndex = nextTurnIndex
            this.roundNumber = roundNumber
            turnPhase = TurnPhase.ROLL_DICE
            lastDiceRoll = null
        }
    }

    fun findAll(): List<GameModel> = transaction {
        GameEntity.all().map { it.toModel() }
    }

    fun delete(id: Int): Unit = transaction {
        GameEntity.findById(id)?.delete()
    }
}