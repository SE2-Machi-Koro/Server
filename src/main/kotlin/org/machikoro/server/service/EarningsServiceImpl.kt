package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.stereotype.Service

/**
 * Service responsible for core economic of the game
 * Handles the calculation and distribution of coins based on dice rolls and player establishments
 */
@Service
class EarningsServiceImpl(
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val cardDao: CardDao,
    private val gameDao: GameDao
) : EarningsService {

    /**
     * Helper function to calculate total earnings for a specific set of cards
     * Multiplies the number of cards owned (quantity) by the coin yield of that card (income)
     */
    fun computeEarnings(pairs: List<Pair<Int, Int>>): Int =
        pairs.sumOf { (quantity, income) -> quantity * income }

    /**
     * Core earnings logic, intended to run inside an existing transaction.
     *
     * Coin changes are accumulated as deltas first, then applied in a single pass
     * to avoid stale in-memory values causing incorrect totals when multiple cards
     * trigger for the same player in one roll.
     *
     * Card color determines who is eligible to receive income:
     * - BLUE   (ANY_TURN):   all players receive income from the bank
     * - GREEN  (OWN_TURN):   only the active player receives income from the bank
     * - RED    (OTHER_TURN): opponents steal coins from the active player
     * - PURPLE (OWN_TURN):   only the active player; payment source varies by card
     */
    private fun processEarningsInTransaction(gameId: Int, diceRoll: Int, activePlayerId: Int) {
        val activatingCards = cardDao.findByActivationNumber(diceRoll)
            .associateBy { it.cardType }

        val players = playerDao.getPlayers(gameId)
        val activePlayer = players.find { it.id == activePlayerId }
            ?: throw GameNotFoundException("Active player $activePlayerId not found")

        // Accumulate all coin changes before touching the DB to avoid reading
        // stale in-memory coin values mid-loop
        val coinDeltas = players.associate { it.id to 0 }.toMutableMap()

        players.forEach { player ->
            val isActive = player.id == activePlayerId

            val triggeredCards = playerCardDao.findByPlayerId(player.id)
                .mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }
                .filter { (_, card) ->
                    when (card.color) {
                        CardColor.BLUE -> true
                        CardColor.GREEN, CardColor.PURPLE -> isActive
                        CardColor.RED -> !isActive
                    }
                }

            triggeredCards.forEach { (playerCard, card) ->
                val totalEarnings = playerCard.quantity * card.income
                if (totalEarnings <= 0) return@forEach

                when (card.paymentSource) {
                    PaymentSource.BANK -> {
                        // Income from bank to card owner (blue/green/purple cards)
                        coinDeltas[player.id] = coinDeltas.getValue(player.id) + totalEarnings
                    }
                    PaymentSource.ACTIVE_PLAYER -> {
                        // Red cards: steal from active player, give to card owner.
                        // Cap the transfer to what the active player can actually pay after
                        // previous deltas — no coins are created out of thin air.
                        val available = activePlayer.coins + coinDeltas.getValue(activePlayer.id)
                        val actualTransfer = minOf(totalEarnings, maxOf(0, available))
                        coinDeltas[activePlayer.id] = coinDeltas.getValue(activePlayer.id) - actualTransfer
                        coinDeltas[player.id] = coinDeltas.getValue(player.id) + actualTransfer
                    }
                    PaymentSource.ALL_PLAYERS -> {
                        // Each other player contributes an equal share (e.g. Stadium).
                        // Each contributor is capped at what they can actually pay after
                        // previous deltas — the owner receives the sum of actual payments.
                        val perPlayerAmount = totalEarnings / (players.size - 1)
                        var actualTotal = 0
                        players.filter { it.id != player.id }.forEach { contributor ->
                            val available = contributor.coins + coinDeltas.getValue(contributor.id)
                            val actualPayment = minOf(perPlayerAmount, maxOf(0, available))
                            coinDeltas[contributor.id] = coinDeltas.getValue(contributor.id) - actualPayment
                            actualTotal += actualPayment
                        }
                        coinDeltas[player.id] = coinDeltas.getValue(player.id) + actualTotal
                    }
                    PaymentSource.CHOSEN_PLAYER, PaymentSource.NONE -> {
                        // CHOSEN_PLAYER: handled separately by client interaction (TV Station)
                        // NONE: no automatic payment (Business Center)
                    }
                }
            }
        }

        // Apply all deltas in one pass; clamp to 0 so no player goes into debt
        players.forEach { player ->
            val delta = coinDeltas.getValue(player.id)
            if (delta != 0) {
                playerDao.updateCoins(player.id, maxOf(0, player.coins + delta))
            }
        }
    }

    /**
     * Processes income for all players based on the current dice roll.
     * Runs inside a single transaction to guarantee atomicity.
     */
    override fun processEarnings(gameId: Int, diceRoll: Int, activePlayerId: Int) {
        transaction {
            processEarningsInTransaction(gameId, diceRoll, activePlayerId)
        }
    }

    /**
     * Advances the game state by resolving the effects of the dice roll
     * and then moves the active player into the buy/build window.
     *
     * Coin distribution and phase transition run in a single transaction so
     * they either both commit or both roll back — no partial state possible.
     */
    override fun resolveEffects(gameId: Int) {
        transaction {
            val game = gameDao.findById(gameId)
                ?: throw GameNotFoundException("Game $gameId not found")

            check(game.turnPhase == TurnPhase.RESOLVE_EFFECTS) { "Game is not in RESOLVE_EFFECTS phase" }
            val diceRoll = checkNotNull(game.lastDiceRoll) { "Dice roll not set" }

            val players = playerDao.getPlayers(gameId)
            val activePlayer = players[game.currentTurnIndex]

            processEarningsInTransaction(gameId, diceRoll, activePlayer.id)

            gameDao.updateTurnPhase(gameId, TurnPhase.BUY_OR_BUILD)
        }
    }
}