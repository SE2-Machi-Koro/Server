package org.machikoro.server.dto

import io.swagger.v3.oas.annotations.media.Schema
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase

@Schema(description = "Client screen the server considers current for an authenticated user")
enum class ClientScreen {
    START,
    MAIN,
    LOBBY,
    GAME,
}

@Schema(description = "Server-resolved navigation state for login and reconnect flows")
data class SessionStateDto(
    @Schema(description = "Current screen the client should display")
    val screen: ClientScreen,
    @Schema(description = "Current game ID when the user belongs to an active lobby or game")
    val gameId: Int? = null,
    @Schema(description = "Current player row ID for the user in that game")
    val playerId: Int? = null,
    @Schema(description = "Lobby code when the user is in a waiting lobby or active game")
    val lobbyCode: String? = null,
    @Schema(description = "Current game status when a valid game membership exists")
    val gameStatus: GameStatus? = null,
    @Schema(description = "Current turn phase when a valid game membership exists")
    val turnPhase: TurnPhase? = null,
)

@Schema(description = "Response from a successful login — caller stores the session token and uses it for subsequent authenticated calls")
data class LoginResponse(
    @Schema(description = "Opaque session token. Treat as a secret. Echo back to /auth/logout to invalidate.", example = "550e8400-e29b-41d4-a716-446655440000")
    val sessionToken: String,
    @Schema(description = "Canonical (trimmed and lower-cased) username of the authenticated user", example = "alice")
    val username: String,
    @Schema(description = "Unique ID of the authenticated user", example = "42")
    val userId: Int,
    @Schema(description = "Server-resolved state that tells the client whether to show main, lobby, or game UI")
    val sessionState: SessionStateDto = SessionStateDto(screen = ClientScreen.MAIN),
)
