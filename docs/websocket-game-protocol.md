# WebSocket Game Synchronization Protocol

This document defines the client-facing STOMP contract for Machi Koro lobby updates, game-state broadcasts, turn actions, errors, and reconnect synchronization.

## Connection

Clients connect with STOMP over one of the registered WebSocket endpoints:

| Transport | Endpoint | Typical client |
| --- | --- | --- |
| Native WebSocket | `/ws` | Android and WebSocket-capable clients |
| SockJS | `/ws-sockjs` | Browser clients that need SockJS fallback transports |

The application destination prefix is `/app`. Broker subscriptions use `/topic` for shared broadcasts and `/queue` or `/user/queue` for session-scoped replies.

Every STOMP `CONNECT` frame must include the authenticated session token returned by the login API:

```text
Authorization: Bearer <sessionToken>
```

The server resolves the authenticated user from this token. Clients must not send user IDs or sender names as an authorization mechanism; sensitive actions derive identity from the STOMP principal and server-side room membership.

## Subscriptions

Subscribe before sending lobby or game commands so immediate responses are not missed.

| Subscription | Scope | Purpose |
| --- | --- | --- |
| `/topic/game/{gameId}` | All players in one lobby or game | Lobby membership changes, game start, dice, phase changes, purchases, resolved effects, game end, and game-topic errors |
| `/queue/lobby-user{sessionId}` | Single WebSocket session | Lobby creation result, lobby roster after join, invalid lobby-code errors |
| `/user/queue/game-sync` | Single WebSocket session | Full `SYNC` snapshot for reconnect and explicit state refresh |
| `/user/queue/errors` | Single WebSocket session | `CustomWebSocketException` and unexpected WebSocket handler failures |
| `/topic/public` | Global chat only | Chat messages and chat join announcements from `/app/chat.*` |

`{sessionId}` is the STOMP session id assigned by Spring. Browser clients usually receive it from the STOMP session object after connection.

The server sends private lobby replies to `/queue/lobby-user{sessionId}`. It sends reconnect snapshots to the resolved broker destination `/queue/game-sync-user{sessionId}`; clients consume that reply by subscribing to `/user/queue/game-sync`.

Game clients must not use `/topic/public` for lobby or game state. All game-state broadcasts are scoped to `/topic/game/{gameId}` or to a private queue.

## Send Destinations

| Client sends to | Payload | Server publishes |
| --- | --- | --- |
| `/app/chat.addUser` | `WebSocketMessage`, optional `gameId` | Chat join message to `/topic/public`; may also emit `SYNC` to `/user/queue/game-sync` when the user has an active in-progress game |
| `/app/chat.send` | `WebSocketMessage` | Chat message to `/topic/public` |
| `/app/lobby.create` | `WebSocketMessage` body; sender ignored | `LOBBY_CREATED` to `/queue/lobby-user{sessionId}` |
| `/app/lobby.join` | `WebSocketMessage` with `payload.lobbyCode` | `LOBBY_ROSTER` to `/queue/lobby-user{sessionId}` and `LOBBY_JOINED` to `/topic/game/{gameId}` |
| `/app/lobby.leave` | `WebSocketMessage` with `payload.gameId` | `LOBBY_LEFT` or `HOST_LEFT` to `/topic/game/{gameId}` |
| `/app/game.start` | `StartGameRequest` | `GAME_STARTED` and `GAME_ACTION` snapshots to `/topic/game/{gameId}` |
| `/app/game.enterScreen` | `EnterGameScreenRequest` | Idempotent initialization path; host may trigger `GAME_STARTED` and `GAME_ACTION` to `/topic/game/{gameId}` |
| `/app/game.rollDice` | `RollDiceRequest` | `ROLL_DICE` and `GAME_ACTION` snapshots to `/topic/game/{gameId}` |
| `/app/game.resolveEffects` | `ResolveEffectsRequest` | `GAME_ACTION` income/effects result to `/topic/game/{gameId}` |
| `/app/game.advancePhase` | `AdvancePhaseRequest` | `GAME_ACTION` phase snapshot to `/topic/game/{gameId}` |
| `/app/game.purchase` | `PurchaseRequest` | `GAME_ACTION` purchase snapshot or `ERROR` purchase failure to `/topic/game/{gameId}` |
| `/app/game.endTurn` | `EndTurnRequest` | `GAME_ACTION` next-turn snapshot or `GAME_END` to `/topic/game/{gameId}` |
| `/app/game.sync` | `SyncGameRequest` | `SYNC` to `/user/queue/game-sync` |

