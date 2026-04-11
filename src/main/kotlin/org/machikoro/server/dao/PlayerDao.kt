package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Players
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Repository

@Repository
class PlayerDao {

    private fun ResultRow.toPlayerModel() = PlayerModel(
        id = this[Players.id].value,
        gameId = this[Players.gameId].value,
        userId = this[Players.userId].value,
        turnOrder = this[Players.turnOrder],
        coins = this[Players.coins]
    )

    fun findById(id: Int): PlayerModel? = transaction {
        Players.selectAll()
            .where { Players.id eq id }
            .singleOrNull()
            ?.toPlayerModel()
    }

    fun findByGameId(gameId: Int): List<PlayerModel> = transaction {
        Players.selectAll()
            .where { Players.gameId eq gameId }
            .map { it.toPlayerModel() }
    }

    fun findByUserIdAndGameId(userId: Int, gameId: Int): PlayerModel? = transaction {
        Players.selectAll()
            .where { (Players.userId eq userId) and (Players.gameId eq gameId) }
            .singleOrNull()
            ?.toPlayerModel()
    }

    fun create(gameId: Int, userId: Int, turnOrder: Int): Int = transaction {
        Players.insert {
            it[Players.gameId] = gameId
            it[Players.userId] = userId
            it[Players.turnOrder] = turnOrder
            it[Players.coins] = 3
        }[Players.id].value
    }

    fun updateCoins(id: Int, coins: Int): Unit = transaction {
        Players.update({ Players.id eq id }) {
            it[Players.coins] = coins
        }
    }
}