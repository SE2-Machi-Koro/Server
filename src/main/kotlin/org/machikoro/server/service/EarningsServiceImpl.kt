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

    private data class EarningsCalculation(val finalCoins: Map<Int, Int>)

    private fun calculateEarnings(
        players: List<PlayerModel>,
        diceRoll: Int,
        activePlayerId: Int,
    ): EarningsCalculation {
        val activatingCards = cardDao.findByActivationNumber(diceRoll)
            .associateBy { it.cardType }

        val finalCoins = players.associate { it.id to it.coins }.toMutableMap()

        // Single query for all player cards instead of N per-player queries
        val rawInventory = playerCardDao.findByPlayerIds(players.map { it.id })
        val inventoryByPlayer = players.associate { player ->
            player.id to (rawInventory[player.id] ?: emptyList())
        }

        val matchedCardsByPlayer = inventoryByPlayer.mapValues { (_, cards) ->
            cards.mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }
        }

        // Single query for all player landmarks instead of N per-player queries
        val allLandmarks = playerLandmarkDao.findByPlayerIds(players.map { it.id })
        val hasShoppingMallByPlayerId = players.associate { player ->
            player.id to (allLandmarks[player.id]
                ?.any { it.landmarkType == LandmarkType.SHOPPING_MALL && it.isBuilt } == true)
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

        return EarningsCalculation(finalCoins)
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
     * - ALL_PLAYERS-sourced (Stadium): steals from each opponent, capped at balance.
     * - CHOSEN_PLAYER-sourced (TV Station): steals from a randomly selected opponent,
     *   capped at that opponent's current balance.
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

        // TV Station: steal from a random opponent regardless of their balance
        val tvSteal = purpleCards
            .filter { (_, card) -> card.paymentSource == PaymentSource.CHOSEN_PLAYER }
            .sumOf { (playerCard, card) -> playerCard.quantity * card.income }

        if (tvSteal > 0) {
            val opponents = players.filter { it.id != activePlayerId }
            if (opponents.isNotEmpty()) {
                val target = opponents.random()
                val transfer = minOf(tvSteal, finalCoins.getValue(target.id))
                if (transfer > 0) {
                    finalCoins[target.id] = finalCoins.getValue(target.id) - transfer
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
            TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN -> throw effectsAlreadyResolved()
            TurnPhase.RESOLVE_EFFECTS -> Unit
        }
        val (diceRoll, players, activePlayer) = requireActiveTurn(gameId, game.lastDiceRoll, game.currentTurnIndex)

        val earnings = calculateEarnings(players, diceRoll, activePlayer.id)

        if (!gameDao.tryTransitionPhase(gameId, TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD)) {
            throw effectsAlreadyResolved()
        }

        applyCoinChanges(players, earnings.finalCoins)
    }

    private data class ActiveTurn(val diceRoll: Int, val players: List<PlayerModel>, val activePlayer: PlayerModel)

    // Centralised rejection for double-resolution races
    private fun effectsAlreadyResolved() = CustomWebSocketException(
        "EFFECTS_ALREADY_RESOLVED",
        "Effects have already been resolved for this turn",
    )

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
}
