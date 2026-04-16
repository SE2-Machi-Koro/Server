package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.upsert
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.PlayerCardModel
import org.springframework.stereotype.Repository

@Repository
class PlayerCardDao {

    private fun ResultRow.toPlayerCardModel() = PlayerCardModel(
        playerId = this[PlayerCards.playerId].value,
        cardType = this[PlayerCards.cardType],
        quantity = this[PlayerCards.quantity]
    )

    fun findByPlayerId(playerId: Int): List<PlayerCardModel> = transaction {
        PlayerCards.selectAll()
            .where { PlayerCards.playerId eq playerId }
            .map { it.toPlayerCardModel() }
    }

    fun upsert(playerId: Int, cardType: CardType, quantity: Int): Unit = transaction {
        if (quantity <= 0) {
            PlayerCards.deleteWhere {
                (PlayerCards.playerId eq playerId) and
                        (PlayerCards.cardType eq cardType)
            }
        } else {
            PlayerCards.upsert(
                keys = arrayOf(PlayerCards.playerId, PlayerCards.cardType),
                onUpdate = { it[PlayerCards.quantity] = quantity }
            ) {
                it[PlayerCards.playerId] = playerId
                it[PlayerCards.cardType] = cardType
                it[PlayerCards.quantity] = quantity
            }
        }
    }

    fun findAll(): List<PlayerCardModel> = transaction {
        PlayerCards.selectAll().map {
            PlayerCardModel(
                playerId = it[PlayerCards.playerId].value,
                cardType = it[PlayerCards.cardType],
                quantity = it[PlayerCards.quantity]
            )
        }
    }

    fun delete(playerId: Int, cardType: CardType): Unit = transaction {
        PlayerCards.deleteWhere {
            (PlayerCards.playerId eq playerId) and
                    (PlayerCards.cardType eq cardType)
        }
    }
}