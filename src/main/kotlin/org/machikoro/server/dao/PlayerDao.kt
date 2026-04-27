package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.entities.GameEntity
import org.machikoro.server.database.entities.PlayerEntity
import org.machikoro.server.database.Players
import org.machikoro.server.database.entities.UserEntity
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Repository

/**
 * Data Access Object (DAO) for Player-related database operations.
 *
 * Encapsulates all database access for Player entities, providing methods to
 * retrieve, create, and update player data. This DAO uses Exposed DAO entities
 * and transactions to ensure safe and isolated persistence operations.
 *
 * Note: Some methods may appear unused but are kept for future extensibility and
 * will be reviewed in Sprint 3.
 */
@Repository
class PlayerDao {
    /**
     * Finds a player by their unique ID.
     * @param id Player ID
     * @return PlayerModel or null if not found
     */
    fun findById(id: Int): PlayerModel? = transaction {
        PlayerEntity.findById(id)?.toModel()
    }

    /**
     * Retrieves all players in a given game.
     * @param gameId Game ID
     * @return List of PlayerModel
     */
    fun getPlayers(gameId: Int): List<PlayerModel> = transaction {
        PlayerEntity.find { Players.gameId eq gameId }
            .orderBy(Players.turnOrder to SortOrder.ASC)
            .map { it.toModel() }
    }
    /**
     * Returns counter of players in game who didn't leave yet
     */
    fun countByGameId(gameId: Int): Int = transaction {
        PlayerEntity.find { Players.gameId eq gameId }.count().toInt()
    }

    /**
     * Adds a new player to a game.
     * @param gameId Game ID
     * @param userId User ID
     * @return The created PlayerModel
     */
    fun addPlayer(gameId: Int, userId: Int): PlayerModel {
        val turnOrder = getPlayers(gameId).size
        val playerId = transaction {
            PlayerEntity.new {
                this.game = GameEntity.findById(gameId) ?: error("Game not found")
                this.user = UserEntity.findById(userId) ?: error("User not found")
                this.turnOrder = turnOrder
                this.coins = 3
            }.id.value
        }
        return findById(playerId)!!
    }

    /**
     * Updates the coin count for a player.
     * @param playerId Player ID
     * @param newCoins New coin value
     */
    fun updateCoins(playerId: Int, newCoins: Int): Unit = transaction {
        PlayerEntity.findById(playerId)?.let {
            it.coins = newCoins
        }
    }

    // --- The following methods are kept for future use and will be reviewed in Sprint 3 ---

    /**
     * Finds all players in the database.
     * @return List of PlayerModel
     */
    fun findAll(): List<PlayerModel> = transaction {
        PlayerEntity.all().map { it.toModel() }
    }

    /**
     * Deletes a player by their ID.
     * @param playerId Player ID
     */
    fun delete(playerId: Int): Unit = transaction {
        PlayerEntity.findById(playerId)?.delete()
    }

    /**
     * Updates the turn order for a player.
     * @param playerId Player ID
     * @param newOrder New turn order
     */
    fun updateTurnOrder(playerId: Int, newOrder: Int): Unit = transaction {
        PlayerEntity.findById(playerId)?.let {
            it.turnOrder = newOrder
        }
    }
}