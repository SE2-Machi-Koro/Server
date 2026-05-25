# Backend Container Restart Recovery Verification

This document outlines the procedure to verify that an in-progress Machi Koro game fully survives a backend container restart without losing any game state. This proves the "Game state recovery upon failure/restart of the backend container" requirement for Sprint 2.

## Manual Test Procedure

1. **Start the backend locally via Docker Compose:**
   ```bash
   docker compose -f compose.yaml -f compose-dev.yaml up -d
   ```

2. **Connect two clients:**
   - Open two browser windows pointing to the frontend or connect via an API testing tool (like Postman or a WebSocket client).
   - Log in as `Player 1` in the first window and `Player 2` in the second.

3. **Establish a game:**
   - `Player 1` creates a lobby.
   - `Player 2` joins the lobby.
   - `Player 1` starts the game.

4. **Mutate game state (take turns):**
   - `Player 1` rolls the dice, purchases a card (e.g., `BAKERY`), and advances the turn.
   - `Player 2` rolls the dice, builds a landmark (if enough coins), and ends their turn.

5. **Simulate a Backend Restart:**
   - Leave both client sessions open.
   - Stop and start the backend container using Docker Compose:
     ```bash
     docker compose restart backend
     ```
     *(This restarts only the backend server, keeping the Postgres database container running and preserving state, exactly as it would happen in production if the server crashed).*

6. **Reconnect Clients:**
   - After the backend is back online, refresh both clients (or trigger a WS reconnect).
   - Subscribe each reconnecting client to `/topic/game/{gameId}`, `/user/queue/game-sync`, and `/user/queue/errors`.
   - The reconnect flow invokes `/app/game.sync` and retrieves the private `SYNC` response containing the `GameStateDto` snapshot.

7. **Verify Game State:**
   Ensure that the state is identical to pre-restart. The following fields must match exactly:
   - **Active Player & Turn Order**
   - **Round & Turn Index**
   - **Player Coins**
   - **Owned Cards** (e.g., `Player 1` still has the `BAKERY` purchased)
   - **Landmark Built Status**
   - **Marketplace Quantities**

## Evidence for Sprint 2

Automated coverage:

- `scripts/backend-restart-recovery-smoke.sh` builds and starts the backend with Docker Compose, creates two authenticated STOMP clients, starts a game, buys an establishment, builds a landmark, restarts the `backend` container, reconnects with the same session token, and re-issues `/app/game.sync`.
- `SpringContextRestartRecoveryIntegrationTest` creates an in-progress game in a Testcontainers-backed Postgres database, mutates persisted fields, forces a Spring application context restart, and then re-enters the `/app/game.sync` controller path.
- The recovered `SYNC` payload verifies the same game id, `IN_PROGRESS` status, `BUY_OR_BUILD` phase, dice roll, round number, player coins, owned cards, built landmark, marketplace quantities, active player, and turn order.
- The broader reviewer flow is documented in [backend-networking-demo-checklist.md](backend-networking-demo-checklist.md).

Last local verification:

```bash
./scripts/backend-restart-recovery-smoke.sh
./gradlew test --tests org.machikoro.server.service.SpringContextRestartRecoveryIntegrationTest
./gradlew test
```

All commands passed on 2026-05-18.

Manual demo evidence:

- During the Sprint 2 demo, capture the `/app/game.sync` payload before and after `docker compose restart backend`.
- The fields listed in "Verify Game State" above must match exactly, and the game must continue from the recovered turn.
