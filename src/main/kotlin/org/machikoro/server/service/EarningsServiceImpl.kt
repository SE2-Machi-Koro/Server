package org.machikoro.server.service

import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.CardModel
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameNotFoundException
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
    private val gamePhaseService: GamePhaseService
) : EarningsService {

    fun computeEarnings(pairs: List<Pair<Int, Int>>): Int =
        pairs.sumOf { (quantity, income) -> quantity * income }

    @Transactional
    override fun processEarnings(gameId: Int, diceRoll: Int, activePlayerId: Int) {
        val activatingCards = cardDao.findByActivationNumber(diceRoll)
            .associateBy { it.cardType }

        val players = playerDao.getPlayers(gameId)
        val finalCoins = players.associate { it.id to it.coins }.toMutableMap()

        val matchedCardsByPlayer = players.associate { player ->
            player.id to playerCardDao.findByPlayerId(player.id)
                .mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }
        }

        processRedCards(players, activePlayerId, matchedCardsByPlayer, finalCoins)
        processBlueCards(players, matchedCardsByPlayer, finalCoins)
        processGreenCards(players, activePlayerId, matchedCardsByPlayer, finalCoins)
        processPurpleCards(players, activePlayerId, matchedCardsByPlayer, finalCoins)

        players.forEach { player ->
            if (finalCoins[player.id] != player.coins) {
                playerDao.updateCoins(player.id, finalCoins.getValue(player.id))
            }
        }
    }

    /**
     * RED cards (OTHER_TURN): each opponent with a matching red card steals from the active player.
     * Each theft is capped at the active player's remaining balance — no coins are created.
     */
    private fun processRedCards(
        players: List<PlayerModel>,
        activePlayerId: Int,
        matchedCardsByPlayer: Map<Int, List<Pair<PlayerCardModel, CardModel>>>,
        finalCoins: MutableMap<Int, Int>
    ) {
        players.filter { it.id != activePlayerId }.forEach { opponent ->
            val redEarned = matchedCardsByPlayer[opponent.id].orEmpty()
                .filter { (_, card) -> card.color == CardColor.RED }
                .sumOf { (playerCard, card) -> playerCard.quantity * card.income }

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
        finalCoins: MutableMap<Int, Int>
    ) {
        val activePlayer = players.find { it.id == activePlayerId } ?: return
        val earned = matchedCardsByPlayer[activePlayer.id].orEmpty()
            .filter { (_, card) -> card.color == CardColor.GREEN }
            .sumOf { (playerCard, card) -> playerCard.quantity * card.income }

        if (earned > 0) {
            finalCoins[activePlayerId] = finalCoins.getValue(activePlayerId) + earned
        }
    }

    /**
     * PURPLE cards (OWN_TURN): only the active player benefits.
     * - BANK-sourced: active player receives income from the bank.
     * - ALL_PLAYERS-sourced (e.g. Stadium): active player steals from each opponent,
     *   capped at each opponent's actual balance to avoid creating coins.
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
            .filter { (_, card) -> card.paymentSource != PaymentSource.ALL_PLAYERS }
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
    @Transactional
    override fun resolveEffects(gameId: Int) {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        // Ensure exact phase where earnings are calculated
        check(game.turnPhase == TurnPhase.RESOLVE_EFFECTS) { "Game is not in RESOLVE_EFFECTS phase" }
        val diceRoll = checkNotNull(game.lastDiceRoll) { "Dice roll not set" }

        val players = playerDao.getPlayers(gameId)
        val activePlayer = players[game.currentTurnIndex]

        // Distribute coins
        processEarnings(gameId, diceRoll, activePlayer.id)

        // Route through phase service so all transition logic is applied consistently
        gamePhaseService.advancePhase(gameId)
    }
}