## Request Payloads

Start game:

```json
{
  "gameId": 42
}
```

Roll dice:

```json
{
  "gameId": 42,
  "rollTwoDice": false
}
```

Resolve effects, advance phase, and end turn all use the same minimal game-scoped request shape:

```json
{
  "gameId": 42
}
```

Purchase an establishment:

```json
{
  "gameId": 42,
  "purchaseType": "ESTABLISHMENT",
  "cardType": "BAKERY"
}
```

Build a landmark:

```json
{
  "gameId": 42,
  "purchaseType": "LANDMARK",
  "landmarkType": "TRAIN_STATION"
}
```

Explicit reconnect sync:

```json
{
  "gameId": 42
}
```

`SyncGameRequest.gameId` is optional. When omitted, the server attempts to resolve the authenticated user's active `IN_PROGRESS` game.

## Message Envelope

Most WebSocket responses use `WebSocketMessage`:

```json
{
  "type": "GAME_ACTION",
  "sender": "server",
  "content": "Optional human-readable text",
  "payload": {},
  "timestamp": 1714000000000,
  "gameId": 42
}
```

Core game message types:

| Type | Meaning |
| --- | --- |
| `GAME_STARTED` | The host started the game. The payload is the full `GameStateDto`. |
| `GAME_ACTION` | A state-changing game action completed. The payload includes `event`, `turnPhase`, `activePlayerId`, and `state`. |
| `ROLL_DICE` | Dice were rolled. The payload includes dice values, total, completion flag, and a state snapshot. |
| `SYNC` | Private reconnect snapshot. The payload includes `targetUserId`, `targetSessionId`, and `state`. |
| `GAME_END` | The game has ended. The payload includes `winnerId`, `roundsPlayed`, and final `state`. |
| `ERROR` | A command failed. Game-topic errors use `WebSocketMessage`; user-queue handler errors use `WebSocketErrorResponse`. |

Lobby-specific message types include `LOBBY_CREATED`, `LOBBY_JOINED`, `LOBBY_ROSTER`, `LOBBY_LEFT`, and `HOST_LEFT`.

`GameStateDto` is the authoritative board snapshot. Important fields for client reconciliation are:

| Field | Meaning |
| --- | --- |
| `game.status` | Current game lifecycle state, for example `WAITING`, `IN_PROGRESS`, or `FINISHED` |
| `game.turnPhase` | Current turn phase, for example `ROLL_DICE`, `EARN_INCOME`, or `BUY_OR_BUILD` |
| `players` | Active players in the game |
| `playerCards` | Player-owned establishments keyed by player id |
| `playerLandmarks` | Landmark build state keyed by player id |
| `marketplace` | Remaining card supply keyed by `CardType` |
| `turnOrder` | Ordered list of user ids |
| `activePlayerId` | User id of the current active player |
| `playerUsernames` | Display names keyed by player id |

Clients should treat the latest server snapshot as authoritative and replace local board state with the included `state`.

## Response Examples

The examples below show the relevant envelope and payload fields. `GameStateDto` snapshots may contain additional card, landmark, player, and marketplace entries depending on the running game.

### Start Game

`/app/game.start` publishes `GAME_STARTED` followed by a `GAME_ACTION` event. Both messages are sent to `/topic/game/{gameId}`.

```json
{
  "type": "GAME_STARTED",
  "sender": "server",
  "content": "Game 42 has started",
  "payload": {
    "game": {
      "id": 42,
      "status": "IN_PROGRESS",
      "turnPhase": "ROLL_DICE"
    },
    "players": [],
    "playerCards": {},
    "playerLandmarks": {},
    "marketplace": {},
    "cardDefinitions": [],
    "landmarkDefinitions": [],
    "turnOrder": [10, 11],
    "activePlayerId": 10,
    "playerUsernames": {}
  },
  "timestamp": 1714000000000,
  "gameId": 42
}
```

The immediate `GAME_ACTION` uses the same state under `payload.state`:

```json
{
  "type": "GAME_ACTION",
  "sender": "server",
  "payload": {
    "event": "GAME_STARTED",
    "turnPhase": "ROLL_DICE",
    "activePlayerId": 10,
    "state": {
      "game": {
        "id": 42,
        "status": "IN_PROGRESS",
        "turnPhase": "ROLL_DICE"
      }
    }
  },
  "gameId": 42
}
```

### Purchase

A successful purchase publishes a `GAME_ACTION` to `/topic/game/{gameId}`:

