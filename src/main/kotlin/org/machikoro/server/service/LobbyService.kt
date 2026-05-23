package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.LandmarkDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.LobbyLeavingOutcome
import org.machikoro.server.dto.LobbyRosterPlayerDto
import org.machikoro.server.dto.toDefinitionDto
import org.machikoro.server.exception.GameFinishedException
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.machikoro.server.exception.NotEnoughPlayersException
import org.machikoro.server.exception.NotHostException
import org.machikoro.server.exception.PlayerNotFoundException
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
open class LobbyService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val initializationService: InitializationService,
    private val playerCardDao: PlayerCardDao,
    private val cardDao: CardDao,
    private val landmarkDao: LandmarkDao,
) {

    // ConcurrentHashMap for thread-safe lock creation and removal
    private val lobbyLocks = ConcurrentHashMap<Int, Any>()

    /**
     * Runs [block] inside an Exposed transaction.
     *
     * Extracted as a protected open method so that pure unit tests can subclass
     * [LobbyService] and override this to `block()` directly, bypassing the
     * Exposed transaction machinery that requires a real database connection.
     */
    protected open fun <T> runInTransaction(block: () -> T): T = transaction { block() }

    companion object {
        // Machi Koro base game requires at least 2 players.
        const val MIN_PLAYERS = 2
        // Must match DebugService.PLAYER_USERNAMES / FILL_USERNAMES prefix
        const val DEBUG_USER_PREFIX = "debug_player"
    }

    /**
     * Creates a new lobby owned by [hostUserId] and returns the persisted
     * [GameModel]. The host is registered on the game record but is NOT
     * automatically added to the player roster — callers go through
     * [addUserToLobby] for that, the same as any other joining player.
     *
     * Wrapped in a single transaction so the create + re-fetch pair is atomic.
     */
    fun createLobby(hostUserId: Int): GameModel = runInTransaction {
        val gameId = gameDao.create(hostUserId)
        gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found after creation")
    }

    /**
     * Validates whether a lobby code exists.
     *
     * @throws GameNotFoundException if no lobby with the given code exists.
     */
    fun validateLobbyCode(lobbyCode: String): GameModel = runInTransaction {
        gameDao.findByLobbyCode(lobbyCode)
            ?: throw GameNotFoundException("Lobby with code $lobbyCode not found")
    }

    /**
     * Adds [userId] to the lobby identified by [lobbyCode].
     *
     * The lobby code is first resolved to the corresponding game. Then the existing
     * addUserToLobby logic is reused so all validation rules stay in one place.
     *
     * @throws GameNotFoundException if no lobby with [lobbyCode] exists.
     * @throws GameStartedException  if the game has already started.
     * @throws GameFinishedException if the game has already ended.
     * @throws LobbyFullException    if the lobby has already reached its player cap.
     */
    fun joinLobby(lobbyCode: String, userId: Int): PlayerModel = runInTransaction {
        val game = gameDao.findByLobbyCode(lobbyCode)
            ?: throw GameNotFoundException("Lobby with code $lobbyCode not found")

        addUserToLobby(game.id, userId)
    }

    /**
     * Returns the current lobby roster for [gameId], including usernames, ordered by turn order.
     */
    fun getLobbyRoster(gameId: Int): List<LobbyRosterPlayerDto> = runInTransaction {
        playerDao.getLobbyRoster(gameId)
    }

    /**
     * Adds [userId] to the lobby for [gameId] if the game exists, is still in the
     * WAITING state and has not yet reached its player cap.
     *
     * Existing players in the game can rejoin regardless of status (including
     * FINISHED) — the reconnect short-circuit returns their record without any
     * status check or DB write. New joins are rejected for both IN_PROGRESS and
     * FINISHED games.
     *
     * @throws GameNotFoundException        if no game with [gameId] exists.
     * @throws GameStartedException         if the game has already moved to IN_PROGRESS.
     * @throws GameFinishedException        if the game has already ended.
     * @throws LobbyFullException           if the lobby already has reached its player cap.
     */
    fun addUserToLobby(gameId: Int, userId: Int): PlayerModel {
        gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        // Reconnect path: player already belongs to this game, so do not re-insert.
        // Allowed regardless of game status — a player rejoining a FINISHED game
        // can still pull their final state via the same record.
        playerDao.findByGameIdAndUserId(gameId, userId)?.let { return it }

        synchronized(lobbyLocks.computeIfAbsent(gameId) { Any() }) {
            // Protect against duplicate inserts on concurrent reconnect/join attempts.
            playerDao.findByGameIdAndUserId(gameId, userId)?.let { return it }

            val game = gameDao.findById(gameId)
                ?: throw GameNotFoundException("Game $gameId not found")

            if (game.status == GameStatus.IN_PROGRESS) {
                throw GameStartedException("Game $gameId has already started")
            }

            if (game.status == GameStatus.FINISHED) {
                throw GameFinishedException("Game $gameId has already finished")
            }

            val players = playerDao.getPlayers(gameId)
            if (players.size >= game.maxPlayers) {
                throw LobbyFullException("Game $gameId is full (max ${game.maxPlayers} players)")
            }
            return playerDao.addPlayer(gameId, userId)
        }
    }

    /**
     * Deletes every game and its players from the database.
     * Debug-only — do not expose in production.
     * Returns the number of games deleted.
     */
    fun purgeAllGames(): Int = runInTransaction {
        val games = gameDao.findAll()
        games.forEach { game ->
            playerDao.deleteByGameId(game.id)
            gameDao.delete(game.id)
        }
        lobbyLocks.clear()
        games.size
    }

    /**
     * Removes the given [userId] from the lobby of [gameId].
     *
     * If the leaving player is the host, the lobby is deleted immediately.
     *
     * After a non-host player leaves, the lobby is automatically deleted when
     * no real players remain. A real player is any player whose username does
     * not start with [DEBUG_USER_PREFIX]. Debug users are treated as dummy
     * fill-players and do not keep a lobby alive.
     *
     * @param gameId the game/lobby identifier
     * @param userId the user leaving the lobby
     *
     * @return [LobbyLeavingOutcome.LobbyDeleted] if the lobby was deleted,
     * [LobbyLeavingOutcome.LobbyRemains] if the host is still present and the
     * lobby remains active.
     *
     * @throws PlayerNotFoundException if the user is not part of the lobby
     * @throws GameNotFoundException if the game does not exist
     */
    fun leaveLobby(gameId: Int, userId: Int): LobbyLeavingOutcome = runInTransaction {
        val player = playerDao.findByGameIdAndUserId(gameId, userId)
            ?: throw PlayerNotFoundException("Player $userId not found in game $gameId")

        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        val shouldDeleteLobby =
            player.userId == game.hostUserId

        if (!shouldDeleteLobby) {
            playerDao.deleteByPlayerId(player.id)
        }

        if (shouldDeleteLobby || noRealPlayersRemain(gameId)) {
            cleanUpLobby(gameId)

            return@runInTransaction LobbyLeavingOutcome.LobbyDeleted(gameId)
        }

        LobbyLeavingOutcome.LobbyRemains(userId)
    }


    /**
     * Returns `true` if no real players remain in the lobby.
     *
     * A real player is any player whose username does not start with
     * [DEBUG_USER_PREFIX]. Debug users are treated as dummy players.
     */
    private fun noRealPlayersRemain(gameId: Int): Boolean {
        return playerDao.getLobbyRoster(gameId)
            .none { !it.username.startsWith(DEBUG_USER_PREFIX) }
    }

    /**
     * Deletes the lobby and all associated players for the given [gameId].
     *
     * This removes all players, deletes the game row, and clears any
     * in-memory synchronization state for the lobby.
     */
    private fun cleanUpLobby(gameId: Int) {
        playerDao.deleteByGameId(gameId)
        gameDao.delete(gameId)
        lobbyLocks.remove(gameId)
    }

    /**
     * Removes all dummy players from the lobby and returns their roster entries.
     */
    fun resetLobby(lobbyCode: String): List<LobbyRosterPlayerDto> = runInTransaction {
        val game = gameDao.findByLobbyCode(lobbyCode)
            ?: throw GameNotFoundException("Lobby with code $lobbyCode not found")
        val dummies = playerDao.getLobbyRoster(game.id)
            .filter { it.username.startsWith(DEBUG_USER_PREFIX) }
        dummies.forEach { playerDao.deleteByPlayerId(it.playerId) }
        dummies
    }

    /**
     * Starts the game identified by [gameId].
     *
     * When [requestingUserId] is provided, the caller must be the lobby host —
     * otherwise [NotHostException] is thrown.
     *
     * Inside a single transaction this method:
     * 1. Validates the game exists and (optionally) that the caller is the host.
     * 2. Delegates all resource initialization to [InitializationService.initializeGame].
     * 3. Flips the game status to IN_PROGRESS.
     * 4. Returns a full [GameStateDto] snapshot including players, cards, landmarks, and marketplace.
     *
     * @throws GameNotFoundException       if no game with [gameId] exists.
     * @throws NotHostException            if [requestingUserId] is provided but is not the host.
     * @throws NotEnoughPlayersException   if fewer than [MIN_PLAYERS] players have joined.
     */
    fun startGame(gameId: Int, requestingUserId: Int? = null): GameStateDto {
        val result = synchronized(lobbyLocks.computeIfAbsent(gameId) { Any() }) {
            runInTransaction {
                val game = gameDao.findById(gameId)
                    ?: throw GameNotFoundException("Game $gameId not found")

                if (requestingUserId != null && game.hostUserId != requestingUserId) {
                    throw NotHostException("User $requestingUserId is not the host of game $gameId")
                }

                val players = playerDao.getPlayers(gameId)
                if (players.size < MIN_PLAYERS) {
                    throw NotEnoughPlayersException(
                        "Game $gameId needs at least $MIN_PLAYERS players to start, has ${players.size}"
                    )
                }

                val shuffled = initializationService.initializeGame(gameId)

                gameDao.updateStatus(gameId, GameStatus.IN_PROGRESS)
                val updatedGame = gameDao.findById(gameId)!!

                val playerIds = shuffled.map { it.id }
                // Build a full snapshot so the client can render immediately without extra round-trips.
                val playerCards = playerCardDao.findByPlayerIds(playerIds)
                val playerLandmarks = shuffled.associate { player ->
                    player.id to playerLandmarkDao.findByPlayerId(player.id)
                }
                val marketplace = gameMarketplaceDao.findByGameIdAsMap(gameId)
                val cardDefinitions = cardDao.findAll().map { it.toDefinitionDto() }
                val landmarkDefinitions = landmarkDao.findAll().map { it.toDefinitionDto() }
                val playerUsernames = playerDao.getLobbyRoster(gameId).associate { it.playerId to it.username }

                GameStateDto(
                    game = updatedGame,
                    players = shuffled,
                    playerCards = playerCards,
                    playerLandmarks = playerLandmarks,
                    marketplace = marketplace,
                    cardDefinitions = cardDefinitions,
                    landmarkDefinitions = landmarkDefinitions,
                    turnOrder = shuffled.map { it.userId },
                    activePlayerId = shuffled.firstOrNull()?.userId,
                    playerUsernames = playerUsernames,
                )
            }
        }
        // Game is now IN_PROGRESS; no new joins can happen, so the lock is no longer needed
        lobbyLocks.remove(gameId)
        return result
    }
}
