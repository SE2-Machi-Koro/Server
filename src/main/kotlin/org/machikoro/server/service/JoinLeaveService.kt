package org.machikoro.server.service

import io.swagger.v3.oas.models.security.SecurityScheme
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao

class JoinLeaveService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameStateGuard: GameStateGuard,
){
    fun joinGame() {
        // Placeholder
    }
    fun leaveGame(gameId: Int, playerId: Int) {
        val game = gameStateGuard.ensureGameIsFinished(gameId)
        deletePlayerFromCurrentGame(playerId)
        deleteGameIfNoPlayersLeft(game.id)
    }
    private fun deletePlayerFromCurrentGame(playerId: Int) {
        val player = playerDao.findById(playerId)
        check(player != null) { "Player $playerId has no active game entries" }
        playerDao.delete(playerId)
    }

   private fun deleteGameIfNoPlayersLeft(gameId: Int) {
        if(playerDao.findByGameId(gameId).isEmpty()) {
            gameDao.delete(gameId)
        }
    }
}

