package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.stereotype.Service
import org.machikoro.server.domain.enums.CardColor

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
     * Processes income for all players based on the current dice roll
     * Evaluates the card colors to determine who gets paid:
     * - BLUE (ANY_TURN): all players receive income
     * - GREEN/PURPLE (OWN_TURN): only the active player receives income
     * - RED (OTHER_TURN): only opponents receive income
     */
    override fun processEarnings(gameId: Int, diceRoll: Int, activePlayerId: Int) {
        transaction {
            // Find all establishment cards that activate on this specific dice roll
            val activatingCards = cardDao.findByActivationNumber(diceRoll)
                .associateBy { it.cardType }

            val players = playerDao.getPlayers(gameId)

            // Iterate through every player in the game to see if their cards trigger
            players.forEach { player ->
                val isActive = player.id == activePlayerId

                val earned = playerCardDao.findByPlayerId(player.id)
                    // Match the player's owned cards with the ones that trigger on this roll
                    .mapNotNull { playerCard -> activatingCards[playerCard.cardType]?.let { playerCard to it } }
                    // Apply activation rules based on card color
                    .filter { (_, card) ->
                        when (card.color) {
                            // Blue cards activate on anyone's turn
                            CardColor.BLUE -> true
                            // Green/Purple cards activate ONLY on your turn
                            CardColor.GREEN, CardColor.PURPLE -> isActive
                            // Red cards activate ONLY on opponents' turns
                            CardColor.RED -> !isActive
                        }
                    }
                    // Extract the quantities and income values to compute the total
                    .map { (playerCard, card) -> playerCard.quantity to card.income }
                    .let { computeEarnings(it) }

                // If the player earned coins, update their wallet in the database
                if (earned > 0) {
                    playerDao.updateCoins(player.id, player.coins + earned)
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

        // Advance game state to allow active player to construct a new building
        gameDao.updateTurnPhase(gameId, TurnPhase.BUY_OR_BUILD)
    }
}
