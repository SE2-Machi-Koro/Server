package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.machikoro.server.domain.enums.CardType
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
 * @property marketplace      Map of [CardType] → remaining quantity in the game's marketplace supply.
 * @property cardDefinitions  DB-backed card definitions for rendering the shop.
 * @property landmarkDefinitions DB-backed landmark definitions for rendering build options.
 * @property turnOrder        Ordered list of **user IDs** representing the randomized turn order
 *                            (index 0 goes first). Same ID space as [activePlayerId].
 * @property activePlayerId   userId of the player whose turn it is at the time of the snapshot.
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

    @Schema(description = "Map of CardType to the remaining quantity available in the marketplace supply")
    val marketplace: Map<CardType, Int>,

    @Schema(description = "DB-backed card definitions available to the shop UI")
    val cardDefinitions: List<CardDefinitionDto> = emptyList(),

    @Schema(description = "DB-backed landmark definitions available to the shop UI")
    val landmarkDefinitions: List<LandmarkDefinitionDto> = emptyList(),

    @Schema(description = "Randomized turn order as a list of user IDs (same space as activePlayerId)")
    val turnOrder: List<Int>,

    @Schema(description = "userId of the player whose turn it is (matches the userId the client received at login)")
    val activePlayerId: Int? = null,

    @Schema(description = "Map of playerId to username for display name resolution on the client")
    val playerUsernames: Map<Int, String> = emptyMap(),
)
