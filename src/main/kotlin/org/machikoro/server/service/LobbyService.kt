package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
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
    private val playerDao: PlayerDao
) {

    private val lobbyLocks = mutableMapOf<Int, Any>()

    /**
     * Creates a new lobby for a given host user.
     *
     * A lobby is represented as a Game with status WAITING.
     * This method:
     * 1. Creates a new game entry in the database (including a unique lobby code)
     * 2. Adds the host user as the first player in the lobby
     * 3. Returns the fully initialized GameModel
     *
     * @param hostUserId the ID of the user creating the lobby (host)
     * @return the created GameModel representing the lobby
     * @throws GameNotFoundException if the created game cannot be retrieved
     */
    fun createLobby(hostUserId: Int): GameModel {

        // Step 1: Create a new game (lobby) in the database
        // This automatically generates a unique lobby code
        val gameId = gameDao.create(hostUserId = hostUserId)

        // Step 2: Add the host as the first player in the lobby
        playerDao.addPlayer(gameId, hostUserId)

        // Step 3: Retrieve and return the created gamegit add .
        return gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game with id $gameId not found after creation")
    }

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
        gameDao.updateStatus(gameId, GameStatus.IN_PROGRESS)
        // Here you would initialize the game state, e.g., by creating the initial deck of cards, etc.
        return game.copy(status = GameStatus.IN_PROGRESS)
    }
}