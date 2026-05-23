package org.machikoro.server.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.tags.Tag
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Sets up OpenAPI docs shown in Swagger UI
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("Machi Koro API")
                .version("1.0.0")
                .description(
                    """
    REST and WebSocket API for the Machi Koro board game.
    
    ## WebSocket Connection
    Connect via STOMP at `ws://localhost:8080/ws` or use SockJS at `/ws-sockjs`.
    Authenticated clients must send `Authorization: Bearer <sessionToken>` on the STOMP `CONNECT` frame.
    
    ### Game Synchronization
    Subscribe to `/topic/game/{gameId}` for lobby and game-state broadcasts.
    Subscribe to `/queue/lobby-user{sessionId}` for private lobby replies and `/queue/game-sync-user{sessionId}` for reconnect snapshots.
    `/topic/public` is reserved for global chat only.
    
    | Client destination | Payload | Server publishes |
    |---|---|---|
    | `/app/lobby.create` | `WebSocketMessage` | `LOBBY_CREATED` to `/queue/lobby-user{sessionId}` |
    | `/app/lobby.join` | `WebSocketMessage` with `payload.lobbyCode` | `LOBBY_ROSTER` to `/queue/lobby-user{sessionId}`, `LOBBY_JOINED` to `/topic/game/{gameId}` |
    | `/app/game.start` | `StartGameRequest` | `GAME_STARTED`, `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.rollDice` | `RollDiceRequest` | `ROLL_DICE`, `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.resolveEffects` | `ResolveEffectsRequest` | `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.advancePhase` | `AdvancePhaseRequest` | `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.purchase` | `PurchaseRequest` | `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.endTurn` | `EndTurnRequest` | `GAME_ACTION` or `GAME_END` to `/topic/game/{gameId}` |
    | `/app/game.sync` | `SyncGameRequest` | `SYNC` to `/queue/game-sync-user{sessionId}` |
    
    See `docs/websocket-game-protocol.md` for payload examples and reconnect flow.
    """
                        .trimIndent()
                )
        )
        .tags(
            listOf(
                Tag().name("WebSocket")
                    .description("WebSocket endpoints documented manually — not auto-scanned by Springdoc"),
                Tag().name("Game").description("Game management endpoints")
            )
        )
}
