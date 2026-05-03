package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.stereotype.Service

@Service
class PurchaseService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val cardDao: CardDao,
    private val landmarkDao: LandmarkDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
    private val playerCardDao: PlayerCardDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameStateGuard: GameStateGuard,
) {

    /**
     * Executes the single allowed purchase during the active player's
     * BUY_OR_BUILD phase.
     *
     * The full flow runs in one transaction so coin changes, ownership changes,
     * supply updates, and purchase-state enforcement succeed or fail together.
     */
    fun purchase(
        gameId: Int,
        purchaseType: PurchaseType,
        cardType: CardType?,
        landmarkType: LandmarkType?,
    ): PurchaseResult = transaction {
        val game = gameStateGuard.ensureGameIsRunning(gameId)

        if (game.turnPhase != TurnPhase.BUY_OR_BUILD) {
            throw CustomWebSocketException(
                errorCode = "INVALID_TURN_PHASE",
                message = "Purchases are only allowed during BUY_OR_BUILD",
            )
        }

        val activePlayer = playerDao
            .getPlayers(gameId)
            .getOrNull(game.currentTurnIndex)
            ?: throw CustomWebSocketException(
                errorCode = "ACTIVE_PLAYER_NOT_FOUND",
                message = "Could not resolve the active player for game $gameId",
            )

        val target = resolvePurchaseTarget(purchaseType, cardType, landmarkType)

        if (!gameDao.tryMarkPurchasedThisTurn(gameId)) {
            throw CustomWebSocketException(
                errorCode = "PURCHASE_ALREADY_MADE",
                message = "Only one purchase is allowed per turn",
            )
        }

        when (target) {
            is PurchaseTarget.Establishment ->
                purchaseEstablishment(
                    gameId,
                    activePlayer,
                    target.cardType,
                )

            is PurchaseTarget.Landmark ->
                purchaseLandmark(
                    activePlayer,
                    target.landmarkType,
                )
        }
    }

    /**
     * Normalizes the WebSocket request into one concrete internal target before
     * the purchase-specific helpers run.
     */
    private fun resolvePurchaseTarget(
        purchaseType: PurchaseType,
        cardType: CardType?,
        landmarkType: LandmarkType?,
    ): PurchaseTarget =
        when (purchaseType) {
            PurchaseType.ESTABLISHMENT -> {
                if (cardType == null || landmarkType != null) {
                    throw invalidPurchaseRequest(
                        "Establishment purchase requires cardType only"
                    )
                }
                PurchaseTarget.Establishment(cardType)
            }

            PurchaseType.LANDMARK -> {
                if (landmarkType == null || cardType != null) {
                    throw invalidPurchaseRequest(
                        "Landmark purchase requires landmarkType only"
                    )
                }
                PurchaseTarget.Landmark(landmarkType)
            }
        }

    /**
     * Buys one establishment card for the active player and removes that card
     * from the current game's marketplace supply.
     */
    private fun purchaseEstablishment(
        gameId: Int,
        activePlayer: PlayerModel,
        cardType: CardType,
    ): PurchaseResult {
        val card = cardDao.findByCardType(cardType)
            ?: throw CustomWebSocketException(
                "CARD_NOT_FOUND",
                "Card $cardType does not exist"
            )

        if (activePlayer.coins < card.cost) {
            throw CustomWebSocketException(
                "INSUFFICIENT_COINS",
                "Player does not have enough coins"
            )
        }

        val entry = gameMarketplaceDao.findByGameIdAndType(gameId, cardType)

        if (entry == null || entry.quantityAvailable <= 0) {
            throw CustomWebSocketException(
                "CARD_UNAVAILABLE",
                "Card $cardType is not available in supply"
            )
        }

        val currentQuantity = playerCardDao
            .findByPlayerId(activePlayer.id)
            .firstOrNull { it.cardType == cardType }
            ?.quantity
            ?: 0

        playerDao.updateCoins(
            activePlayer.id,
            activePlayer.coins - card.cost
        )

        playerCardDao.upsert(
            activePlayer.id,
            cardType,
            currentQuantity + 1
        )

        gameMarketplaceDao.decrementQuantity(gameId, cardType)

        return PurchaseResult(
            turnPhase = TurnPhase.BUY_OR_BUILD,
            purchaseType = PurchaseType.ESTABLISHMENT,
            cardType = cardType,
        )
    }

    /**
     * Builds one landmark for the active player. Landmark rows are expected to
     * exist already from game start initialization.
     */
    private fun purchaseLandmark(
        activePlayer: PlayerModel,
        landmarkType: LandmarkType,
    ): PurchaseResult {
        val landmark = landmarkDao.findByLandmarkType(landmarkType)
            ?: throw CustomWebSocketException(
                "LANDMARK_NOT_FOUND",
                "Landmark $landmarkType does not exist"
            )

        if (activePlayer.coins < landmark.cost) {
            throw CustomWebSocketException(
                "INSUFFICIENT_COINS",
                "Player does not have enough coins"
            )
        }

        val playerLandmark = playerLandmarkDao
            .findByPlayerIdAndType(activePlayer.id, landmarkType)
            ?: throw CustomWebSocketException(
                "LANDMARK_NOT_FOUND",
                "Landmark state for $landmarkType could not be initialized"
            )

        if (playerLandmark.isBuilt) {
            throw CustomWebSocketException(
                "LANDMARK_ALREADY_BUILT",
                "Landmark $landmarkType has already been built"
            )
        }

        playerDao.updateCoins(
            activePlayer.id,
            activePlayer.coins - landmark.cost
        )

        playerLandmarkDao.markBuilt(
            activePlayer.id,
            landmarkType
        )

        return PurchaseResult(
            turnPhase = TurnPhase.BUY_OR_BUILD,
            purchaseType = PurchaseType.LANDMARK,
            landmarkType = landmarkType,
        )
    }

    private fun invalidPurchaseRequest(message: String) =
        CustomWebSocketException(
            "INVALID_PURCHASE_REQUEST",
            message
        )

    private sealed interface PurchaseTarget {
        data class Establishment(val cardType: CardType) : PurchaseTarget
        data class Landmark(val landmarkType: LandmarkType) : PurchaseTarget
    }
}

/** Minimal result payload broadcast back to clients after a successful purchase. */
data class PurchaseResult(
    val turnPhase: TurnPhase,
    val purchaseType: PurchaseType,
    val cardType: CardType? = null,
    val landmarkType: LandmarkType? = null,
)
