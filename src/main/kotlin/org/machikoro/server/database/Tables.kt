package org.machikoro.server.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.LandmarkType

// STATIC CLASSES -----------------------------

/**
 * Static Tables define the rules of the game and do not change every match
 * They act as reference data
 */

/**
 * Represents all possible establishment cards
 * Each row defines a card type (Wheat Field etc.)
 * Ownership of cards is handled in PlayerCards
 */
object Cards : IntIdTable("cards") {

    // Unique ensures exactly one definition per card type
    val cardType = enumerationByName("card_type", 50, CardType::class).uniqueIndex()

    // Activation ranges
    val diceMin = integer("dice_min")
    val diceMax = integer("dice_max")
    val income = integer("income")
}

/**
 * Represents all possible landmarks
 * Defines cost and name of each landmark
 */
object Landmarks : IntIdTable("landmarks") {
    val landmarkType = enumerationByName("landmark_type", 50, LandmarkType::class).uniqueIndex()
}

// DYNAMIC CLASSES -----------------------------

/**
 * Dynamic tables change during the game
 * Represent users, matches and their current state
 */

/**
 * Represents registered users of the system
 * sessionToken is nullable, meaning it's only present when logged in
 */
object Users : IntIdTable("users") {
    val username = varchar("username", 50).uniqueIndex()
    val passwordHash = varchar("password_hash", 60).nullable()
    val sessionToken = varchar("session_token", 255).nullable().uniqueIndex()
    val totalWins = integer("total_wins").default(0)
    val totalGamesPlayed = integer("total_games_played").default(0)
}

/**
 * Represents a single game session
 * Games table tracks the overall state of a game
 */
object Games : IntIdTable("games") {
    val status = enumerationByName("status", 20, GameStatus::class).default(GameStatus.WAITING)
    val hostUserId = reference("host_user_id", Users, onDelete = ReferenceOption.RESTRICT)
    val lobbyCode = varchar("lobby_code", 7).uniqueIndex()
    val maxPlayers = integer("max_players").default(4)
    val currentTurnIndex = integer("current_turn_index").default(0)
    val turnPhase = enumerationByName("turn_phase", 20, TurnPhase::class).default(TurnPhase.ROLL_DICE)
    val lastDiceRoll = integer("last_dice_roll").nullable()
    val roundNumber = integer("round_number").default(1)
    val hasPurchasedThisTurn = bool("has_purchased_this_turn").default(false)
}

/**
 * Represents a user inside a specific game -> Player
 * Join table between Users and Games with extra states
 * A user can only join a game once
 * Deleting a game removes all players
 */
object Players : IntIdTable("players") {
    val gameId = reference("game_id", Games, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users, onDelete = ReferenceOption.CASCADE)
    val turnOrder = integer("turn_order")
    val coins = integer("coins").default(3)

    /*
    Long instead of a boolean flag, since a flag has the problem that it can go stale.
    If server crashes or a websocket drops without a clean disconnect,
    isConnected = true stays in the DB forever - flag is never flipped
     */
    val lastSeenAt = long("last_seen_at").nullable()

    init {
        uniqueIndex(gameId, userId)
        uniqueIndex(gameId, turnOrder)
    }
}

/**
 * Represents the cards owned by each player
 * Removing a player removes their cards automatically
 */
object PlayerCards : Table("player_cards") {
    val playerId = reference("player_id", Players, onDelete = ReferenceOption.CASCADE)
    val cardId = reference("card_id", Cards, onDelete = ReferenceOption.RESTRICT)
    val quantity = integer("quantity").default(1)

    override val primaryKey = PrimaryKey(playerId, cardId)
}

/**
 * Represents the landmarks owned and the built-status of a player
 */
object PlayerLandmarks : Table("player_landmarks") {
    val playerId = reference("player_id", Players, onDelete = ReferenceOption.CASCADE)
    val landmarkId = reference("landmark_id", Landmarks, onDelete = ReferenceOption.RESTRICT)
    val isBuilt = bool("is_built").default(false)

    override val primaryKey = PrimaryKey(playerId, landmarkId)
}

/**
 * Represents the shared marketplace for a specific game
 * Each game has its own card supply
 * Removing a game removes its marketplace
 */
object GameMarketplace : Table("game_marketplace") {
    val gameId = reference("game_id", Games, onDelete = ReferenceOption.CASCADE)
    val cardId = reference("card_id", Cards, onDelete = ReferenceOption.RESTRICT)
    val quantityAvailable = integer("quantity_available")

    override val primaryKey = PrimaryKey(gameId, cardId)
}
