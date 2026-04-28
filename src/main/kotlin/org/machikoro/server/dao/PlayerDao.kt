package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Players
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Repository

@Repository
class PlayerDao {

    private fun ResultRow.toModel() = PlayerModel(
        id = this[Players.id].value,
        gameId = this[Players.gameId].value,
        userId = this[Players.userId].value,
        turnOrder = this[Players.turnOrder],
        coins = this[Players.coins]
    )

    /**
     * Finds a player by their unique ID.
     * @param id Player ID
     * @return PlayerModel or null if not found
     */
    fun findById(id: Int): PlayerModel? = transaction {
        Players.selectAll()
            .where { Players.id eq id }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Retrieves all players in a given game.
     * @param gameId Game ID
     * @return List of PlayerModel
     */
    fun getPlayers(gameId: Int): List<PlayerModel> = transaction {
        Players.selectAll()
            .where { Players.gameId eq gameId }
            .orderBy(Players.turnOrder to SortOrder.ASC)
            .map { it.toModel() }
    }

    /**
     * Returns counter of players in game who didn't leave yet
     */
    fun countByGameId(gameId: Int): Int = transaction {
        Players.selectAll()
            .where { Players.gameId eq gameId }
            .count()
            .toInt()
    }

    /**
     * Adds a new player to a game.
     * @param gameId Game ID
     * @param userId User ID
     * @return The created PlayerModel
     */
    fun addPlayer(gameId: Int, userId: Int): PlayerModel {
        val turnOrder = countByGameId(gameId)
        val playerId = transaction {
            Players.insertAndGetId {
                it[Players.gameId] = gameId
                it[Players.userId] = userId
                it[Players.turnOrder] = turnOrder
                it[Players.coins] = 3
            }.value
        }
        return findById(playerId)!!
    }

    /**
     * Updates the coin count for a player.
     * @param playerId Player ID
     * @param newCoins New coin value
     */
    fun updateCoins(playerId: Int, newCoins: Int): Unit = transaction {
        Players.update({ Players.id eq playerId }) {
            it[Players.coins] = newCoins
        }
    }

    // --- The following methods are kept for future use and will be reviewed in Sprint 3 ---

    /**
     * Finds all players in the database.
     * @return List of PlayerModel
     */
    fun findAll(): List<PlayerModel> = transaction {
        Players.selectAll().map { it.toModel() }
    }

    /**
     * Deletes a player by their ID.
     * @param playerId Player ID
     */
    fun delete(playerId: Int): Unit = transaction {
        Players.deleteWhere { Players.id eq playerId }
    }

    /**
     * Updates the turn order for a player.
     * @param playerId Player ID
     * @param newOrder New turn order
     */
    fun updateTurnOrder(playerId: Int, newOrder: Int): Unit = transaction {
        Players.update({ Players.id eq playerId }) {
            it[Players.turnOrder] = newOrder
        }
    }
}