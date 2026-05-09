package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.PaymentSource

/**
 * Represents a card definition with all its properties
 * @property id The database ID of the card
 * @property cardType The unique card type (WHEAT_FIELD, BAKERY, etc.)
 * @property cost The coin cost to purchase this card
 * @property income The coin income generated when activated
 * @property establishmentType The card symbol/icon (WHEAT, BREAD, CUP, etc.)
 * @property color The color of a card (BLUE, GREEN, RED, PURPLE)
 * @property paymentSource Where payment comes from (BANK, ACTIVE_PLAYER, ALL_PLAYERS)
 * @property activationNumbers The dice numbers that trigger this card (queried separately)
 */
data class CardModel(
    val id: Int,
    val cardType: CardType,
    val cost: Int,
    val income: Int,
    val color: CardColor,
    val establishmentType: EstablishmentType,
    val paymentSource: PaymentSource,
    val activationNumbers: List<Int> = emptyList()
)