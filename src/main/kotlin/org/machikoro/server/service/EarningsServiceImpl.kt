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

    private data class EarningsCalculation(
        val finalCoins: Map<Int, Int>,
        val tvStationStealAmount: Int,
    )

    private fun calculateEarnings(
        players: List<PlayerModel>,
        diceRoll: Int,
        activePlayerId: Int,
    ): EarningsCalculation {
        val activatingCards = cardDao.findByActivationNumber(diceRoll)
            .associateBy { it.cardType }

        val finalCoins = players.associate { it.id to it.coins }.toMutableMap()

        val inventoryByPlayer = players.associate { player ->
            player.id to playerCardDao.findByPlayerId(player.id)
        }

        val matchedCardsByPlayer = inventoryByPlayer.mapValues { (_, cards) ->
            cards.mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }
        }

        val hasShoppingMallByPlayerId = players.associate { player ->
            player.id to (playerLandmarkDao.findByPlayerIdAndType(player.id, LandmarkType.SHOPPING_MALL)?.isBuilt == true)
        }

        // Cheese/Furniture Factory and Fruit & Vegetable Market pay per matching
        // establishment the active player owns (issue #432), so we need a count of
        // every establishment type in the active player's full inventory.
        val activeEstablishmentCounts = establishmentCounts(inventoryByPlayer[activePlayerId].orEmpty())

        processRedCards(players, activePlayerId, matchedCardsByPlayer, finalCoins, hasShoppingMallByPlayerId)
        processBlueCards(players, matchedCardsByPlayer, finalCoins)
        processGreenCards(
            players,
            activePlayerId,
            matchedCardsByPlayer,
            finalCoins,
            hasShoppingMallByPlayerId,
            activeEstablishmentCounts,
        )
        processPurpleCards(players, activePlayerId, matchedCardsByPlayer, finalCoins)

        val tvStationStealAmount = chosenPlayerIncome(matchedCardsByPlayer[activePlayerId].orEmpty())

        return EarningsCalculation(finalCoins, tvStationStealAmount)
    }

    private fun applyCoinChanges(
        players: List<PlayerModel>,
        finalCoins: Map<Int, Int>,
    ): Map<Int, Int> {
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
     *
     * Most green cards pay a flat `quantity × income`. Cheese Factory, Furniture
     * Factory and Fruit & Vegetable Market instead pay `income` for each matching
     * establishment the active player owns (see [factoryMultiplier]).
     */
    private fun processGreenCards(
        players: List<PlayerModel>,
        activePlayerId: Int,
        matchedCardsByPlayer: Map<Int, List<Pair<PlayerCardModel, CardModel>>>,
        finalCoins: MutableMap<Int, Int>,
        hasShoppingMallByPlayerId: Map<Int, Boolean>,
        activeEstablishmentCounts: Map<EstablishmentType, Int>,
    ) {
        val activePlayer = players.find { it.id == activePlayerId } ?: return
        val earned = matchedCardsByPlayer[activePlayer.id].orEmpty()
            .filter { (_, card) -> card.color == CardColor.GREEN }
            .sumOf { (playerCard, card) ->
                val extra = if (card.establishmentType == EstablishmentType.BREAD && hasShoppingMallByPlayerId[activePlayer.id] == true)
                    1 else 0
                val multiplier = factoryMultiplier(card, activeEstablishmentCounts)
                playerCard.quantity * (card.income + extra) * multiplier
            }

        if (earned > 0) {
            finalCoins[activePlayerId] = finalCoins.getValue(activePlayerId) + earned
        }
    }

    /**
     * Per Machi Koro rules, factory/market green cards multiply their income by
     * the number of a specific other establishment the active player owns:
     * - Cheese Factory → owned COW cards (Ranch)
     * - Furniture Factory → owned GEAR cards (Forest, Mine)
     * - Fruit & Vegetable Market → owned WHEAT cards (Wheat Field, Apple Orchard)
     *
     * Every other green card pays its flat income, i.e. a multiplier of 1.
     */
    private fun factoryMultiplier(
        card: CardModel,
        establishmentCounts: Map<EstablishmentType, Int>,
    ): Int = when (card.cardType) {
        CardType.CHEESE_FACTORY -> establishmentCounts[EstablishmentType.COW] ?: 0
        CardType.FURNITURE_FACTORY -> establishmentCounts[EstablishmentType.GEAR] ?: 0
        CardType.FRUIT_AND_VEGETABLE_MARKET -> establishmentCounts[EstablishmentType.WHEAT] ?: 0
        else -> 1
    }

    /**
     * Counts how many establishments of each [EstablishmentType] a player owns,
     * resolving each owned card's symbol from the card definitions. Used to drive
     * the factory/market income multipliers (issue #432).
     */
    private fun establishmentCounts(inventory: List<PlayerCardModel>): Map<EstablishmentType, Int> {
        if (inventory.isEmpty()) return emptyMap()
        val establishmentTypeByCard = cardDao.findAll().associate { it.cardType to it.establishmentType }
        return inventory
            .groupBy { establishmentTypeByCard[it.cardType] }
            .mapNotNull { (type, cards) -> type?.let { it to cards.sumOf { card -> card.quantity } } }
            .toMap()
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
            TurnPhase.AWAIT_TV_TARGET, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN -> throw effectsAlreadyResolved()
            TurnPhase.RESOLVE_EFFECTS -> Unit
        }
        val (diceRoll, players, activePlayer) = requireActiveTurn(gameId, game.lastDiceRoll, game.currentTurnIndex)

        val earnings = calculateEarnings(players, diceRoll, activePlayer.id)

        // A TV Station steal needs the active player to pick a victim, so when one
        // is pending after the automatic earnings pass we park the turn in
        // AWAIT_TV_TARGET instead of advancing to BUY_OR_BUILD. The destination is
        // chosen *before* transitioning so the optimistic phase lock still guards
        // against concurrent double-resolution.
        val nextPhase = if (isTvStationStealPending(
                players,
                earnings.finalCoins,
                earnings.tvStationStealAmount,
                activePlayer.id,
            )
        ) {
            TurnPhase.AWAIT_TV_TARGET
        } else {
            TurnPhase.BUY_OR_BUILD
        }

        if (!gameDao.tryTransitionPhase(gameId, TurnPhase.RESOLVE_EFFECTS, nextPhase)) {
            throw effectsAlreadyResolved()
        }

        applyCoinChanges(players, earnings.finalCoins)
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
            if (target.coins <= 0) {
                throw CustomWebSocketException(
                    "INVALID_TV_STATION_TARGET",
                    "Player $targetPlayerId has no coins to steal in game $gameId",
                )
            }

            val transfer = minOf(tvStationStealAmount(diceRoll, activePlayer.id), target.coins)

            // Transition first so the optimistic phase lock rejects a concurrent
            // duplicate choice before any coins move.
            if (!gameDao.tryTransitionPhase(gameId, TurnPhase.AWAIT_TV_TARGET, TurnPhase.BUY_OR_BUILD)) {
                throw effectsAlreadyResolved()
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
     * Rejection used wherever a turn's effects are already resolved: the eager phase
     * guard in [resolveEffects] and the optimistic phase-transition CAS in both
     * [resolveEffects] and [resolveTvStationTarget] (a lost race against a concurrent
     * duplicate). Centralised so the code and message live in one place.
     */
    private fun effectsAlreadyResolved() = CustomWebSocketException(
        "EFFECTS_ALREADY_RESOLVED",
        "Effects have already been resolved for this turn",
    )

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
        val matchedCards = playerCardDao.findByPlayerId(activePlayerId)
            .mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }

        return chosenPlayerIncome(matchedCards)
    }

    private fun chosenPlayerIncome(matchedCards: List<Pair<PlayerCardModel, CardModel>>): Int =
        matchedCards
            .filter { (_, card) -> card.paymentSource == PaymentSource.CHOSEN_PLAYER }
            .sumOf { (playerCard, card) -> playerCard.quantity * card.income }

    /**
     * True when the active player activated a TV Station with coins to steal and
     * at least one opponent has a non-zero balance. When no opponent has coins the
     * steal would be a no-op, so the interaction round-trip is skipped entirely.
     */
    private fun isTvStationStealPending(
        players: List<PlayerModel>,
        finalCoins: Map<Int, Int>,
        tvStationStealAmount: Int,
        activePlayerId: Int,
    ): Boolean {
        if (tvStationStealAmount <= 0) return false
        return players.any { it.id != activePlayerId && finalCoins.getValue(it.id) > 0 }
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
