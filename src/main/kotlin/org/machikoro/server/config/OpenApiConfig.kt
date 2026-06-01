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
    Subscribe to `/queue/lobby-user{sessionId}` for private lobby replies, `/user/queue/game-sync` for reconnect snapshots,
    and `/user/queue/errors` for private domain rejections.
    `/topic/public` is reserved for global chat only.

    ### Authoritative Turn Loop
    The server advances phases only after successful domain actions:
    `ROLL_DICE --rollDice--> RESOLVE_EFFECTS --resolveEffects--> BUY_OR_BUILD --endTurn--> ROLL_DICE`.
    A purchase is optional during `BUY_OR_BUILD` and at most one purchase is accepted per turn.
    `/app/game.advancePhase` is retained for compatibility only and rejects gameplay requests with `DIRECT_PHASE_ADVANCE_FORBIDDEN`.
    
    | Client destination | Payload | Server publishes |
    |---|---|---|
    | `/app/lobby.create` | `WebSocketMessage` | `LOBBY_CREATED` to `/queue/lobby-user{sessionId}` |
    | `/app/lobby.join` | `WebSocketMessage` with `payload.lobbyCode` | `LOBBY_ROSTER` to `/queue/lobby-user{sessionId}`, `LOBBY_JOINED` to `/topic/game/{gameId}` |
    | `/app/game.start` | `StartGameRequest` | `GAME_STARTED`, `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.rollDice` | `RollDiceRequest` | `ROLL_DICE`, `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.resolveEffects` | `ResolveEffectsRequest` | `GAME_ACTION` to `/topic/game/{gameId}` |
    | `/app/game.advancePhase` | `AdvancePhaseRequest` | `DIRECT_PHASE_ADVANCE_FORBIDDEN` to `/user/queue/errors`; no state changes |
    | `/app/game.purchase` | `PurchaseRequest` | `GAME_ACTION`, or `ERROR` with `payload.event = PURCHASE_FAILED`, to `/topic/game/{gameId}` |
    | `/app/game.endTurn` | `EndTurnRequest` | `GAME_ACTION` or `GAME_END` to `/topic/game/{gameId}` |
    | `/app/game.sync` | `SyncGameRequest` | `SYNC` to resolved `/queue/game-sync-user{sessionId}` |
    
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
