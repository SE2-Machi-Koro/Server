package org.machikoro.server.service

import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.CardModel
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service responsible for core economic of the game
 * Handles the calculation and distribution of coins based on dice rolls and player establishments.
 *
 * Machi Koro activation order: RED → BLUE → GREEN → PURPLE
 */
@Service
class EarningsServiceImpl(
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val cardDao: CardDao,
    private val gameDao: GameDao,
    private val gameStateGuard: GameStateGuard,
    private val gameTransactionRunner: GameTransactionRunner,
    private val playerLandmarkDao: PlayerLandmarkDao,
) : EarningsService {

    fun computeEarnings(pairs: List<Pair<Int, Int>>): Int =
        pairs.sumOf { (quantity, income) -> quantity * income }

    @Transactional
    override fun processEarnings(gameId: Int, diceRoll: Int, activePlayerId: Int): Map<Int, Int> {
        val activatingCards = cardDao.findByActivationNumber(diceRoll)
            .associateBy { it.cardType }

        val players = playerDao.getPlayers(gameId)
        val finalCoins = players.associate { it.id to it.coins }.toMutableMap()

        val matchedCardsByPlayer = players.associate { player ->
            player.id to playerCardDao.findByPlayerId(player.id)
                .mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }
        }

        val hasShoppingMallByPlayerId = players.associate { player ->
            player.id to (playerLandmarkDao.findByPlayerIdAndType(player.id, LandmarkType.SHOPPING_MALL)?.isBuilt == true)
        }

        processRedCards(players, activePlayerId, matchedCardsByPlayer, finalCoins, hasShoppingMallByPlayerId)
        processBlueCards(players, matchedCardsByPlayer, finalCoins)
        processGreenCards(players, activePlayerId, matchedCardsByPlayer, finalCoins, hasShoppingMallByPlayerId)
        processPurpleCards(players, activePlayerId, matchedCardsByPlayer, finalCoins)

        players.forEach { player ->
            if (finalCoins[player.id] != player.coins) {
                playerDao.updateCoins(player.id, finalCoins.getValue(player.id))
            }
        }

        // playerId -> signed coin delta for players whose balance changed; drives
        // the client coin / coin-drawer sound effects (issue #389).
        return players
            .mapNotNull { player ->
                val delta = finalCoins.getValue(player.id) - player.coins
                if (delta != 0) player.id to delta else null
            }
            .toMap()
    }

    /**
     * RED cards (OTHER_TURN): each opponent with a matching red card steals from the active player.
     * Each theft is capped at the active player's remaining balance — no coins are created.
     */
    private fun processRedCards(
        players: List<PlayerModel>,
        activePlayerId: Int,
        matchedCardsByPlayer: Map<Int, List<Pair<PlayerCardModel, CardModel>>>,
        finalCoins: MutableMap<Int, Int>,
        hasShoppingMallByPlayerId: Map<Int, Boolean>
    ) {
        players.filter { it.id != activePlayerId }.forEach { opponent ->
            val redEarned = matchedCardsByPlayer[opponent.id].orEmpty()
                .filter { (_, card) -> card.color == CardColor.RED }
                .sumOf { (playerCard, card) ->
                    val extra = if (card.establishmentType == EstablishmentType.CUP && hasShoppingMallByPlayerId[opponent.id] == true)
                        1 else 0
                    playerCard.quantity * (card.income + extra)
                }

            if (redEarned > 0) {
                val transfer = minOf(redEarned, finalCoins.getValue(activePlayerId))
                if (transfer > 0) {
                    finalCoins[activePlayerId] = finalCoins.getValue(activePlayerId) - transfer
                    finalCoins[opponent.id] = finalCoins.getValue(opponent.id) + transfer
                }
            }
        }
    }

    /**
     * BLUE cards (ANY_TURN): all players receive income from the bank.
     */
    private fun processBlueCards(
        players: List<PlayerModel>,
        matchedCardsByPlayer: Map<Int, List<Pair<PlayerCardModel, CardModel>>>,
        finalCoins: MutableMap<Int, Int>
    ) {
        players.forEach { player ->
            val earned = matchedCardsByPlayer[player.id].orEmpty()
                .filter { (_, card) -> card.color == CardColor.BLUE }
                .sumOf { (playerCard, card) -> playerCard.quantity * card.income }

            if (earned > 0) {
                finalCoins[player.id] = finalCoins.getValue(player.id) + earned
            }
        }
    }

    /**
     * GREEN cards (OWN_TURN): only the active player receives income from the bank.
     */
    private fun processGreenCards(
        players: List<PlayerModel>,
        activePlayerId: Int,
        matchedCardsByPlayer: Map<Int, List<Pair<PlayerCardModel, CardModel>>>,
        finalCoins: MutableMap<Int, Int>,
        hasShoppingMallByPlayerId: Map<Int, Boolean>
    ) {
        val activePlayer = players.find { it.id == activePlayerId } ?: return
        val earned = matchedCardsByPlayer[activePlayer.id].orEmpty()
            .filter { (_, card) -> card.color == CardColor.GREEN }
            .sumOf { (playerCard, card) ->
                val extra = if (card.establishmentType == EstablishmentType.BREAD && hasShoppingMallByPlayerId[activePlayer.id] == true)
                    1 else 0
                playerCard.quantity * (card.income + extra)
            }

        if (earned > 0) {
            finalCoins[activePlayerId] = finalCoins.getValue(activePlayerId) + earned
        }
    }

    /**
     * PURPLE cards (OWN_TURN): only the active player benefits.
     * - BANK-sourced: active player receives income from the bank.
     * - ALL_PLAYERS-sourced (e.g. Stadium): active player steals from each opponent,
     *   capped at each opponent's actual balance to avoid creating coins.
     * - CHOSEN_PLAYER-sourced (TV Station): intentionally NOT resolved here. The active
     *   player picks the victim through a separate interaction round-trip
     *   ([resolveTvStationTarget]), so the steal cannot be applied during this pass.
     *   See issue #433 — previously CHOSEN_PLAYER fell into [bankEarned], handing the
     *   active player free coins from the bank instead of stealing from an opponent.
     * - NONE-sourced (Business Center): no coin movement; its effect is the card swap
     *   handled by [swapBusinessCenterCard], so it is excluded from bank income.
     */
    private fun processPurpleCards(
        players: List<PlayerModel>,
        activePlayerId: Int,
        matchedCardsByPlayer: Map<Int, List<Pair<PlayerCardModel, CardModel>>>,
        finalCoins: MutableMap<Int, Int>
    ) {
        val activePlayer = players.find { it.id == activePlayerId } ?: return
        val purpleCards = matchedCardsByPlayer[activePlayer.id].orEmpty()
            .filter { (_, card) -> card.color == CardColor.PURPLE }

        val bankEarned = purpleCards
            .filter { (_, card) -> card.paymentSource == PaymentSource.BANK }
            .sumOf { (playerCard, card) -> playerCard.quantity * card.income }

        if (bankEarned > 0) {
            finalCoins[activePlayerId] = finalCoins.getValue(activePlayerId) + bankEarned
        }

        val coinsPerOpponent = purpleCards
            .filter { (_, card) -> card.paymentSource == PaymentSource.ALL_PLAYERS }
            .sumOf { (playerCard, card) -> playerCard.quantity * card.income }

        if (coinsPerOpponent > 0) {
            players.filter { it.id != activePlayerId }.forEach { opponent ->
                val transfer = minOf(coinsPerOpponent, finalCoins.getValue(opponent.id))
                if (transfer > 0) {
                    finalCoins[opponent.id] = finalCoins.getValue(opponent.id) - transfer
                    finalCoins[activePlayerId] = finalCoins.getValue(activePlayerId) + transfer
                }
            }
        }
    }

    /**
     * Advances the game state by resolving the effects of the dice roll
     * and then moves the active player into the buy/build window.
     *
     * This is the handoff point introduced for the buying-phase flow: earnings
     * are resolved first, and only then does the turn enter BUY_OR_BUILD.
     */
    override fun resolveEffects(gameId: Int): Map<Int, Int> = gameTransactionRunner.inTransaction {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        when (game.turnPhase) {
            TurnPhase.ROLL_DICE -> throw CustomWebSocketException(
                "DICE_ROLL_REQUIRED",
                "Effects cannot be resolved before dice are rolled",
            )
            TurnPhase.AWAIT_TV_TARGET, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN -> throw CustomWebSocketException(
                "EFFECTS_ALREADY_RESOLVED",
                "Effects have already been resolved for this turn",
            )
            TurnPhase.RESOLVE_EFFECTS -> Unit
        }
        val (diceRoll, players, activePlayer) = requireActiveTurn(gameId, game.lastDiceRoll, game.currentTurnIndex)

        // A TV Station steal needs the active player to pick a victim, so when one
        // is pending we park the turn in AWAIT_TV_TARGET instead of advancing to
        // BUY_OR_BUILD. The destination is chosen *before* transitioning so the
        // optimistic phase lock still guards against concurrent double-resolution.
        val nextPhase = if (isTvStationStealPending(players, diceRoll, activePlayer.id)) {
            TurnPhase.AWAIT_TV_TARGET
        } else {
            TurnPhase.BUY_OR_BUILD
        }

        if (!gameDao.tryTransitionPhase(gameId, TurnPhase.RESOLVE_EFFECTS, nextPhase)) {
            throw CustomWebSocketException(
                "EFFECTS_ALREADY_RESOLVED",
                "Effects have already been resolved for this turn",
            )
        }

        processEarnings(gameId, diceRoll, activePlayer.id)
    }

    /**
     * Applies a TV Station steal once the active player has chosen a victim.
     *
     * Resolves the second half of the round-trip started by [resolveEffects]: the
     * active player takes `5 × (TV Station count)` coins from [targetPlayerId],
     * capped at that opponent's balance so no coins are created, then the turn
     * advances to BUY_OR_BUILD. See issue #433.
     */
    override fun resolveTvStationTarget(gameId: Int, targetPlayerId: Int): Map<Int, Int> =
        gameTransactionRunner.inTransaction {
            val game = gameStateGuard.ensureGameIsRunning(gameId)
            if (game.turnPhase != TurnPhase.AWAIT_TV_TARGET) {
                throw CustomWebSocketException(
                    "NO_PENDING_TV_STATION",
                    "There is no TV Station target to choose for this turn",
                )
            }
            val (diceRoll, players, activePlayer) = requireActiveTurn(gameId, game.lastDiceRoll, game.currentTurnIndex)

            val target = players.firstOrNull { it.id == targetPlayerId && it.id != activePlayer.id }
                ?: throw CustomWebSocketException(
                    "INVALID_TV_STATION_TARGET",
                    "Player $targetPlayerId is not a valid TV Station target in game $gameId",
                )

            val transfer = minOf(tvStationStealAmount(diceRoll, activePlayer.id), target.coins)

            // Transition first so the optimistic phase lock rejects a concurrent
            // duplicate choice before any coins move.
            if (!gameDao.tryTransitionPhase(gameId, TurnPhase.AWAIT_TV_TARGET, TurnPhase.BUY_OR_BUILD)) {
                throw CustomWebSocketException(
                    "EFFECTS_ALREADY_RESOLVED",
                    "Effects have already been resolved for this turn",
                )
            }

            if (transfer <= 0) {
                return@inTransaction emptyMap()
            }

            playerDao.updateCoins(activePlayer.id, activePlayer.coins + transfer)
            playerDao.updateCoins(target.id, target.coins - transfer)

            // Signed per-player coin deltas drive the client coin sounds (#389).
            mapOf(activePlayer.id to transfer, target.id to -transfer)
        }

    private data class ActiveTurn(val diceRoll: Int, val players: List<PlayerModel>, val activePlayer: PlayerModel)

    /**
     * Loads the stored dice roll, the game's players, and the active player, rejecting
     * a turn with no recorded roll or no resolvable active player. Shared by the two
     * effect-resolution entry points.
     */
    private fun requireActiveTurn(gameId: Int, lastDiceRoll: Int?, currentTurnIndex: Int): ActiveTurn {
        val diceRoll = lastDiceRoll ?: throw CustomWebSocketException(
            "DICE_ROLL_REQUIRED",
            "Effects require a stored dice roll",
        )
        val players = playerDao.getPlayers(gameId)
        val activePlayer = players.getOrNull(currentTurnIndex)
            ?: throw CustomWebSocketException("NO_ACTIVE_PLAYER", "Game $gameId has no active player")
        return ActiveTurn(diceRoll, players, activePlayer)
    }

    /**
     * Coins a TV Station steal would move: `income × quantity` summed over the
     * active player's activated CHOSEN_PLAYER cards. Business Center is also
     * CHOSEN_PLAYER but seeds income 0, so it contributes nothing here.
     */
    private fun tvStationStealAmount(diceRoll: Int, activePlayerId: Int): Int {
        val activatingCards = cardDao.findByActivationNumber(diceRoll).associateBy { it.cardType }
        return playerCardDao.findByPlayerId(activePlayerId)
            .mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }
            .filter { (_, card) -> card.paymentSource == PaymentSource.CHOSEN_PLAYER }
            .sumOf { (playerCard, card) -> playerCard.quantity * card.income }
    }

    /**
     * True when the active player activated a TV Station with coins to steal and
     * at least one opponent has a non-zero balance. When no opponent has coins the
     * steal would be a no-op, so the interaction round-trip is skipped entirely.
     */
    private fun isTvStationStealPending(players: List<PlayerModel>, diceRoll: Int, activePlayerId: Int): Boolean {
        if (tvStationStealAmount(diceRoll, activePlayerId) <= 0) return false
        return players.any { it.id != activePlayerId && it.coins > 0 }
    }

    override fun swapBusinessCenterCard(
        gameId: Int,
        activePlayerId: Int,
        targetPlayerId: Int,
        offeredCardType: CardType,
        requestedCardType: CardType,
    ): Unit = gameTransactionRunner.inTransaction {
        val game = gameStateGuard.ensureGameIsRunning(gameId)
        if (game.turnPhase != TurnPhase.BUY_OR_BUILD || game.lastDiceRoll != 6) {
            throw CustomWebSocketException(
                "BUSINESS_CENTER_NOT_ACTIVE",
                "Business Center can only be used after resolving a roll of 6",
            )
        }
        if (game.businessCenterUsedThisTurn) {
            throw CustomWebSocketException(
                "BUSINESS_CENTER_ALREADY_USED",
                "Business Center has already been used this turn",
            )
        }

        val players = playerDao.getPlayers(gameId)
        val activePlayer = players.getOrNull(game.currentTurnIndex)
            ?: throw CustomWebSocketException("NO_ACTIVE_PLAYER", "Game $gameId has no active player")
        if (activePlayer.id != activePlayerId) {
            throw CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn")
        }
        if (players.none { it.id == targetPlayerId } || targetPlayerId == activePlayerId) {
            throw CustomWebSocketException(
                "INVALID_SWAP_TARGET",
                "Business Center requires a different target player in the same game",
            )
        }
        if (offeredCardType == requestedCardType) {
            throw CustomWebSocketException(
                "INVALID_BUSINESS_CENTER_CARD",
                "Business Center must exchange two different card types",
            )
        }

        val activeCards = playerCardDao.findByPlayerId(activePlayerId)
        val targetCards = playerCardDao.findByPlayerId(targetPlayerId)
        val businessCenterQuantity = activeCards.firstOrNull { it.cardType == CardType.BUSINESS_CENTER }?.quantity ?: 0
        if (businessCenterQuantity <= 0) {
            throw CustomWebSocketException(
                "BUSINESS_CENTER_NOT_OWNED",
                "Active player does not own Business Center",
            )
        }

        val offeredCard = cardDao.findByCardType(offeredCardType)
            ?: throw CustomWebSocketException("CARD_NOT_FOUND", "Offered card $offeredCardType not found")
        val requestedCard = cardDao.findByCardType(requestedCardType)
            ?: throw CustomWebSocketException("CARD_NOT_FOUND", "Requested card $requestedCardType not found")
        if (offeredCard.establishmentType == EstablishmentType.MAJOR || requestedCard.establishmentType == EstablishmentType.MAJOR) {
            throw CustomWebSocketException(
                "INVALID_BUSINESS_CENTER_CARD",
                "Business Center cannot exchange major establishments",
            )
        }

        val offeredQuantity = activeCards.firstOrNull { it.cardType == offeredCardType }?.quantity ?: 0
        val requestedQuantity = targetCards.firstOrNull { it.cardType == requestedCardType }?.quantity ?: 0
        if (offeredQuantity <= 0 || requestedQuantity <= 0) {
            throw CustomWebSocketException(
                "CARD_NOT_OWNED",
                "Both players must own the cards being exchanged",
            )
        }
        if (!gameDao.tryMarkBusinessCenterUsedThisTurn(gameId)) {
            throw CustomWebSocketException(
                "BUSINESS_CENTER_ALREADY_USED",
                "Business Center has already been used this turn",
            )
        }

        playerCardDao.upsert(activePlayerId, offeredCardType, offeredQuantity - 1)
        playerCardDao.upsert(targetPlayerId, requestedCardType, requestedQuantity - 1)
        playerCardDao.upsert(
            activePlayerId,
            requestedCardType,
            activeCards.firstOrNull { it.cardType == requestedCardType }?.quantity?.plus(1) ?: 1,
        )
        playerCardDao.upsert(
            targetPlayerId,
            offeredCardType,
            targetCards.firstOrNull { it.cardType == offeredCardType }?.quantity?.plus(1) ?: 1,
        )
    }
}
