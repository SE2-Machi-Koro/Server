package org.machikoro.server.dao

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

    fun getPlayers(gameId: Int): List<PlayerModel> = transaction {
        PlayerEntity.find { Players.gameId eq gameId }.map { it.toModel() }
    }

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

    fun updateCoins(playerId: Int, newCoins: Int): Unit = transaction {
        PlayerEntity.findById(playerId)?.let {
            it.coins = newCoins
        }
    }
}