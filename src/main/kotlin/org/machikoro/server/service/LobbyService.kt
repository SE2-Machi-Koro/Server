package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.machikoro.server.exception.NotHostException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LobbyService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val connectionTracker: WebSocketConnectionTracker,
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

        // Step 3: Retrieve and return the created game
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

    /**
     * Transitions a lobby to an active game.
     *
     * Steps performed atomically from the caller's perspective:
     * 1. Load and validate the game (must exist and be in WAITING state).
     * 2. Assert [requestingUserId] is the host; throw [NotHostException] otherwise.
     * 3. Sanitize the roster — remove players whose WebSocket session has dropped.
     * 4. Guard minimum player count (≥ 2).
     * 5. Shuffle player IDs to produce a random turn order and persist it.
     * 6. Initialize each player's starting hand: 1× WHEAT_FIELD + 1× BAKERY
     *    (coins default to 3 and are already set by [PlayerDao.addPlayer]).
     * 7. Flip game status to IN_PROGRESS.
     *
     * The entire sequence runs inside a single database transaction so that any
     * mid-sequence failure rolls back completely and the lobby never wedges in
     * an inconsistent state.
     *
     * @param gameId           ID of the game to start.
     * @param requestingUserId User ID of the caller — must be the host.
     * @return [GameStateDto] ready to be broadcast via WebSocket.
     */
    @Transactional
    fun startGame(gameId: Int, requestingUserId: Int): GameStateDto {
        // 1. Load game
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game with id $gameId not found")

        if (game.status == GameStatus.IN_PROGRESS) {
            throw GameStartedException("Game with id $gameId has already started")
        }

        // 2. Host authorization
        if (game.hostUserId != requestingUserId) {
            throw NotHostException("Only the host can start the game (gameId=$gameId)")
        }

        // 3. Roster sanitization — keep only players still connected
        val connectedUserIds = connectionTracker.getConnectedUserIds(gameId)
        val allPlayers = playerDao.getPlayers(gameId)
        val activePlayers = allPlayers.filter { it.userId in connectedUserIds }

        // 4. Minimum player check
        if (activePlayers.size < 2) {
            throw CustomWebSocketException(
                errorCode = "NOT_ENOUGH_PLAYERS",
                message = "At least 2 connected players are required to start (gameId=$gameId, " +
                        "connected=${activePlayers.size})"
            )
        }

        // 5. Randomize turn order and persist
        val shuffledPlayers = activePlayers.shuffled()
        val shuffledPlayerIds = shuffledPlayers.map { it.id }
        shuffledPlayers.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index)
        }

        // 6. Initialize starting hand for each active player
        activePlayers.forEach { player ->
            playerCardDao.upsert(player.id, CardType.WHEAT_FIELD, 1)
            playerCardDao.upsert(player.id, CardType.BAKERY, 1)
        }

        // 7. Flip game to IN_PROGRESS
        gameDao.updateStatus(gameId, GameStatus.IN_PROGRESS)
        val updatedGame: GameModel = game.copy(status = GameStatus.IN_PROGRESS)

        // Build final player list from the already-loaded active list, reflecting
        // updated turnOrder values assigned in step 5 (avoids a second DB query).
        val finalPlayers = shuffledPlayers.mapIndexed { index, player ->
            player.copy(turnOrder = index)
        }

        val playerCards = finalPlayers.associate { player ->
            player.id to playerCardDao.findByPlayerId(player.id)
        }

        return GameStateDto(
            game = updatedGame,
            players = finalPlayers,
            playerCards = playerCards,
            turnOrder = shuffledPlayerIds,
        )
    }
}