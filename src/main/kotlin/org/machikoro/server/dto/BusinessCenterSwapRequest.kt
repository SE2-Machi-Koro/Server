package org.machikoro.server.dto

import org.machikoro.server.domain.enums.CardType

data class BusinessCenterSwapRequest(
    val gameId: Int,
    val targetPlayerId: Int,
    val offeredCardType: CardType,
    val requestedCardType: CardType,
)
