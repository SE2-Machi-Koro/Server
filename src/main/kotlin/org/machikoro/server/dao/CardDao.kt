package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.Cards
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.CardModel
import org.springframework.stereotype.Repository

@Repository
class CardDao {

    private fun ResultRow.toModel() = CardModel(
        id = this[Cards.id].value,
        cardType = this[Cards.cardType],
        cost = this[Cards.cost],
        diceMin = this[Cards.diceMin],
        diceMax = this[Cards.diceMax],
        income = this[Cards.income]
    )

    /**
     * Finds all available card definitions from database
     */
    fun findAll(): List<CardModel> = transaction {
        Cards.selectAll().map { it.toModel() }
    }

    /**
     * Find card by its unique CardType
     * Returns null if no matching card exists
     */
    fun findByCardType(cardType: CardType): CardModel? = transaction {
        Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()
            ?.toModel()
    }
}