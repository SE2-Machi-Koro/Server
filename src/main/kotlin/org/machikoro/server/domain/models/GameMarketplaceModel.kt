package org.machikoro.server.domain.models

import org.machikoro.server.domain.enums.CardType

data class GameMarketplaceModel(
    val gameId: Int,
    val cardType: CardType,
    val quantityAvailable: Int
)