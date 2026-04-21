package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.enums.CardColor
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
     * Processes income for all players based on the current dice roll
     * Evaluates the color-coded rules to determine who gets paid
     */
    override fun processEarnings(gameId: Int, diceRoll: Int, activePlayerId: Int) {
        transaction {
            // Find all establishment cards in the game that activate on this specific dice roll
            val allCards = cardDao.findAll()
                .filter { it.diceMin <= diceRoll && it.diceMax >= diceRoll }
                .associateBy { it.cardType }

            val players = playerDao.findByGameId(gameId)

            // Iterate through every player in the game to see if their cards trigger
            players.forEach { player ->
                val isActive = player.id == activePlayerId

                val earned = playerCardDao.findByPlayerId(player.id)
                    // Match the player's owned cards with the ones that trigger on this roll
                    .mapNotNull { playerCard -> allCards[playerCard.cardType]?.let { playerCard to it } }
                    // Apply activation rules based on card color
                    .filter { (playerCard, _) ->
                        when (playerCard.cardType.color) {
                            // Primary Industry (Blue): Activates on ANY player's turn
                            CardColor.BLUE -> true
                            // Secondary Industry (Green): Activates ONLY on your turn
                            CardColor.GREEN -> isActive
                            // Major Establishment (Purple): Activates ONLY on your turn
                            CardColor.PURPLE -> isActive
                            // Restaurants (Red): Activates ONLY on opponents' turns
                            // Note: Red card logic usually requires stealing from the active player
                            // Currently, this just grants coins from the bank
                            // Stealing logic may need to be expanded here
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
     * Ensures the game is in the correct phase before triggering the economics
     */
    override fun resolveEffects(gameId: Int) {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        // Ensure exact phase where earnings are calculated
        check(game.turnPhase == TurnPhase.RESOLVE_EFFECTS) { "Game is not in RESOLVE_EFFECTS phase" }
        val diceRoll = checkNotNull(game.lastDiceRoll) { "Dice roll not set" }

        val players = playerDao.findByGameId(gameId)
        val activePlayer = players[game.currentTurnIndex]

        // Distribute coins
        processEarnings(gameId, diceRoll, activePlayer.id)

        // Advance game state to allow active player to construct a new building
        gameDao.updateTurnPhase(gameId, TurnPhase.BUY_OR_BUILD)
    }
}