package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.exception.LobbyFullException
import org.machikoro.server.exception.NotHostException
import org.springframework.stereotype.Service

@Service
open class LobbyService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
) {

    private val lobbyLocks = mutableMapOf<Int, Any>()

    /**
     * Runs [block] inside an Exposed transaction.
     *
     * Extracted as a protected open method so that pure unit tests can subclass
     * [LobbyService] and override this to `block()` directly, bypassing the
     * Exposed transaction machinery that requires a real database connection.
     */
    protected open fun <T> runInTransaction(block: () -> T): T = transaction { block() }

    companion object {
        /**
         * Temporary offset applied to all player turn orders in the first pass of
         * the shuffle so that intermediate values don't collide with the unique
         * (gameId, turnOrder) constraint while the final values are being assigned.
         * Any value larger than the max players limit works; 10 000 provides headroom.
         */
        private const val TEMP_TURN_ORDER_OFFSET = 10_000
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
     * Adds [userId] to the lobby for [gameId] if the game exists, is still in the
     * WAITING state and has not yet reached its player cap.
     *
     * The status check, capacity check, and insert all occur inside the same
     * lock and re-fetch from the DB, ensuring no other thread can sneak in a
     * concurrent join or a game-start transition between the checks and the insert.
     *
     * @throws GameNotFoundException  if no game with [gameId] exists.
     * @throws GameStartedException   if the game has already moved to IN_PROGRESS.
     * @throws LobbyFullException     if the lobby already has reached its player cap.
     */
    fun addUserToLobby(gameId: Int, userId: Int): PlayerModel {
        // Early reconnect check outside the lock: if the player is already in the
        // game there is nothing to do and no need to contend on the lock.
        playerDao.findByGameIdAndUserId(gameId, userId)?.let { return it }

        synchronized(lobbyLocks.getOrPut(gameId) { Any() }) {
            // Re-check inside the lock: another thread may have inserted this player
            // or started the game between the pre-lock check above and acquiring the lock.
            playerDao.findByGameIdAndUserId(gameId, userId)?.let { return it }

            // Re-fetch game state inside the lock so the status and maxPlayers values
            // reflect any transition (e.g. startGame) that completed just before we
            // acquired the lock.
            val game = gameDao.findById(gameId)
                ?: throw GameNotFoundException("Game $gameId not found")

            if (game.status == GameStatus.IN_PROGRESS) {
                throw GameStartedException("Game $gameId has already started")
            }

            val players = playerDao.getPlayers(gameId)
            if (players.size >= game.maxPlayers) {
                throw LobbyFullException("Game $gameId is full (max ${game.maxPlayers} players)")
            }

            return playerDao.addPlayer(gameId, userId)
        }
    }

    /**
     * Starts the game identified by [gameId].
     *
     * When [requestingUserId] is provided, the caller must be the lobby host —
     * otherwise [NotHostException] is thrown.
     *
     * Inside a single transaction this method:
     * 1. Validates the game exists and (optionally) that the caller is the host.
     * 2. Randomly shuffles the player turn order using a two-pass update to avoid
     *    unique-constraint collisions on the (gameId, turnOrder) pair.
     * 3. Initialises the marketplace supply rows for the game.
     * 4. Initialises landmark entries for every player.
     * 5. Flips the game status to IN_PROGRESS.
     *
     * @throws GameNotFoundException if no game with [gameId] exists.
     * @throws NotHostException      if [requestingUserId] is provided but is not the host.
     */
    fun startGame(gameId: Int, requestingUserId: Int? = null): GameStateDto = runInTransaction {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        if (requestingUserId != null && game.hostUserId != requestingUserId) {
            throw NotHostException("User $requestingUserId is not the host of game $gameId")
        }

        val players = playerDao.getPlayers(gameId)
        val shuffled = players.shuffled()

        // Two-pass update to avoid unique (gameId, turnOrder) constraint violations
        // while reassigning turn orders.
        shuffled.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index + TEMP_TURN_ORDER_OFFSET)
        }
        shuffled.forEachIndexed { index, player ->
            playerDao.updateTurnOrder(player.id, index)
        }

        gameMarketplaceDao.initForGame(gameId)
        shuffled.forEach { player -> playerLandmarkDao.initForPlayer(player.id) }

        gameDao.updateStatus(gameId, GameStatus.IN_PROGRESS)
        val updatedGame = gameDao.findById(gameId)!!

        GameStateDto(
            game = updatedGame,
            players = shuffled,
            playerCards = emptyMap(),
            turnOrder = shuffled.map { it.id },
        )
    }
}