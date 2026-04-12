package org.machikoro.server.dao

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

    fun findById(id: Int): PlayerModel? = transaction {
        PlayerEntity.findById(id)?.toModel()
    }

    fun findByGameId(gameId: Int): List<PlayerModel> = transaction {
        PlayerEntity.find { Players.gameId eq gameId }
            .map { it.toModel() }
    }

    fun findByUserIdAndGameId(userId: Int, gameId: Int): PlayerModel? = transaction {
        PlayerEntity.find {
            (Players.userId eq userId) and (Players.gameId eq gameId)
        }.singleOrNull()?.toModel()
    }

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

    fun updateCoins(id: Int, coins: Int): Unit = transaction {
        PlayerEntity.findById(id)?.apply {
            this.coins = coins
        }
    }

    fun findAll(): List<PlayerModel> = transaction {
        PlayerEntity.all().map { it.toModel() }
    }

    fun updateTurnOrder(id: Int, turnOrder: Int): Unit = transaction {
        PlayerEntity.findById(id)?.apply {
            this.turnOrder = turnOrder
        }
    }

    fun delete(id: Int): Unit = transaction {
        PlayerEntity.findById(id)?.delete()
    }
}