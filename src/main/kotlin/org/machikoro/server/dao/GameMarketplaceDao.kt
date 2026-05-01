package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Cards
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.GameMarketplaceModel
import org.springframework.stereotype.Repository

@Repository
class GameMarketplaceDao {

    /**
     * Maps a raw database row (ResultRow) to a domain model
     * Requires a join with Cards to resolve cardType from cardId
     */
    private fun ResultRow.toMarketplaceEntryModel() = GameMarketplaceModel(
        gameId = this[GameMarketplace.gameId].value,
        cardType = this[Cards.cardType],
        quantityAvailable = this[GameMarketplace.quantityAvailable]
    )

    /**
     * Finds all marketplace entries for a given game
     */
    fun findByGameId(gameId: Int): List<GameMarketplaceModel> = transaction {
        (GameMarketplace innerJoin Cards)
            .selectAll()
            .where { GameMarketplace.gameId eq gameId }
            .map { it.toMarketplaceEntryModel() }
    }

    /**
     * Retrieves a specific card entry from marketplace for a game
     * Returns null if card is not present
     */
    fun findByGameIdAndType(gameId: Int, cardType: CardType): GameMarketplaceModel? = transaction {
        (GameMarketplace innerJoin Cards)
            .selectAll()
            .where {
                (GameMarketplace.gameId eq gameId) and
                        (Cards.cardType eq cardType)
            }
            .singleOrNull()
            ?.toMarketplaceEntryModel()
    }

    /**
     * Initializes a marketplace for a new game
     * Looks up each CardType's id from Cards table and creates a supply entry
     * Prevents errors if entries already exist
     */
    fun initForGame(gameId: Int, supplyPerCard: Int = 6): Unit = transaction {
        val cardIdByType = Cards.selectAll()
            .associate { it[Cards.cardType] to it[Cards.id] }

        CardType.entries.forEach { type ->
            val cardId = cardIdByType[type] ?: return@forEach
            GameMarketplace.insertIgnore {
                it[GameMarketplace.gameId] = gameId
                it[GameMarketplace.cardId] = cardId
                it[GameMarketplace.quantityAvailable] = supplyPerCard
            }
        }
    }

    /**
     * Decreases available quantity of a card by 1
     * Only updates if quantity > 0
     */
    fun decrementQuantity(gameId: Int, cardType: CardType): Boolean = transaction {
        val cardId = Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()?.get(Cards.id) ?: return@transaction false

        val updatedRows = GameMarketplace.update({
            (GameMarketplace.gameId eq gameId) and
                    (GameMarketplace.cardId eq cardId) and
                    (GameMarketplace.quantityAvailable greater 0)
        }) {
            it[GameMarketplace.quantityAvailable] = GameMarketplace.quantityAvailable - 1
        }
        updatedRows > 0
    }

    /**
     * Checks whether a card is still available in the marketplace
     */
    fun isAvailable(gameId: Int, cardType: CardType): Boolean = transaction {
        val cardId = Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()?.get(Cards.id) ?: return@transaction false

        (GameMarketplace innerJoin Cards)
            .selectAll()
            .where {
                (GameMarketplace.gameId eq gameId) and
                        (GameMarketplace.cardId eq cardId)
            }
            .singleOrNull()
            ?.let { it[GameMarketplace.quantityAvailable] > 0 }
            ?: false
    }

    /**
     * Finds all marketplace entries across all games
     */
    fun findAll(): List<GameMarketplaceModel> = transaction {
        (GameMarketplace innerJoin Cards)
            .selectAll()
            .map { it.toMarketplaceEntryModel() }
    }

    /**
     * Sets available quantity for a specific card in a game
     */
    fun updateQuantity(gameId: Int, cardType: CardType, quantity: Int): Unit = transaction {
        val cardId = Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()?.get(Cards.id) ?: return@transaction

        GameMarketplace.update({
            (GameMarketplace.gameId eq gameId) and
                    (GameMarketplace.cardId eq cardId)
        }) {
            it[GameMarketplace.quantityAvailable] = quantity
        }
    }

    /**
     * Deletes a specific card entry from a game's marketplace
     */
    fun delete(gameId: Int, cardType: CardType): Unit = transaction {
        val cardId = Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()?.get(Cards.id) ?: return@transaction

        GameMarketplace.deleteWhere {
            (GameMarketplace.gameId eq gameId) and
                    (GameMarketplace.cardId eq cardId)
        }
    }

    /**
     * Deletes all marketplace entries for a given game
     */
    fun deleteAllForGame(gameId: Int): Unit = transaction {
        GameMarketplace.deleteWhere {
            GameMarketplace.gameId eq gameId
        }
    }
}