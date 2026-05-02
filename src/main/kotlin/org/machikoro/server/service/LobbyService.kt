package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
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

@Service
class LobbyService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val gameMarketplaceDao: GameMarketplaceDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val connectionTracker: WebSocketConnectionTracker,
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
 * Creates a new lobby for a given host user.
 *
 * A lobby is represented as a Game with status WAITING.
 * This method:
 * 1. Creates a new game