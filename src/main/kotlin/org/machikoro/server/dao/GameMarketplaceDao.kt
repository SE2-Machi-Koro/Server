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
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.GameMarketplaceModel
import org.springframework.stereotype.Repository

@Repository
class GameMarketplaceDao {

    /**
     * Maps a raw database row (ResultRow) to a domain models
     * Needed because this DAO uses Exposed DSL instead of DAO entities
     */
    private fun ResultRow.toMarketplaceEntryModel() = GameMarketplaceModel(
        gameId = this[GameMarketplace.gameId].value,
        cardType = this[GameMarketplace.cardType],
        quantityAvailable = this[GameMarketplace.quantityAvailable]
    )

    /**
     * Finds all marketplace entries for a given game
     */
    fun findByGameId(gameId: Int): List<GameMarketplaceModel> = transaction {
        GameMarketplace.selectAll()
            .where { GameMarketplace.gameId eq gameId }
            .map { it.toMarketplaceEntryModel() }
    }

    /**
     * Retrieves a specific cad entry from marketplace for a game
     * Returns null if card is not present
     */
    fun findByGameIdAndType(gameId: Int, cardType: CardType): GameMarketplaceModel? = transaction {
        GameMarketplace.selectAll()
            .where {
                (GameMarketplace.gameId eq gameId) and
                        (GameMarketplace.cardType eq cardType)
            }
            .singleOrNull()
            ?.toMarketplaceEntryModel()
    }

    /**
     * Initializes a marketplace for a new game
     * For each CardType, a supply entry is created with default quantity
     * Prevents errors if entries already exist
     */
    fun initForGame(gameId: Int, supplyPerCard: Int = 6): Unit = transaction {
        CardType.entries.forEach { type ->
            GameMarketplace.insertIgnore {
                it[GameMarketplace.gameId] = gameId
                it[GameMarketplace.cardType] = type
                it[GameMarketplace.quantityAvailable] = supplyPerCard
            }
        }
    }

    /**
     * Decreases available quantity of a card by 1
     * Only updates if quantity > 0
     */
    fun decrementQuantity(gameId: Int, cardType: CardType): Boolean = transaction {
        val updatedRows = GameMarketplace.update({
            (GameMarketplace.gameId eq gameId) and
                    (GameMarketplace.cardType eq cardType) and
                    (GameMarketplace.quantityAvailable greater 0)
        }) {
            it[GameMarketplace.quantityAvailable] = GameMarketplace.quantityAvailable - 1
        }
        updatedRows > 0
    }

    /**
     * Checks whether a card is still available in marketplace
     */
    fun isAvailable(gameId: Int, cardType: CardType): Boolean = transaction {
        GameMarketplace.selectAll()
            .where {
                (GameMarketplace.gameId eq gameId) and
                        (GameMarketplace.cardType eq cardType)
            }
            .singleOrNull()
            ?.let { it[GameMarketplace.quantityAvailable] > 0 }
            ?: false
    }

    /**
     * Finds all marketplace entries across all games
     */
    fun findAll(): List<GameMarketplaceModel> = transaction {
        GameMarketplace.selectAll()
            .map { it.toMarketplaceEntryModel() }
    }

    /**
     * Sets available quantity for a specific card in a game
     */
    fun updateQuantity(gameId: Int, cardType: CardType, quantity: Int): Unit = transaction {
        GameMarketplace.update({
            (GameMarketplace.gameId eq gameId) and
                    (GameMarketplace.cardType eq cardType)
        }) {
            it[GameMarketplace.quantityAvailable] = quantity
        }
    }

    /**
     * Deletes a specific card entry from a game's marketplace
     */
    fun delete(gameId: Int, cardType: CardType): Unit = transaction {
        GameMarketplace.deleteWhere {
            (GameMarketplace.gameId eq gameId) and
                    (GameMarketplace.cardType eq cardType)
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