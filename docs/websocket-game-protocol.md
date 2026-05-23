# WebSocket Game Synchronization Protocol

This document defines the client-facing STOMP contract for real-time Machi Koro lobby updates, game action broadcasts, and reconnect synchronization.

## Connection

Clients connect with STOMP over one of the registered WebSocket endpoints:

| Transport | Endpoint | Typical client |
| --- | --- | --- |
| Native WebSocket | `/ws` | Android and WebSocket-capable clients |
| SockJS | `/ws-sockjs` | Browser clients that need SockJS fallback transports |

The application destination prefix is `/app`. Broker subscriptions use `/topic` for shared broadcasts and `/queue` for session-scoped replies.

Every STOMP `CONNECT` frame must include an authenticated session token:

```text
Authorization: Bearer <sessionToken>
```

The server resolves the user identity from this token and ignores client-supplied sender or user fields for authorization-sensitive actions.

## Subscriptions

Clients should subscribe before sending lobby or game commands so they do not miss immediate broadcasts.

| Subscription | Scope | Purpose |
| --- | --- | --- |
| `/topic/game/{gameId}` | All players in one lobby or game | Lobby membership changes, game start, dice, phase, purchase, effects, game end, and broadcast errors |
| `/queue/lobby-user{sessionId}` | Single WebSocket session | Lobby creation result, lobby roster after join, and lobby-specific request errors |
| `/queue/game-sync-user{sessionId}` | Single WebSocket session | Full `SYNC` snapshot for reconnect and explicit state refresh |
| `/topic/public` | Global chat only | Chat and connection announcements from `/app/chat.*` |

`{sessionId}` is the STOMP session id assigned by Spring. Browser clients usually receive it from the STOMP session object after connection. The server currently sends private lobby and sync replies to the resolved broker destinations `/queue/lobby-user{sessionId}` and `/queue/game-sync-user{sessionId}`.

Game clients must not use `/topic/public` for game state. All game-state broadcasts are scoped to `/topic/game/{gameId}` or a private queue.

## Send Destinations

| Client sends to | Payload | Server publishes |
| --- | --- | --- |
| `/app/lobby.create` | `WebSocketMessage` | `LOBBY_CREATED` to `/queue/lobby-user{sessionId}` |
| `/app/lobby.join` | `WebSocketMessage` with `payload.lobbyCode` | `LOBBY_ROSTER` to `/queue/lobby-user{sessionId}` and `LOBBY_JOINED` to `/topic/game/{gameId}` |
| `/app/lobby.leave` | `WebSocketMessage` with `payload.gameId` | `LOBBY_LEFT` or `HOST_LEFT` to `/topic/game/{gameId}` |
| `/app/game.start` | `StartGameRequest` | `GAME_STARTED` and a `GAME_ACTION` snapshot to `/topic/game/{gameId}` |
| `/app/game.rollDice` | `RollDiceRequest` | `ROLL_DICE` and a `GAME_ACTION` snapshot to `/topic/game/{gameId}` |
| `/app/game.resolveEffects` | `ResolveEffectsRequest` | `GAME_ACTION` income/effects result to `/topic/game/{gameId}` |
| `/app/game.advancePhase` | `AdvancePhaseRequest` | `GAME_ACTION` phase snapshot to `/topic/game/{gameId}` |
| `/app/game.purchase` | `PurchaseRequest` | `GAME_ACTION` purchase snapshot to `/topic/game/{gameId}` |
| `/app/game.endTurn` | `EndTurnRequest` | `GAME_ACTION` next-turn snapshot or `GAME_END` to `/topic/game/{gameId}` |
| `/app/game.sync` | `SyncGameRequest` | `SYNC` to `/queue/game-sync-user{sessionId}` |
| `/app/chat.send` | `WebSocketMessage` | Chat message to `/topic/public` |
| `/app/chat.addUser` | `WebSocketMessage` | Join message to `/topic/public`; may also trigger `SYNC` for an active in-progress game |

## Message Envelope

All WebSocket responses use `WebSocketMessage`:

```json
{
  "type": "GAME_ACTION",
  "sender": "server",
  "content": "Optional human-readable text",
  "payload": {},
  "timestamp": 1714000000000,
  "gameId": 1
}
```

Core game message types:

| Type | Meaning |
| --- | --- |
| `GAME_STARTED` | The host started the lobby. The payload is the full `GameStateDto`. |
| `GAME_ACTION` | A state-changing game action completed. The payload includes `event`, `turnPhase`, `activePlayerId`, and `state`. |
| `ROLL_DICE` | Dice were rolled. The payload includes the dice values, total, completion flag, and state snapshot. |
| `SYNC` | Private reconnect snapshot. The payload includes `targetUserId`, `targetSessionId`, and `state`. |
| `GAME_END` | The game has ended. The payload includes `winnerId`, `roundsPlayed`, and final `state`. |
| `ERROR` | The command failed. The payload includes an event or error code and a message when available. |

Lobby-specific message types include `LOBBY_CREATED`, `LOBBY_JOINED`, `LOBBY_ROSTER`, `LOBBY_LEFT`, and `HOST_LEFT`.

## Two-Player Synchronization Flow

1. Player A and Player B connect to `/ws` or `/ws-sockjs` with `Authorization: Bearer <sessionToken>`.
2. Player A subscribes to `/queue/lobby-user{sessionId}` and sends `/app/lobby.create`.
3. Player A receives `LOBBY_CREATED` with `gameId` and `lobbyCode`.
4. Both players subscribe to `/topic/game/{gameId}`.
5. Player B subscribes to `/queue/lobby-user{sessionId}` and sends `/app/lobby.join` with the lobby code.
6. Player B receives `LOBBY_ROSTER` privately. Both players receive `LOBBY_JOINED` on `/topic/game/{gameId}`.
7. Player A sends `/app/game.start`.
8. Both players receive `GAME_STARTED` and the initial `GAME_ACTION` snapshot on `/topic/game/{gameId}`.
9. During the game, the active player sends dice, effects, phase, purchase, and end-turn commands. Both players consume the resulting `ROLL_DICE`, `GAME_ACTION`, or `GAME_END` messages from `/topic/game/{gameId}` and replace their local state with the included `state` snapshot.

Clients should treat the server snapshot as authoritative. Local UI state may optimistically display pending actions, but it must reconcile to the latest broadcast payload.

## Reconnect and Explicit Sync

A reconnecting player must establish a new STOMP connection with the same authenticated session token and resubscribe to:

```text
/topic/game/{gameId}
/queue/game-sync-user{newSessionId}
```

Then the client sends:

```json
SEND /app/game.sync

{
  "gameId": 1
}
```

The server validates that the authenticated user belongs to the requested game and that the game is in progress. On success, it publishes:

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
        "id": 1,
        "status": "IN_PROGRESS",
        "turnPhase": "ROLL_DICE"
      },
      "activePlayerId": 10,
      "turnOrder": [10, 11],
      "players": [],
      "marketplace": []
    }
  },
  "gameId": 1
}
```

If `gameId` is omitted, the server attempts to find the authenticated user's active in-progress game. Unauthorized or inactive sync requests are rejected without broadcasting state to other players.
