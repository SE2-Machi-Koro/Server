package org.machikoro.server.dto

import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType

enum class PurchaseType {
    ESTABLISHMENT,
    LANDMARK,
}

data class PurchaseRequest(
    val gameId: Int,
    val purchaseType: PurchaseType,
    val cardType: CardType? = null,
    val landmarkType: LandmarkType? = null,
)
