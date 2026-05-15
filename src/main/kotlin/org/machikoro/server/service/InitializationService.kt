package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao

/**
 * Service responsible for creating the initial game state
 * Handles the player order, distribution of initial resources.
 *
 * Machi Koro start resources per player: 3 coins, 2 establishments, 4 deactivated landmarks
 */

class InitializationService (
    private val playerDao: PlayerDao,
    private val gameDao: GameDao,
) {

    fun initialize(gameId: Int) {
        generateRandomOrder(gameId)
    }

    //lock input
    private fun lockInput(){

    }
    //determine player order
    private fun generateRandomOrder(gameId: Int){
        val game = gameDao.findById(gameId)

        //get list of players and shuffle
        val players = playerDao.getPlayers(gameId).shuffled()
        //assign shuffled position as turn order position
        players.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index)
        }

    }
    //initialize resources
    private fun generateResources(){

    }
    //trigger first players turn
    private fun startFirstTurn(){
        //unlock input
    }

}
