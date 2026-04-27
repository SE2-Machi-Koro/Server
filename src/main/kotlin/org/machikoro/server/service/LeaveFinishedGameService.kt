package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
@Service
class LeaveFinishedGameService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameStateGuard: GameStateGuard,
){
    @Transactional
    fun leaveFinishedGame(gameId: Int, playerId: Int) {
        gameStateGuard.ensureGameIsFinished(gameId)
        val players = playerDao.getPlayers(gameId)
        require(!(players.none { it.id == playerId })) { "Player not in game" }
        playerDao.delete(playerId)
        if (playerDao.countByGameId(gameId) == 0) {
            gameDao.delete(gameId)
        }
    }
}

