package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.springframework.stereotype.Service

@Service
class LobbyService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
) {

    private val lobbyLocks = mutableMapOf<Int, Any>()

    fun addUserToLobby(gameId: Int, userId: Int): PlayerModel {
        val lock = synchronized(lobbyLocks) {
            lobbyLocks.computeIfAbsent(gameId) { Any() }
        }

        synchronized(lock) {
            val game = gameDao.findById(gameId) ?: throw GameNotFoundException("Game with id $gameId not found")
            if (game.status == GameStatus.IN_PROGRESS) {
                throw GameStartedException("Game with id $gameId has already started")
            }
            val players = playerDao.getPlayers(gameId)
            if (players.size >= 4) {
                throw LobbyFullException("Lobby for game with id $gameId is full")
            }
            return playerDao.addPlayer(gameId, userId)
        }
    }

    fun startGame(gameId: Int): GameModel {
        val game = gameDao.findById(gameId) ?: throw GameNotFoundException("Game with id $gameId not found")
        val players = playerDao.getPlayers(gameId)

        gameDao.updateStatus(gameId, GameStatus.IN_PROGRESS)

        // Buying-phase setup lives on game start so purchases can read existing rows.
        gameMarketplaceDao.initForGame(gameId)
        players.forEach { player ->
            // Landmark state is created once per player and then updated over the game.
            playerLandmarkDao.initForPlayer(player.id)
        }

        return game.copy(status = GameStatus.IN_PROGRESS)
    }
}
