package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.Cards
import org.machikoro.server.database.entities.CardEntity
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.CardModel
import org.springframework.stereotype.Repository

@Repository
class CardDao {

    fun findAll(): List<CardModel> = transaction {
        CardEntity.all().map { it.toModel() }
    }

    fun findByCardType(cardType: CardType): CardModel? = transaction {
        CardEntity.find { Cards.cardType eq cardType }
            .singleOrNull()
            ?.toModel()
    }
}