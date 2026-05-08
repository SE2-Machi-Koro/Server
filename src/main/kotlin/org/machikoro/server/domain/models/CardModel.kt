package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.TriggerCondition
import org.machikoro.server.domain.enums.PaymentSource

/**
 * Represents a card definition with all its properties
 * @property id The database ID of the card
 * @property cardType The unique card type (WHEAT_FIELD, BAKERY, etc.)
 * @property cost The coin cost to purchase this card
 * @property income The coin income generated when activated
 * @property establishmentType The card symbol/icon (WHEAT, BREAD, CUP, etc.)
 * @property triggerCondition When the card activates (OWN_TURN, ANY_TURN, OTHER_TURN, NONE)
 * @property paymentSource Where payment comes from (BANK, ACTIVE_PLAYER, ALL_PLAYERS)
 * @property activationNumbers The dice numbers that trigger this card (queried separately)
 */
data class CardModel(
    val id: Int,
    val cardType: CardType,
    val cost: Int,
    val income: Int,
    val establishmentType: EstablishmentType?,
    val triggerCondition: TriggerCondition?,
    val paymentSource: PaymentSource?,
    val activationNumbers: List<Int> = emptyList()
)