package org.machikoro.server.service

import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.models.PlayerModel
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service responsible for creating the initial game state.
 * Handles player order randomization and distribution of starting resources.
 *
 * Machi Koro start resources per player: 3 coins, 2 establishments, 4 disabled landmarks.
 */
@Service
class InitializationService(
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
) {

    companion object {
        // Temporary offset used in two-pass shuffle to avoid unique (gameId, turnOrder) collisions
        private const val TEMP_TURN_ORDER_OFFSET = 10_000

        // Bumped to 50 for testing on this branch
        private const val STARTING_COINS = 50
        private val STARTING_CARDS = listOf(CardType.WHEAT_FIELD, CardType.BAKERY)
        private const val MARKETPLACE_SUPPLY_PER_CARD = 6
    }

    /**
     * Initializes all game resources for a newly started game.
     * Runs all initialization steps in sequence:
     * 1. Randomizes player turn order
     * 2. Initializes player coins
     * 3. Initializes player landmarks (all unbuilt)
     * 4. Initializes player starting cards
     * 5. Initializes game marketplace
     *
     * @param gameId The ID of the game to initialize
     * @return List of initialized players in shuffled turn order
     */
    @Transactional
    fun initializeGame(gameId: Int): List<PlayerModel> {
        val players = playerDao.getPlayers(gameId)
        val shuffled = randomizeOrder(players)

        initializePlayerCoins(shuffled)
        initializePlayerLandmarks(shuffled)
        initializePlayerCards(shuffled)
        initializeMarketplace(gameId)

        return shuffled
    }

    /**
     * Randomizes turn order using a two-pass update to avoid unique
     * constraint collisions on (gameId, turnOrder).
     */
    private fun randomizeOrder(players: List<PlayerModel>): List<PlayerModel> {
        val shuffled = players.shuffled()

        shuffled.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index + TEMP_TURN_ORDER_OFFSET)
        }
        shuffled.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index)
        }

        return shuffled
    }

    /** Sets each player's starting coin balance. */
    private fun initializePlayerCoins(players: List<PlayerModel>) {
        players.forEach { player ->
            playerDao.updateCoins(player.id, STARTING_COINS)
        }
    }

    /** Creates an unbuilt landmark entry for each landmark type for every player. */
    private fun initializePlayerLandmarks(players: List<PlayerModel>) {
        players.forEach { player ->
            playerLandmarkDao.initForPlayer(player.id)
        }
    }

    /** Gives each player one WHEAT_FIELD and one BAKERY to start. */
    private fun initializePlayerCards(players: List<PlayerModel>) {
        players.forEach { player ->
            STARTING_CARDS.forEach { cardType ->
                playerCardDao.upsert(player.id, cardType, quantity = 1)
            }
        }
    }

    /** Seeds the game marketplace with the full supply for each card type. */
    private fun initializeMarketplace(gameId: Int) {
        gameMarketplaceDao.initForGame(gameId, MARKETPLACE_SUPPLY_PER_CARD)
    }
}
