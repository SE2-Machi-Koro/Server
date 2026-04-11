package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.GameMarketplace
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.GameMarketplaceModel
import org.springframework.stereotype.Repository

@Repository
class GameMarketplaceDao {

    private fun ResultRow.toMarketplaceEntryModel() = GameMarketplaceModel(
        gameId = this[GameMarketplace.gameId].value,
        cardType = this[GameMarketplace.cardType],
        quantityAvailable = this[GameMarketplace.quantityAvailable]
    )

    fun findByGameId(gameId: Int): List<GameMarketplaceModel> = transaction {
        GameMarketplace.selectAll()
            .where { GameMarketplace.gameId eq gameId }
            .map { it.toMarketplaceEntryModel() }
    }

    fun findByGameIdAndType(gameId: Int, cardType: CardType): GameMarketplaceModel? = transaction {
        GameMarketplace.selectAll()
            .where {
                (GameMarketplace.gameId eq gameId) and
                        (GameMarketplace.cardType eq cardType)
            }
            .singleOrNull()
            ?.toMarketplaceEntryModel()
    }

    fun initForGame(gameId: Int, supplyPerCard: Int = 6): Unit = transaction {
        CardType.entries.forEach { type ->
            GameMarketplace.insert {
                it[GameMarketplace.gameId] = gameId
                it[GameMarketplace.cardType] = type
                it[GameMarketplace.quantityAvailable] = supplyPerCard
            }
        }
    }

    fun decrementQuantity(gameId: Int, cardType: CardType): Unit = transaction {
        GameMarketplace.update({
            (GameMarketplace.gameId eq gameId) and
                    (GameMarketplace.cardType eq cardType)
        }) {
            it[GameMarketplace.quantityAvailable] = GameMarketplace.quantityAvailable - 1
        }
    }

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
}