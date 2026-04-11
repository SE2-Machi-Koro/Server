package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.CardType

data class PlayerCardModel(
    val playerId: Int,
    val cardType: CardType,
    val quantity: Int
)