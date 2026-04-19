package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.CardType

data class CardModel(
    val id: Int,
    val cardType: CardType,
    val name: String,
    val cost: Int,
    val diceMin: Int,
    val diceMax: Int,
    val income: Int
)