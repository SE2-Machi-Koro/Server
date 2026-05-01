package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.machikoro.server.database.Cards
import org.machikoro.server.database.PlayerCards
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.exception.CardNotFoundException
import org.springframework.stereotype.Repository

@Repository
class PlayerCardDao {

    /**
     * Maps a raw database row (ResultRow) to a domain model
     * Requires a join with Cards to resolve cardType from cardId
     */
    private fun ResultRow.toPlayerCardModel() = PlayerCardModel(
        playerId = this[PlayerCards.playerId].value,
        cardType = this[Cards.cardType],
        quantity = this[PlayerCards.quantity]
    )

    /**
     * Finds all cards owned by a specific player
     */
    fun findByPlayerId(playerId: Int): List<PlayerCardModel> = transaction {
        (PlayerCards innerJoin Cards)
            .selectAll()
            .where { PlayerCards.playerId eq playerId }
            .map { it.toPlayerCardModel() }
    }

    /**
     * Finds all player card entries across all players
     */
    fun findAll(): List<PlayerCardModel> = transaction {
        (PlayerCards innerJoin Cards)
            .selectAll()
            .map { it.toPlayerCardModel() }
    }

    /**
     * Inserts or updates a player's card entry
     *
     * If quantity <= 0 -> entry is deleted
     * Otherwise entry is inserted or updated
     */
    fun upsert(playerId: Int, cardType: CardType, quantity: Int): Unit = transaction {
        val cardId = Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()?.get(Cards.id) ?: throw CardNotFoundException("Card type $cardType not found in database")

        if (quantity <= 0) {
            PlayerCards.deleteWhere {
                (PlayerCards.playerId eq playerId) and
                        (PlayerCards.cardId eq cardId)
            }
        } else {
            PlayerCards.upsert(
                keys = arrayOf(PlayerCards.playerId, PlayerCards.cardId),
                onUpdate = { it[PlayerCards.quantity] = quantity }
            ) {
                it[PlayerCards.playerId] = playerId
                it[PlayerCards.cardId] = cardId
                it[PlayerCards.quantity] = quantity
            }
        }
    }

    /**
     * Deletes a specific card entry for a player
     *
     * @throws CardNotFoundException if the card type doesn't exist in the database
     */
    fun delete(playerId: Int, cardType: CardType): Unit = transaction {
        val cardId = Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()?.get(Cards.id)
            ?: throw CardNotFoundException("Card type $cardType not found in database")

        PlayerCards.deleteWhere {
            (PlayerCards.playerId eq playerId) and
                    (PlayerCards.cardId eq cardId)
        }
    }

    /**
     * Deletes all card entry for a player
     */
    fun deleteAllByPlayerId(playerId: Int) = transaction {
        PlayerCards.deleteWhere {
            PlayerCards.playerId eq playerId
        }
    }
}
