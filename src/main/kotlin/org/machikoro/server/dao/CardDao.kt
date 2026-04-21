package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.Cards
import org.machikoro.server.database.entities.CardEntity
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.CardModel
import org.springframework.stereotype.Repository

/**
 * Data Access Object responsible for interacting with the database
 * - Encapsulated all database operations
 * - Uses Exposed entities and transactions to access persistence layer
 * - Converts database entities into domain models via toModel()
 *
 * - Only layer that should directly access Exposed/DB tables
 * - Returns domain models instead of entities to keep persistence isolated
 * - All operations are executed inside a transaction
 *
 * DAOs are used by the service layer to retrieve and modify the game state
 */
@Repository
class CardDao {

    /**
     * Finds all available card definitions from database
     */
    fun findAll(): List<CardModel> = transaction {
        CardEntity.all().map { it.toModel() }
    }

    /**
     * Find card by its unique CardType
     * Returns null if no matching card exists
     */
    fun findByCardType(cardType: CardType): CardModel? = transaction {
        CardEntity.find { Cards.cardType eq cardType }
            .singleOrNull()
            ?.toModel()
    }
}