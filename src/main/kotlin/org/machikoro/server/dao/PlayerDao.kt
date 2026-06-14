package org.machikoro.server.dao

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.machikoro.server.database.Games
import org.machikoro.server.database.Players
import org.machikoro.server.database.Users
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.LobbyRosterPlayerDto
import org.machikoro.server.exception.PlayerNotFoundException
import org.springframework.stereotype.Repository

@Repository
class PlayerDao {

    data class PlayerGameMembership(
        val player: PlayerModel,
        val game: GameModel,
    )

    private fun ResultRow.toModel() = PlayerModel(
        id = this[Players.id].value,
        gameId = this[Players.gameId].value,
        userId = this[Players.userId].value,
        turnOrder = this[Players.turnOrder],
        coins = this[Players.coins],
        lastSeenAt = this[Players.lastSeenAt]
    )

    private fun ResultRow.toGameModel() = GameModel(
        id = this[Games.id].value,
        status = this[Games.status],
        hostUserId = this[Games.hostUserId].value,
        lobbyCode = this[Games.lobbyCode],
        maxPlayers = this[Games.maxPlayers],
        currentTurnIndex = this[Games.currentTurnIndex],
        turnPhase = this[Games.turnPhase],
        lastDiceRoll = this[Games.lastDiceRoll],
        roundNumber = this[Games.roundNumber],
        hasPurchasedThisTurn = this[Games.hasPurchasedThisTurn],
        rerolledThisTurn = this[Games.rerolledThisTurn]
    )

