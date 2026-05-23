package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "Result of leaving lobby",
    oneOf = [LobbyLeavingOutcome.LobbyRemains::class, LobbyLeavingOutcome.LobbyDeleted::class]
)
sealed interface LobbyLeavingOutcome {

    @Schema(description = "Normal player left the game, lobby remains for other players")
    data class LobbyRemains(
        @field:Schema(description = "ID of the user leaving", example = "12")
        val userId: Int
    ) : LobbyLeavingOutcome

    @Schema(description = "Host left the game, lobby was deleted")
    data class LobbyDeleted(
        @field:Schema(description = "ID of the game to close lobby for", example = "1")
        val gameId: Int
    ) : LobbyLeavingOutcome
}