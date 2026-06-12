package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.CheatFlags
import org.springframework.stereotype.Repository

/**
 * Persists which players have an outstanding (uncaught) Insider Trading cheat
 * (issue #361). A row present means "this player cheated and has not been caught
 * yet". Durable in Postgres so a cheat reported before a restart stays catchable.
 */
@Repository
class CheatFlagDao {

    /**
     * Marks [playerId] as having an outstanding cheat. Idempotent — reporting the
     * cheat twice keeps a single row, so a player who cheats repeatedly without
     * being caught is still "caught at most once" per consume.
     */
    fun setOutstanding(playerId: Int): Unit = transaction {
        CheatFlags.insertIgnore { it[CheatFlags.playerId] = playerId }
    }

    /**
     * Atomically consumes [playerId]'s outstanding cheat. Returns the number of
     * rows removed (1 if there was an outstanding cheat, 0 otherwise). The count
     * makes adjudication race-safe: only the accusation whose delete affected a
     * row counts as a catch, so two accusers cannot double-penalize one cheater.
     */
    fun consume(playerId: Int): Int = transaction {
        CheatFlags.deleteWhere { CheatFlags.playerId eq playerId }
    }

    /** Test/diagnostic helper: whether [playerId] currently has an outstanding cheat. */
    fun hasOutstanding(playerId: Int): Boolean = transaction {
        CheatFlags.selectAll().where { CheatFlags.playerId eq playerId }.count() > 0
    }
}
