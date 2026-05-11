package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.database.CardActivationNumbers
import org.springframework.stereotype.Repository

@Repository
class CardActivationNumbersDao {

    /**
     * Find all activation numbers for a specific card
     * @param cardId The card ID to look up
     * @return List of dice numbers that trigger this card
     */
    fun findByCardId(cardId: Int): List<Int> = transaction {
        CardActivationNumbers
            .selectAll()
            .where { CardActivationNumbers.cardId eq cardId }
            .map { it[CardActivationNumbers.number] }
            .sorted()
    }

    /**
     * Find all cards that activate on a specific dice number
     * @param diceNumber The dice roll to check
     * @return List of card IDs that activate on this number
     */
    fun findCardsByActivationNumber(diceNumber: Int): List<Int> = transaction {
        CardActivationNumbers
            .selectAll()
            .where { CardActivationNumbers.number eq diceNumber }
            .map { it[CardActivationNumbers.cardId].value }
    }
}


