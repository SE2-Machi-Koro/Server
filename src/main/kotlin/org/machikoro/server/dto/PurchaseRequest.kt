package org.machikoro.server.dto

import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType

/** Distinguishes whether the active player is buying an establishment or a landmark. */
enum class PurchaseType {
    ESTABLISHMENT,
    LANDMARK,
}

/**
 * WebSocket payload for one buy/build action during [org.machikoro.server.domain.enums.TurnPhase.BUY_OR_BUILD].
 *
 * Exactly one target field must be set:
 * - [cardType] for [PurchaseType.ESTABLISHMENT]
 * - [landmarkType] for [PurchaseType.LANDMARK]
 */
data class PurchaseRequest(
    val gameId: Int,
    val purchaseType: PurchaseType,
    val cardType: CardType? = null,
    val landmarkType: LandmarkType? = null,
)
