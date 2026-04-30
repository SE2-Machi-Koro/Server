package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Games
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.utils.LobbyCodeGenerator
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Repository

@Repository
class GameDao {

    private fun ResultRow.toModel() = GameModel(
        id = this[Games.id].value,
        status = this[Games.status],
        hostUserId = this[Games.hostUserId].value,
        lobbyCode = this[Games.lobbyCode],
        maxPlayers = this[Games.maxPlayers],
        currentTurnIndex = this[Games.currentTurnIndex],
        turnPhase = this[Games.turnPhase],
        lastDiceRoll = this[Games.lastDiceRoll],
        roundNumber = this[Games.roundNumber],
        hasPurchasedThisTurn = this[Games.hasPurchasedThisTurn]
    )

    /**
     * Finds a game by its ID
     */
    fun findById(id: Int): GameModel? = transaction {
        Games.selectAll()
            .where { Games.id eq id }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Finds a game by its status
     */
    fun findAllByStatus(status: GameStatus): List<GameModel> = transaction {
        Games.selectAll()
            .where { Games.status eq status }
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
        Games.insertAndGetId {
            it[Games.hostUserId] = hostUserId
            it[Games.status] = GameStatus.WAITING
            it[Games.turnPhase] = TurnPhase.ROLL_DICE
            it[Games.currentTurnIndex] = 0
            it[Games.roundNumber] = 1
            it[Games.lastDiceRoll] = null
            it[Games.hasPurchasedThisTurn] = false
            it[Games.lobbyCode] = lobbyCode
            it[Games.maxPlayers] = maxPlayers
        }.value
    }

    private fun generateUniqueLobbyCode(): String {
        var code: String

        do {
            code = LobbyCodeGenerator.generate()
        } while (existsByLobbyCode(code))

        return code
    }

    fun existsByLobbyCode(code: String): Boolean = transaction {
        Games.selectAll()
            .where { Games.lobbyCode eq code }
            .empty()
            .not()
    }

    /**
     * Updates status of the game
     */
    fun updateStatus(id: Int, status: GameStatus): Unit = transaction {
        val updatedRows = Games.update({ Games.id eq id }) {
            it[Games.status] = status
        }
        if (updatedRows == 0) throw GameNotFoundException("Game $id not found")
    }

    /**
     * Gets the current turn phase of a game otherwise throws if game does not exist
     */
    fun getPhase(id: Int): TurnPhase = transaction {
        Games.selectAll()
            .where { Games.id eq id }
            .singleOrNull()
            ?.get(Games.turnPhase)
            ?: throw GameNotFoundException("Game $id not found")
    }

    /**
     * Updates current turn phase
     */
    fun updateTurnPhase(id: Int, phase: TurnPhase): Unit = transaction {
        val updatedRows = Games.update({ Games.id eq id }) {
            it[Games.turnPhase] = phase
        }
        if (updatedRows == 0) throw GameNotFoundException("Game $id not found")
    }

    /**
     * Updates game state after a dice roll
     */
    fun updateAfterRoll(id: Int, diceRoll: Int, phase: TurnPhase): Unit = transaction {
        val updatedRows = Games.update({ Games.id eq id }) {
            it[Games.lastDiceRoll] = diceRoll
            it[Games.turnPhase] = phase
        }
        if (updatedRows == 0) throw GameNotFoundException("Game $id not found")
    }

    /**
     * Updates whether the active turn has already used its purchase.
     */
    fun updateHasPurchasedThisTurn(id: Int, hasPurchasedThisTurn: Boolean): Unit = transaction {
        val updatedRows = Games.update({ Games.id eq id }) {
            it[Games.hasPurchasedThisTurn] = hasPurchasedThisTurn
        }
        if (updatedRows == 0) throw GameNotFoundException("Game $id not found")
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
        val updatedRows = Games.update({ Games.id eq id }) {
            it[Games.currentTurnIndex] = nextTurnIndex
            it[Games.roundNumber] = roundNumber
            it[Games.turnPhase] = TurnPhase.ROLL_DICE
            it[Games.lastDiceRoll] = null
            it[Games.hasPurchasedThisTurn] = false
        }
        if (updatedRows == 0) throw GameNotFoundException("Game $id not found")
    }

    /**
     * Finds all games
     */
    fun findAll(): List<GameModel> = transaction {
        Games.selectAll().map { it.toModel() }
    }

    /**
     * Deletes a game by ID
     */
    fun delete(id: Int): Unit = transaction {
        val deletedRows = Games.deleteWhere { Games.id eq id }
        if (deletedRows == 0) throw GameNotFoundException("Game $id not found")
    }
}
