package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
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
        lastDiceCount = this[Games.lastDiceCount],
        roundNumber = this[Games.roundNumber],
        hasPurchasedThisTurn = this[Games.hasPurchasedThisTurn],
        businessCenterUsedThisTurn = this[Games.businessCenterUsedThisTurn],
        extraTurnPlayerId = this[Games.extraTurnPlayerId],
        extraTurnRoundNumber = this[Games.extraTurnRoundNumber],
        extraTurnConsumed = this[Games.extraTurnConsumed],
        rerolledThisTurn = this[Games.rerolledThisTurn],
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
            it[Games.businessCenterUsedThisTurn] = false
            it[Games.lobbyCode] = lobbyCode
            it[Games.maxPlayers] = maxPlayers
            it[Games.rerolledThisTurn] = false
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
     * Finds a game by its lobby code
     */
    fun findByLobbyCode(code: String): GameModel? = transaction {
        Games.selectAll()
            .where { Games.lobbyCode eq code }
            .singleOrNull()
            ?.toModel()
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
     * Persists the only legal transition out of ROLL_DICE. The conditional
     * update prevents two simultaneous requests from recording separate rolls.
     */
    fun tryRecordDiceRoll(id: Int, diceRoll: Int, diceCount: Int): Boolean = transaction {
        Games.update({
            (Games.id eq id) and
                (Games.turnPhase eq TurnPhase.ROLL_DICE) and
                Games.lastDiceRoll.isNull()
        }) {
            it[Games.lastDiceRoll] = diceRoll
            it[Games.lastDiceCount] = diceCount
            it[Games.turnPhase] = TurnPhase.RESOLVE_EFFECTS
        } > 0
    }

    /**
     * Grants an extra turn to playerId for the current round if not already granted
     * (or already consumed) for the same player in the same round. Returns true if persisted.
     */
    fun markExtraTurnIfEligible(gameId: Int, playerId: Int, roundNumber: Int): Boolean = transaction {
        // Block grant when same player + same round (pending or consumed); allow otherwise
        Games.update({
            (Games.id eq gameId) and
                    (Games.extraTurnRoundNumber.isNull() or
                            (Games.extraTurnRoundNumber neq roundNumber) or
                            ((Games.extraTurnPlayerId neq playerId) and (Games.extraTurnRoundNumber eq roundNumber)))
        }) {
            it[Games.extraTurnPlayerId] = playerId
            it[Games.extraTurnRoundNumber] = roundNumber
            it[Games.extraTurnConsumed] = false
        } > 0
    }

    fun removeExtraTurnMark(gameId: Int, playerId: Int, roundNumber: Int): Boolean = transaction {
        // Mark consumed but keep player/round so re-grant is blocked for the rest of this round
        Games.update({
            (Games.id eq gameId) and
                    (Games.extraTurnPlayerId eq playerId) and
                    (Games.extraTurnRoundNumber eq roundNumber) and
                    (Games.extraTurnConsumed eq false)
        }) {
            it[Games.extraTurnConsumed] = true
        } > 0
    }

    /**
     * Changes phase only when the stored phase still matches the action that
     * requested the transition.
     */
    fun tryTransitionPhase(id: Int, expected: TurnPhase, next: TurnPhase): Boolean = transaction {
        Games.update({
            (Games.id eq id) and (Games.turnPhase eq expected)
        }) {
            it[Games.turnPhase] = next
        } > 0
    }

    /**
     * Atomically attempts to reroll: succeeds only if the game is still in RESOLVE_EFFECTS,
     * has an active dice roll, and has not yet rerolled this turn.
     *
     * On success, sets the new dice roll and marks rerolledThisTurn = true in a single transaction.
     * Returns true if the update succeeded, false if any condition failed.
     *
     * lastDiceCount is intentionally left untouched: a reroll replays the same number of dice as the
     * initial roll, so the count recorded by [tryRecordDiceRoll] must be preserved (DiceService reads
     * lastDiceCount at reroll time to know how many dice to roll).
     */
    fun tryRerollThisTurn(id: Int, newDiceRoll: Int): Boolean = transaction {
        Games.update({
            (Games.id eq id) and
                    (Games.turnPhase eq TurnPhase.RESOLVE_EFFECTS) and
                    (Games.lastDiceRoll.isNotNull()) and
                    (Games.rerolledThisTurn eq false)
        }) {
            it[Games.lastDiceRoll] = newDiceRoll
            it[Games.rerolledThisTurn] = true
            // lastDiceCount deliberately not updated — the reroll keeps the initial roll's dice count.
        } > 0
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
     *
     * This is used by the buying-phase purchase flow to enforce the
     * "only one purchase per turn" rule without a separate read-then-write race.
     */
    fun tryMarkPurchasedThisTurn(id: Int): Boolean = transaction {
        // Phase guard prevents accepting a purchase that arrives after endTurn committed
        Games.update({
            (Games.id eq id) and
                (Games.turnPhase eq TurnPhase.BUY_OR_BUILD) and
                (Games.hasPurchasedThisTurn eq false)
        }) {
            it[hasPurchasedThisTurn] = true
        } > 0
    }

    /**
     * Atomically marks the current turn as having used Business Center.
     */
    fun tryMarkBusinessCenterUsedThisTurn(id: Int): Boolean = transaction {
        Games.update({
            (Games.id eq id) and
                (Games.businessCenterUsedThisTurn eq false)
        }) {
            it[businessCenterUsedThisTurn] = true
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
            it[Games.lastDiceCount] = null
            it[Games.hasPurchasedThisTurn] = false
            it[Games.businessCenterUsedThisTurn] = false
            it[Games.rerolledThisTurn] = false
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
