package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.Cards
import org.machikoro.server.domain.enums.CardType
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
            establishmentType = this[Cards.establishmentType],
            triggerCondition = this[Cards.triggerCondition],
            paymentSource = this[Cards.paymentSource],
            activationNumbers = activationNumbers
        )
    }

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

    /**
     * Find cards that activate on a specific dice number
     * @param diceNumber The dice roll to check
     * @return List of cards that activate on this number
     */
    fun findByActivationNumber(diceNumber: Int): List<CardModel> = transaction {
        val cardIds = cardActivationNumbersDao.findCardsByActivationNumber(diceNumber)
        Cards.selectAll()
            .where { Cards.id inList cardIds }
            .map { it.toModel() }
    }
}