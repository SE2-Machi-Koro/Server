package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType

/** Distinguishes whether the active player is buying an establishment or a landmark. */
@Schema(description = "Whether the active player is buying an establishment or building a landmark")
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
@Schema(description = "Request to purchase an establishment or build a landmark — sent to /app/game.purchase")
data class PurchaseRequest(
    @Schema(description = "ID of the game", example = "1")
    val gameId: Int,
    @Schema(description = "Whether buying an establishment or building a landmark")
    val purchaseType: PurchaseType,
    @Schema(description = "Establishment card to buy — required when purchaseType is ESTABLISHMENT", example = "WHEAT_FIELD")
    val cardType: CardType? = null,
    @Schema(description = "Landmark to build — required when purchaseType is LANDMARK", example = "TRAIN_STATION")
    val landmarkType: LandmarkType? = null,
)
