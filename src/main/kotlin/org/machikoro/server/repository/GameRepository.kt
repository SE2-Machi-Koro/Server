package org.machikoro.server.repository

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Games
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.exception.GameNotFoundException
import org.springframework.stereotype.Repository

@Repository
class GameRepository {

    /** Reads the current turn phase for a given game. */
    fun getPhase(gameId: Int): TurnPhase = transaction {
        Games.selectAll()
            .where { Games.id eq gameId }
            .singleOrNull()?.get(Games.turnPhase)
            ?: throw GameNotFoundException("Game $gameId not found")
    }

    /** Updates the turn phase for a given game. */
    fun updatePhase(gameId: Int, phase: TurnPhase) {
        transaction {
            val rowsUpdated = Games.update({ Games.id eq gameId }) {
                it[turnPhase] = phase
            }
            if (rowsUpdated == 0) throw GameNotFoundException("Game $gameId not found")
        }
    }
}
