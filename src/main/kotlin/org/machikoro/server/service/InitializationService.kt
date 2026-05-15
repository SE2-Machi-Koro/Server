package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Service

/**
 * Service responsible for creating the initial game state
 * Handles the player order, distribution of initial resources.
 *
 * Machi Koro start resources per player: 3 coins, 2 establishments, 4 deactivated landmarks
 */

@Service
class InitializationService (
    private val playerDao: PlayerDao,
    private val gameDao: GameDao,
    private val playerCardDao: PlayerCardDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
) {

    companion object {
        /**
         * Temporary offset applied to all player turn orders in the first pass of
         * the shuffle so that intermediate values don't collide with the unique
         * (gameId, turnOrder) constraint while the final values are being assigned.
         */
        private const val TEMP_TURN_ORDER_OFFSET = 10_000

        // Initial resources per player
        private const val STARTING_COINS = 3
        private val STARTING_CARDS = listOf(CardType.WHEAT_FIELD, CardType.BAKERY)
        private const val MARKETPLACE_SUPPLY_PER_CARD = 6
    }

    /**
     * Initializes all game resources for a newly started game.
     * Runs all initialization steps in sequence:
     * 1. Randomizes player turn order
     * 2. Initializes player landmarks (all unbuilt)
     * 3. Initializes player starting cards
     * 4. Initializes game marketplace
     *
     * @param gameId The ID of the game to initialize
     * @return List of initialized players in shuffled order
     */
    fun initializeGame(gameId: Int): List<PlayerModel> {
        val players = playerDao.getPlayers(gameId)
        val shuffled = generateRandomOrder(players)

        //generate starting Resources
        initializePlayerCoins(shuffled)
        initializePlayerLandmarks(shuffled)
        initializePlayerCards(shuffled)
        initializeMarketplace(gameId)

        return shuffled
    }

    /**
     * Randomizes the player turn order using a two-pass update to avoid
     * unique constraint collisions on (gameId, turnOrder).
     *
     * @param players The list of players to shuffle
     * @return The shuffled players list
     */
    private fun generateRandomOrder(players: List<PlayerModel>): List<PlayerModel>{
        //shuffle list of players
        val shuffled = players.shuffled()
        // Two-pass update to avoid unique (gameId, turnOrder) constraint violations
        // First pass: assign temporary turn orders
        shuffled.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index + TEMP_TURN_ORDER_OFFSET)
        }
        //assign shuffled position as turn order position
        shuffled.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index)
        }
        return shuffled
    }
    //initialize resources

    /**
     * Initializes landmarks for each player.
     * Creates an entry for each LandmarkType marked as unbuilt.
     *
     * @param players The list of players to initialize landmarks for
     */
    private fun initializePlayerLandmarks(players: List<PlayerModel>) {
        players.forEach { player ->
            playerLandmarkDao.initForPlayer(player.id)
        }
    }
    /**
     * Initializes starting cards for each player.
     * By default, players start with WHEAT_FIELD and BAKERY (1 of each).
     *
     * @param players The list of players to initialize cards for
     */
    private fun initializePlayerCards(players: List<PlayerModel>) {
        players.forEach { player ->
            STARTING_CARDS.forEach { cardType ->
                playerCardDao.upsert(player.id, cardType, quantity = 1)
            }
        }
    }
    /**
     * Initializes starting coins for each player.
     * Ensures each player starts with STARTING_COINS.
     *
     * @param players The list of players to initialize coins for
     */
    private fun initializePlayerCoins(players: List<PlayerModel>) {
        players.forEach { player ->
            playerDao.updateCoins(player.id, STARTING_COINS)
        }
    }
    /**
     * Initializes the game marketplace with the full supply of cards.
     * Each card type is seeded with MARKETPLACE_SUPPLY_PER_CARD copies.
     *
     * @param gameId The ID of the game
     */
    private fun initializeMarketplace(gameId: Int) {
        gameMarketplaceDao.initForGame(gameId, MARKETPLACE_SUPPLY_PER_CARD)
    }

}
