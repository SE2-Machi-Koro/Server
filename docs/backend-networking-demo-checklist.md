# Backend Networking Demo Checklist

Use this checklist to validate the backend networking, recovery, health, and deployment paths during review or demo. It assumes the server is running locally on port `8080` or on the AAU group 6 deployment port `53210`.

## References

| Area | Reference |
| --- | --- |
| STOMP protocol | [websocket-game-protocol.md](websocket-game-protocol.md) |
| Restart recovery | [backend-restart-recovery.md](backend-restart-recovery.md) |
| Deployment | [README.md#deployment](../README.md#deployment) |
| Local health | `http://localhost:8080/actuator/health` |
| AAU health | `http://se2-demo.aau.at:53210/actuator/health` |
| Local WebSocket | `ws://localhost:8080/ws` |
| AAU WebSocket | `ws://se2-demo.aau.at:53210/ws` |

## Pre-Demo Setup

1. Start the backend and database.

   ```bash
   docker compose -f compose.yaml -f compose-dev.yaml up -d
   ```

2. Verify health.

   ```bash
   curl http://localhost:8080/actuator/health
   ```

   Expected result: JSON response with `status` set to `UP`.

3. Open REST and AsyncAPI documentation.

   ```text
   http://localhost:8080/swagger-ui.html
   http://localhost:8080/springwolf/asyncapi-ui.html
   ```

## Two-Player Sync

1. Log in two users and keep both session tokens.
2. Connect both clients to `/ws` with `Authorization: Bearer <sessionToken>`.
3. Subscribe both clients to `/user/queue/errors`.
4. Player A subscribes to `/queue/lobby-user{sessionId}` and sends `/app/lobby.create`.
5. Player A records `gameId` and `lobbyCode` from `LOBBY_CREATED`.
6. Both players subscribe to `/topic/game/{gameId}`.
7. Player B subscribes to `/queue/lobby-user{sessionId}` and sends `/app/lobby.join` with `payload.lobbyCode`.
8. Verify Player B receives `LOBBY_ROSTER` privately and both players receive `LOBBY_JOINED` on `/topic/game/{gameId}`.
9. Player A sends `/app/game.start`.
10. Verify both players receive `GAME_STARTED` and `GAME_ACTION` on `/topic/game/{gameId}`.
11. Run one turn: `/app/game.rollDice`, `/app/game.resolveEffects`, `/app/game.advancePhase`, optional `/app/game.purchase`, then `/app/game.endTurn`.
12. Verify both clients receive the same final `state.activePlayerId`, `state.turnOrder`, `state.players`, `state.playerCards`, `state.playerLandmarks`, and `state.marketplace`.

## Reconnect Recovery

1. Keep the game in `IN_PROGRESS`.
2. Disconnect one player without ending the game.
3. Reconnect with the same session token.
4. Subscribe to `/topic/game/{gameId}`, `/user/queue/game-sync`, and `/user/queue/errors`.
5. Send `/app/game.sync` with the current `gameId`.
6. Verify the reconnecting client receives `SYNC` privately on `/user/queue/game-sync`.
7. Compare the `SYNC.payload.state` with the last broadcast snapshot.

The following fields must match: `game.id`, `game.status`, `game.turnPhase`, `activePlayerId`, `turnOrder`, player coins, owned cards, built landmarks, and marketplace quantities.

## Backend Restart Recovery

1. Keep a two-player game in `IN_PROGRESS` after at least one state mutation.
2. Capture the latest broadcast snapshot from `/topic/game/{gameId}`.
3. Restart only the backend container.

   ```bash
   docker compose restart backend
   ```

4. Reconnect both clients with their existing session tokens.
5. Subscribe again to `/topic/game/{gameId}`, `/user/queue/game-sync`, and `/user/queue/errors`.
6. Send `/app/game.sync`.
7. Verify the recovered `SYNC.payload.state` matches the pre-restart snapshot for turn, coin, card, landmark, and marketplace state.
8. Continue the game by sending the next valid turn action and verify `/topic/game/{gameId}` broadcasts resume.

Automated smoke coverage:

```bash
./scripts/backend-restart-recovery-smoke.sh
```

## Error Routing

1. Send an invalid lobby join request with a non-existent lobby code.
2. Verify the error is delivered only to `/queue/lobby-user{sessionId}`.
3. Send a WebSocket command that fails validation or authorization.
4. Verify the private error response is delivered on `/user/queue/errors`.
5. Trigger a purchase rejection, for example a duplicate purple establishment.
6. Verify a game-topic `ERROR` with `payload.event = "PURCHASE_FAILED"` is broadcast on `/topic/game/{gameId}`.

## Deployment Verification

1. Confirm the GHCR image publication and SSH deployment workflow exists at `.github/workflows/docker-publish.yml`.
2. Confirm `compose.yaml` uses `ghcr.io/se2-machi-koro/server:${IMAGE_TAG:-latest}` and publishes only the backend via `PUBLIC_PORT`.
3. On the AAU server, verify `~/machi-koro-server-deploy` contains `compose.yaml` and a production `.env`.
4. Refresh the stack when needed.

   ```bash
   docker compose pull backend
   docker compose up -d --no-deps backend
   docker compose ps
   ```

5. Verify both containers are healthy and the public endpoint returns `UP`.

   ```bash
   curl http://se2-demo.aau.at:53210/actuator/health
   ```

6. Verify clients use `ws://se2-demo.aau.at:53210/ws` and game-state subscriptions use `/topic/game/{gameId}`, not `/topic/public`.
