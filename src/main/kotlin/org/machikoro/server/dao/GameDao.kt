package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.entities.GameEntity
import org.machikoro.server.database.Games
import org.machikoro.server.database.entities.UserEntity
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.utils.LobbyCodeGenerator
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Repository

@Repository
class GameDao {

    /**
     * Finds a game by its ID
     */
    fun findById(id: Int): GameModel? = transaction {
        GameEntity.findById(id)?.toModel()
    }

    /**
     * Finds a game by its status
     */
    fun findAllByStatus(status: GameStatus): List<GameModel> = transaction {
        GameEntity.find { Games.status eq status }
            .map { it.toModel() }
    }

    /**
     * Creates a new game with the given host user
     * Initializes default game state:
     * - status = WAITING
     * - turnPhase = ROLL_DICE
     * - roundNumber = 1
     */
    fun create(hostUserId: Int, lobbyCode: String = generateUniqueLobbyCode(), maxPlayers: Int = 4): Int = transaction {
        GameEntity.new {
            hostUser = UserEntity.findById(hostUserId)
                ?: error("User $hostUserId not found")
            status = GameStatus.WAITING
            turnPhase = TurnPhase.ROLL_DICE
            currentTurnIndex = 0
            roundNumber = 1
            lastDiceRoll = null
            hasPurchasedThisTurn = false
            this.lobbyCode = lobbyCode
            this.maxPlayers = maxPlayers
        }.id.value
    }

    private fun generateUniqueLobbyCode(): String {
        var code: String

        do {
            code = LobbyCodeGenerator.generate()
        } while (existsByLobbyCode(code))

        return code
    }

    fun existsByLobbyCode(code: String): Boolean = transaction {
        !GameEntity.find { Games.lobbyCode eq code }.empty()
    }

    /**
     * Updates status of the game
     */
    fun updateStatus(id: Int, status: GameStatus): Unit = transaction {
        GameEntity.findById(id)?.apply {
            this.status = status
        }
    }

    /**
     * Gets the current turn phase of a game otherwise throws if game does not exist
     */
    fun getPhase(id: Int): TurnPhase = transaction {
        GameEntity.findById(id)?.turnPhase
            ?: throw GameNotFoundException("Game $id not found")
    }

    /**
     * Updates current turn phase
     */
    fun updateTurnPhase(id: Int, phase: TurnPhase): Unit = transaction {
        val game = GameEntity.findById(id)
            ?: throw GameNotFoundException("Game $id not found")
        game.turnPhase = phase
    }

    /**
     * Updates game state after a dice roll
     */
    fun updateAfterRoll(id: Int, diceRoll: Int, phase: TurnPhase): Unit = transaction {
        GameEntity.findById(id)?.apply {
            lastDiceRoll = diceRoll
            turnPhase = phase
        }
    }

    /**
     * Updates whether the active turn has already used its purchase.
     */
    fun updateHasPurchasedThisTurn(id: Int, hasPurchasedThisTurn: Boolean): Unit = transaction {
        val game = GameEntity.findById(id)
            ?: throw GameNotFoundException("Game $id not found")
        game.hasPurchasedThisTurn = hasPurchasedThisTurn
    }

    /**
     * Atomically marks the current turn as having purchased only if it has not
     * already been marked by another request.
     */
    fun tryMarkPurchasedThisTurn(id: Int): Boolean = transaction {
        Games.update({
            (Games.id eq id) and
                (Games.hasPurchasedThisTurn eq false)
        }) {
            it[hasPurchasedThisTurn] = true
        } > 0
    }

    /**
     * Advances the game to the next player's turn
     * - Resets phase to ROLL_DICE
     * - Clears last dice roll
     */
    fun advanceTurn(id: Int, nextTurnIndex: Int, roundNumber: Int): Unit = transaction {
        GameEntity.findById(id)?.apply {
            currentTurnIndex = nextTurnIndex
            this.roundNumber = roundNumber
            turnPhase = TurnPhase.ROLL_DICE
            lastDiceRoll = null
            hasPurchasedThisTurn = false
        }
    }

    /**
     * Finds all games
     */
    fun findAll(): List<GameModel> = transaction {
        GameEntity.all().map { it.toModel() }
    }

    /**
     * Deletes a game by ID
     */
    fun delete(id: Int): Unit = transaction {
        GameEntity.findById(id)?.delete()
    }
}