    /**
     * Finds a player by their unique ID.
     * @param id Player ID
     * @return PlayerModel or null if not found
     */
    fun findById(id: Int): PlayerModel? = transaction {
        Players.selectAll()
            .where { Players.id eq id }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Retrieves all players in a given game.
     * @param gameId Game ID
     * @return List of PlayerModel
     */
    fun getPlayers(gameId: Int): List<PlayerModel> = transaction {
        Players.selectAll()
            .where { Players.gameId eq gameId }
            .orderBy(Players.turnOrder to SortOrder.ASC)
            .map { it.toModel() }
    }

    /**
     * Retrieves the current lobby roster with usernames for a given game.
     */
    fun getLobbyRoster(gameId: Int): List<LobbyRosterPlayerDto> = transaction {
        Players.join(
            Users,
            JoinType.INNER,
            additionalConstraint = { Players.userId eq Users.id }
        )
            .selectAll()
            .where { Players.gameId eq gameId }
            .orderBy(Players.turnOrder to SortOrder.ASC)
            .map {
                LobbyRosterPlayerDto(
                    playerId = it[Players.id].value,
                    userId = it[Players.userId].value,
                    username = it[Users.username],
                    gameId = it[Players.gameId].value,
                    turnOrder = it[Players.turnOrder],
                    coins = it[Players.coins],
                )
            }
    }

    /**
     * Finds a player's row by (gameId, userId), if already present.
     */
    fun findByGameIdAndUserId(gameId: Int, userId: Int): PlayerModel? = transaction {
        Players.selectAll()
            .where { (Players.gameId eq gameId) and (Players.userId eq userId) }
            .singleOrNull()
            ?.toModel()
    }

    /**
     * Returns the most recent IN_PROGRESS game ID the given user belongs to.
     */
    fun findActiveGameIdByUserId(userId: Int): Int? = transaction {
        Players.join(
            Games,
            JoinType.INNER,
            additionalConstraint = { Players.gameId eq Games.id }
        )
            .selectAll()
            .where { (Players.userId eq userId) and (Games.status eq GameStatus.IN_PROGRESS) }
            .orderBy(Games.id to SortOrder.DESC)
            .firstOrNull()
            ?.get(Players.gameId)
            ?.value
    }

    fun findWaitingGameIdsByUserId(userId: Int): List<Int> = transaction {
        Players.join(
            Games,
            JoinType.INNER,
            additionalConstraint = { Players.gameId eq Games.id }
        )
            .selectAll()
            .where { (Players.userId eq userId) and (Games.status eq GameStatus.WAITING) }
            .map { it[Players.gameId].value }
    }

    fun findWaitingMembershipByUserId(userId: Int): PlayerGameMembership? = transaction {
        Players.join(
            Games,
            JoinType.INNER,
            additionalConstraint = { Players.gameId eq Games.id }
        )
            .selectAll()
            .where { (Players.userId eq userId) and (Games.status eq GameStatus.WAITING) }
            .orderBy(Games.id to SortOrder.DESC)
            .firstOrNull()
            ?.let { row ->
                PlayerGameMembership(
                    player = row.toModel(),
                    game = row.toGameModel(),
                )
            }
    }

    /**
     * Returns the newest valid membership for a reconnect/login decision.
     *
     * IN_PROGRESS games take precedence over WAITING lobbies so a client that
     * missed the start transition is sent to the game screen instead of back to
     * lobby creation. FINISHED games are intentionally ignored because they are
     * not a current navigation target after a fresh login.
     */
    fun findCurrentMembershipByUserId(userId: Int): PlayerGameMembership? = transaction {
        fun newestMembershipByStatus(status: GameStatus): PlayerGameMembership? =
            Players.join(
                Games,
                JoinType.INNER,
                additionalConstraint = { Players.gameId eq Games.id }
            )
                .selectAll()
                .where { (Players.userId eq userId) and (Games.status eq status) }
                .orderBy(Games.id to SortOrder.DESC)
                .firstOrNull()
                ?.let { row ->
                    PlayerGameMembership(
                        player = row.toModel(),
                        game = row.toGameModel(),
                    )
                }

        newestMembershipByStatus(GameStatus.IN_PROGRESS)
            ?: newestMembershipByStatus(GameStatus.WAITING)
    }

    /**
     * Returns counter of players in game who didn't leave yet
     */
    fun countByGameId(gameId: Int): Int = transaction {
        Players.selectAll()
            .where { Players.gameId eq gameId }
            .count()
            .toInt()
    }

    /**
     * Adds a new player to a game.
     * All steps run in a single transaction to prevent duplicate turnOrder on concurrent joins.
     * @param gameId Game ID
     * @param userId User ID
     * @return The created PlayerModel
     */
    fun addPlayer(gameId: Int, userId: Int): PlayerModel = transaction {
        val turnOrder = Players.selectAll()
            .where { Players.gameId eq gameId }
            .count()
            .toInt()

        val playerId = Players.insertAndGetId {
            it[Players.gameId] = gameId
            it[Players.userId] = userId
            it[Players.turnOrder] = turnOrder
            it[Players.coins] = 3
        }.value

        Players.selectAll()
            .where { Players.id eq playerId }
            .single()
            .toModel()
    }

    /**
     * Updates the coin count for a player.
     * @param playerId Player ID
     * @param newCoins New coin value
     */
    fun updateCoins(playerId: Int, newCoins: Int): Unit = transaction {
        val updatedRows = Players.update({ Players.id eq playerId }) {
            it[Players.coins] = newCoins
        }
        if (updatedRows == 0) throw PlayerNotFoundException("Player $playerId not found")
    }

    // --- The following methods are kept for future use and will be reviewed in Sprint 3 ---

    /**
     * Finds all players in the database.
     * @return List of PlayerModel
     */
    fun findAll(): List<PlayerModel> = transaction {
        Players.selectAll().map { it.toModel() }
    }

    /**
     * Updates the turn order for a player.
     * @param playerId Player ID
     * @param newOrder New turn order
     */
    fun updateTurnOrder(playerId: Int, newOrder: Int): Unit = transaction {
        val updatedRows = Players.update({ Players.id eq playerId }) {
            it[Players.turnOrder] = newOrder
        }
        if (updatedRows == 0) throw PlayerNotFoundException("Player $playerId not found")
    }

    /**
     * Updates the lastSeenAt timestamp for a player.
     * Called on every heartbeat/reconnect to track connection status.
     * @param playerId Player ID
     */
    fun updateLastSeen(playerId: Int): Unit = transaction {
        val updatedRows = Players.update({ Players.id eq playerId }) {
            it[Players.lastSeenAt] = System.currentTimeMillis()
        }
        if (updatedRows == 0) throw PlayerNotFoundException("Player $playerId not found")
    }

    /**
     * Deletes a player by their ID.
     * @param playerId Player ID
     */
    fun deleteByPlayerId(playerId: Int): Unit = transaction {
        val deletedRows = Players.deleteWhere { Players.id eq playerId }
        if (deletedRows == 0) throw PlayerNotFoundException("Player $playerId not found")
    }

    /**
    * Deletes all players in a given game.
    * @param gameId Game ID
    */
    fun deleteByGameId(gameId: Int): Unit = transaction {
        Players.deleteWhere { Players.gameId eq gameId }
    }
}