```json
{
  "type": "GAME_ACTION",
  "sender": "server",
  "payload": {
    "event": "PURCHASE_COMPLETED",
    "turnPhase": "BUY_OR_BUILD",
    "activePlayerId": 10,
    "state": {
      "game": {
        "id": 42,
        "status": "IN_PROGRESS",
        "turnPhase": "BUY_OR_BUILD"
      }
    },
    "purchaseType": "ESTABLISHMENT",
    "cardType": "BAKERY"
  },
  "gameId": 42
}
```

For landmark builds, the payload uses `landmarkType` instead of `cardType`.

### Sync

`/app/game.sync` publishes a private `SYNC` response consumed through `/user/queue/game-sync`:

```json
{
  "type": "SYNC",
  "sender": "server",
  "content": "State sync for reconnecting player",
  "payload": {
    "targetUserId": 10,
    "targetSessionId": "abc123",
    "state": {
      "game": {
        "id": 42,
        "status": "IN_PROGRESS",
        "turnPhase": "ROLL_DICE"
      },
      "activePlayerId": 10,
      "turnOrder": [10, 11],
      "players": [],
      "playerCards": {},
      "playerLandmarks": {},
      "marketplace": {}
    }
  },
  "gameId": 42
}
```

### Errors

Purchase rejections are broadcast on `/topic/game/{gameId}` so every subscribed client can clear pending purchase UI consistently:

```json
{
  "type": "ERROR",
  "sender": "server",
  "payload": {
    "event": "PURCHASE_FAILED",
    "code": "DUPLICATE_PURPLE_ESTABLISHMENT",
    "message": "Player already owns purple establishment STADIUM",
    "purchaseType": "ESTABLISHMENT",
    "cardType": "STADIUM"
  },
  "gameId": 42
}
```

Handler exceptions are private user-queue errors on `/user/queue/errors`:

```json
{
  "code": "UNAUTHENTICATED",
  "message": "Authenticated principal not found",
  "timestamp": 1714000000000
}
```

For rejected landmark purchases, the game-topic error payload uses `landmarkType` instead of `cardType`.

## Turn Sequence

The server enforces the active-player check for turn actions. Client turn controls should follow this sequence:

1. Send `/app/game.rollDice`.
2. Send `/app/game.resolveEffects`.
3. Send `/app/game.advancePhase` when moving from income resolution to buy/build.
4. Send `/app/game.purchase` at most once during `BUY_OR_BUILD`, unless the player skips buying.
5. Send `/app/game.endTurn`.

Income resolution applies Machi Koro card effects in server order: red cards, blue cards, green cards, then purple cards. Clients should not locally apply income as the source of truth; they should render the `GAME_ACTION` snapshot returned after `/app/game.resolveEffects`.

## Two-Player Synchronization Flow

1. Player A and Player B connect to `/ws` or `/ws-sockjs` with `Authorization: Bearer <sessionToken>`.
2. Player A subscribes to `/queue/lobby-user{sessionId}` and sends `/app/lobby.create`.
3. Player A receives `LOBBY_CREATED` with `gameId` and `lobbyCode`.
4. Both players subscribe to `/topic/game/{gameId}` and `/user/queue/errors`.
5. Player B subscribes to `/queue/lobby-user{sessionId}` and sends `/app/lobby.join` with the lobby code.
6. Player B receives `LOBBY_ROSTER` privately. Both players receive `LOBBY_JOINED` on `/topic/game/{gameId}`.
7. Player A sends `/app/game.start`.
8. Both players receive `GAME_STARTED` and the initial `GAME_ACTION` snapshot on `/topic/game/{gameId}`.
9. During the game, the active player sends dice, effects, phase, purchase, and end-turn commands.
10. Both players consume the resulting `ROLL_DICE`, `GAME_ACTION`, or `GAME_END` messages from `/topic/game/{gameId}` and replace local state with the included `state` snapshot.

## Reconnect and Explicit Sync

A reconnecting player must establish a new STOMP connection with the same authenticated session token and resubscribe to:

```text
/topic/game/{gameId}
/user/queue/game-sync
/user/queue/errors
```

Then the client sends:

```text
SEND /app/game.sync
```

```json
{
  "gameId": 42
}
```

The server validates that the authenticated user belongs to the requested game and that the game is in progress. On success, it publishes a private `SYNC` message to the reconnecting session. Unauthorized or inactive sync requests are rejected without broadcasting state to other players.

`/app/chat.addUser` may also trigger a sync when the reconnecting user has an active in-progress game, but clients should still support explicit `/app/game.sync` because it is deterministic and does not depend on chat registration.
