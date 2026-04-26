package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.springframework.transaction.annotation.Transactional

class LeaveFinishedGameService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameStateGuard: GameStateGuard,
){
    fun leaveGame(gameId: Int, playerId: Int) {
        gameStateGuard.ensureGameIsFinished(gameId)
        val players = playerDao.findByGameId(gameId)
        val player = players.find { it.id == playerId }
            ?: throw IllegalArgumentException("Player not in game")
        playerDao.delete(playerId)
        if (playerDao.countByGameId(gameId) == 0) {
            gameDao.delete(gameId)
        }
    }
}

