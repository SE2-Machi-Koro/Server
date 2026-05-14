package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.Cards
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.models.CardModel
import org.springframework.stereotype.Repository

@Repository
class CardDao(
    private val cardActivationNumbersDao: CardActivationNumbersDao
) {

    private fun ResultRow.toModel(): CardModel {
        val cardId = this[Cards.id].value
        val activationNumbers = cardActivationNumbersDao.findByCardId(cardId)

        return CardModel(
            id = cardId,
            cardType = this[Cards.cardType],
            cost = this[Cards.cost],
            income = this[Cards.income],
            color = this[Cards.color],
            establishmentType = this[Cards.establishmentType],
            paymentSource = this[Cards.paymentSource],
            activationNumbers = activationNumbers
        )
    }

    fun findAll(): List<CardModel> = transaction {
        Cards.selectAll().map { it.toModel() }
    }

    fun findByCardType(cardType: CardType): CardModel? = transaction {
        Cards.selectAll()
            .where { Cards.cardType eq cardType }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Efficiently find multiple cards by their types.
     * Useful for initializing the game marketplace.
     */
    fun findAllByCardTypes(cardTypes: Collection<CardType>): List<CardModel> = transaction {
        Cards.selectAll()
            .where { Cards.cardType inList cardTypes }
            .map { it.toModel() }
    }

    fun findByActivationNumber(diceNumber: Int): List<CardModel> = transaction {
        val cardIds = cardActivationNumbersDao.findCardsByActivationNumber(diceNumber)
        if (cardIds.isEmpty()) return@transaction emptyList()

        Cards.selectAll()
            .where { Cards.id inList cardIds }
            .map { it.toModel() }
    }

    /**
     * Finds cards by their symbol (EstablishmentType).
     * Used for cards like "Furniture Factory" (GEAR) or "Cheese Factory" (COW).
     */
    fun findByEstablishmentType(type: EstablishmentType): List<CardModel> = transaction {
        Cards.selectAll()
            .where { Cards.establishmentType eq type }
            .map { it.toModel() }
    }

    /**
     * Finds cards by their color.
     * Useful for resolving logic specific to certain colors (e.g., all Blue cards).
     */
    fun findByColor(color: CardColor): List<CardModel> = transaction {
        Cards.selectAll()
            .where { Cards.color eq color }
            .map { it.toModel() }
    }
}