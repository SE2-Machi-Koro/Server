package org.machikoro.server.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.machikoro.server.domain.GameStatus

// Used for leaderboard
object Users : IntIdTable("users") {
    val userName = varchar("username", 50).uniqueIndex()
    val totalWins = integer("total_wins").default(0)
    val totalGamesPlayed = integer("total_games_played").default(0)
}

// Games
object Games : IntIdTable("games") {
    val status = enumerationByName("status", 20, GameStatus::class).default(GameStatus.WAITING)
    val currentTurnIndex = integer("current_turn_index").default(0)
    val lastDiceRoll = integer("last_dice_roll").nullable()
}

// Players in game
object Players : IntIdTable("players") {
    val gameId = reference("game_id", Games, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users)
    val turnOrder = integer("turn_order")
    val coins = integer("coins").default(3)
}

// Cards that a player has
object PlayerCards : IntIdTable("player_cards") {
    val playerId = reference("player_id", Players, onDelete = ReferenceOption.CASCADE)
    val cardType = varchar("card_type", 50)
    val quantity = integer("quantity").default(0)
}

// Landmarks a player owns
object PlayerLandmarks : IntIdTable("player_landmarks") {
    val playerId = reference("player_id", Players, onDelete = ReferenceOption.CASCADE)
    val landmarkType = varchar("landmark_type", 50)
    val isBuilt = bool("is_built").default(false)
}

// Marketplace in game
object GameMarketplace : IntIdTable("game_marketplace") {
    val gameId = reference("game_id", Games, onDelete = ReferenceOption.CASCADE)
    val cardType = varchar("card_type", 50)
    val quantityAvailable = integer("quantity_available")
}