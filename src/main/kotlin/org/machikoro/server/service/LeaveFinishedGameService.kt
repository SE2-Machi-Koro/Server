package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao

class LeaveFinishedGameService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameStateGuard: GameStateGuard,
){
    fun leaveGame(gameId: Int, playerId: Int) {
        gameStateGuard.ensureGameIsFinished(gameId)

        val players = playerDao.findByGameId(gameId)

        val isLastPlayer = players.size == 1 && players.first().id == playerId

        playerDao.delete(playerId)

        if (isLastPlayer) {
            gameDao.delete(gameId)
        }
    }
}

