package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.entities.GameEntity
import org.machikoro.server.database.entities.PlayerEntity
import org.machikoro.server.database.Players
import org.machikoro.server.database.entities.UserEntity
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Repository

@Repository
class PlayerDao {

    /**
     * Finds a player by its unique ID
     */
    fun findById(id: Int): PlayerModel? = transaction {
        PlayerEntity.findById(id)?.toModel()
    }

    /**
     * Finds all players for a given game
     * Players are ordered by turnOrder to reflect the correct turn sequence
     */
    fun findByGameId(gameId: Int): List<PlayerModel> = transaction {
        PlayerEntity.find { Players.gameId eq gameId }
            .orderBy(Players.turnOrder to SortOrder.ASC)
            .map { it.toModel() }
    }

    /**
     * Finds a specific player by userId within a given game
     * Return null if user is not part of the game
     */
    fun findByUserIdAndGameId(userId: Int, gameId: Int): PlayerModel? = transaction {
        PlayerEntity.find {
            (Players.userId eq userId) and (Players.gameId eq gameId)
        }.singleOrNull()?.toModel()
    }

    /**
     * Creates a new player entry in a game
     * Initializes:
     * - turnOrder -> determines position in turn sequence
     * - coins -> starts with 3 coins
     *
     * Fails if referenced game or user does not exist
     */
    fun create(gameId: Int, userId: Int, turnOrder: Int): Int = transaction {
        PlayerEntity.new {
            game = GameEntity.findById(gameId)
                ?: error("Game $gameId not found")
            user = UserEntity.findById(userId)
                ?: error("User $userId not found")
            this.turnOrder = turnOrder
            coins = 3
        }.id.value
    }

    /**
     * Updates coin count for a player
     */
    fun updateCoins(id: Int, coins: Int): Unit = transaction {
        PlayerEntity.findById(id)?.apply {
            this.coins = coins
        }
    }

    /**
     * Finds all players across all games
     */
    fun findAll(): List<PlayerModel> = transaction {
        PlayerEntity.all().map { it.toModel() }
    }

    /**
     * Updates the turn order of a player
     * Affects sequence in which players take turn
     */
    fun updateTurnOrder(id: Int, turnOrder: Int): Unit = transaction {
        PlayerEntity.findById(id)?.apply {
            this.turnOrder = turnOrder
        }
    }

    /**
     * Deletes a player by ID
     */
    fun delete(id: Int): Unit = transaction {
        PlayerEntity.findById(id)?.delete()
    }
}