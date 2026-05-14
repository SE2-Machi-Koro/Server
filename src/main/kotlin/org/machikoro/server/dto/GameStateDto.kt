package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.domain.models.PlayerModel

/**
 * Full snapshot of the game state broadcast to every player when the game
 * starts and returned by `/app/game.sync` on reconnect.
 *
 * @property game             Updated [GameModel] (status = IN_PROGRESS).
 * @property players          Sanitized, active player list for this game.
 * @property playerCards      Map of playerId → list of [PlayerCardModel] representing each player's hand.
 * @property playerLandmarks  Map of playerId → list of [PlayerLandmarkModel] with each landmark's built / unbuilt state.
 * @property turnOrder        Ordered list of **player IDs** representing the randomized turn order
 *                            (index 0 goes first).
 */
@Schema(description = "Full game state broadcast on game start and returned by /app/game.sync on reconnect")
data class GameStateDto(
    @Schema(description = "Updated game model with status IN_PROGRESS")
    val game: GameModel,

    @Schema(description = "List of active players in the game")
    val players: List<PlayerModel>,

    @Schema(description = "Map of playerId to the player's starting hand")
    val playerCards: Map<Int, List<PlayerCardModel>>,

    @Schema(description = "Map of playerId to the player's landmarks (with built / unbuilt state)")
    val playerLandmarks: Map<Int, List<PlayerLandmarkModel>>,

    @Schema(description = "Randomized turn order as a list of player IDs")
    val turnOrder: List<Int>,
)

