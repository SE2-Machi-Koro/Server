package org.machikoro.server.domain.utils

import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType

/**
 * Maps each CardType to its EstablishmentType (card symbol).
 * These symbols are referenced directly in game rules (e.g., Shopping Mall).
 *
 * USAGE EXAMPLES:
 *
 * 1. DATABASE SEEDING - Populate the Cards table with establishment types:
 *    CardType.values().forEach { cardType ->
 *        val establishmentType = CardTypeMapping.getEstablishmentType(cardType)
 *        // INSERT card into database with establishmentType
 *    }
 *
 * 2. VALIDATION - Ensure all cards have a defined symbol (run on startup):
 *    CardTypeMapping.validateAllCardsAreMapped()
 *
 * 3. GAME LOGIC - Query cards by symbol from database:
 *    // Shopping Mall effect: gain coins from CUP or BREAD symbols
 *    db.query { Cards.select { Cards.establishmentType eq EstablishmentType.CUP } }
 *
 * 4. ADDING NEW CARDS - When adding expansion cards:
 *    CardType.NEW_CARD to EstablishmentType.SYMBOL
 *    // Then validateAllCardsAreMapped() catches missing mappings
 */
object CardTypeMapping {
    private val mapping: Map<CardType, EstablishmentType> = mapOf(
        // WHEAT symbol
        CardType.WHEAT_FIELD to EstablishmentType.WHEAT,
        CardType.APPLE_ORCHARD to EstablishmentType.WHEAT,

        // COW symbol
        CardType.RANCH to EstablishmentType.COW,

        // GEAR symbol
        CardType.FOREST to EstablishmentType.GEAR,
        CardType.MINE to EstablishmentType.GEAR,

        // BREAD symbol
        CardType.BAKERY to EstablishmentType.BREAD,
        CardType.CONVENIENCE_STORE to EstablishmentType.BREAD,

        // FACTORY symbol
        CardType.CHEESE_FACTORY to EstablishmentType.FACTORY,
        CardType.FURNITURE_FACTORY to EstablishmentType.FACTORY,

        // FRUIT symbol
        CardType.FRUIT_AND_VEGETABLE_MARKET to EstablishmentType.FRUIT,

        // CUP symbol
        CardType.CAFE to EstablishmentType.CUP,
        CardType.FAMILY_RESTAURANT to EstablishmentType.CUP,

        // MAJOR symbol
        CardType.STADIUM to EstablishmentType.MAJOR,
        CardType.TV_STATION to EstablishmentType.MAJOR,
        CardType.BUSINESS_CENTER to EstablishmentType.MAJOR
    )

    /**
     * Returns the EstablishmentType (symbol) for a given CardType.
     * @param cardType The card type to look up
     * @return The establishment type symbol, or null if not found
     */
    fun getEstablishmentType(cardType: CardType): EstablishmentType? = mapping[cardType]

    /**
     * Validates that all CardTypes have a mapping defined.
     * Useful for catching missing mappings during development.
     */
    fun validateAllCardsAreMapped(): Boolean {
        val unmappedCards = CardType.entries.filter { it !in mapping }
        return unmappedCards.isEmpty().also {
            if (!it) {
                throw IllegalStateException("Missing establishment type mappings for: ${unmappedCards.joinToString()}")
            }
        }
    }
}