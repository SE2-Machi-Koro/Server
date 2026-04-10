package org.machikoro.server.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.machikoro.server.domain.CardColor
import org.machikoro.server.domain.GameStatus
import org.machikoro.server.domain.TurnPhase
import org.machikoro.server.domain.CardType
import org.machikoro.server.domain.LandmarkType

// STATIC CLASSES -----------------------------

object Cards : IntIdTable("cards") {
    val cardType = enumerationByName("card_type", 50, CardType::class).uniqueIndex()
    val name = varchar("name", 100)
    val cost = integer("cost")
    val diceMin = integer("dice_min")
    val diceMax = integer("dice_max")
    val income = integer("income")
}

object Landmarks : IntIdTable("landmarks") {
    val landmarkType = enumerationByName("landmark_type", 50, LandmarkType::class).uniqueIndex()
    val name = varchar("name", 100)
    val cost = integer("cost")
}

// DYNAMIC CLASSES -----------------------------

object Users : IntIdTable("users") {
    val username = varchar("username", 50).uniqueIndex()
    val sessionToken = varchar("session_token", 255).nullable().uniqueIndex()
    val totalWins = integer("total_wins").default(0)
    val totalGamesPlayed = integer("total_games_played").default(0)
}

object Games : IntIdTable("games") {
    val status = enumerationByName("status", 20, GameStatus::class).default(GameStatus.WAITING)
    val hostUserId = reference("host_user_id", Users)
    val currentTurnIndex = integer("current_turn_index").default(0)
    val turnPhase = enumerationByName("turn_phase", 20, TurnPhase::class).default(TurnPhase.ROLL_DICE)
    val lastDiceRoll = integer("last_dice_roll").nullable()
    val roundNumber = integer("round_number").default(1)
}

object Players : IntIdTable("players") {
    val gameId = reference("game_id", Games, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users)
    val turnOrder = integer("turn_order")
    val coins = integer("coins").default(3)

    init {
        uniqueIndex(gameId, userId)
    }
}

object PlayerCards : Table("player_cards") {
    val playerId = reference("player_id", Players, onDelete = ReferenceOption.CASCADE)
    val cardType = enumerationByName("card_type", 50, CardType::class)
    val quantity = integer("quantity").default(1)

    override val primaryKey = PrimaryKey(playerId, cardType)
}

object PlayerLandmarks : Table("player_landmarks") {
    val playerId     = reference("player_id", Players, onDelete = ReferenceOption.CASCADE)
    val landmarkType = enumerationByName("landmark_type", 50, LandmarkType::class)
    val isBuilt      = bool("is_built").default(false)

    override val primaryKey = PrimaryKey(playerId, landmarkType)
}

object GameMarketplace : Table("game_marketplace") {
    val gameId            = reference("game_id", Games, onDelete = ReferenceOption.CASCADE)
    val cardType          = enumerationByName("card_type", 50, CardType::class)
    val quantityAvailable = integer("quantity_available")

    override val primaryKey = PrimaryKey(gameId, cardType)
}