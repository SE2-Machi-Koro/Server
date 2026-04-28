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

    /*
    Maps a raw database row (ResultRow) to a domain models
    Needed because this DAO uses Exposed DSL instead of DAO entities
     */
    private fun ResultRow.toPlayerCardModel() = PlayerCardModel(
        playerId = this[PlayerCards.playerId].value,
        cardType = this[PlayerCards.cardType],
        quantity = this[PlayerCards.quantity]
    )

    /**
     * Finds all cards owned by a specific player
     */
    fun findByPlayerId(playerId: Int): List<PlayerCardModel> = transaction {
        PlayerCards.selectAll()
            .where { PlayerCards.playerId eq playerId }
            .map { it.toPlayerCardModel() }
    }

    /**
     * Inserts or updates a player's card entity
     *
     * If quantity < 0 -> Entry is deleted
     * Otherwise entry is inserted or updated
     *
     * If playerId, cardType exists -> update quantity
     * If not -> insert new row
     */
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

    /**
     * Finds all player card entries across all players
     */
    fun findAll(): List<PlayerCardModel> = transaction {
        PlayerCards.selectAll()
            .map { it.toPlayerCardModel() }
    }

    /**
     * Deletes a specific card entry for a player
     */
    fun delete(playerId: Int, cardType: CardType): Unit = transaction {
        PlayerCards.deleteWhere {
            (PlayerCards.playerId eq playerId) and
                    (PlayerCards.cardType eq cardType)
        }
    }
}