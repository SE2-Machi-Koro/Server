# Machi Koro Server

[![Server CI](https://github.com/SE2-Machi-Koro/Server/actions/workflows/ci.yml/badge.svg)](https://github.com/SE2-Machi-Koro/Server/actions/workflows/ci.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=SE2-Machi-Koro_Server&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=SE2-Machi-Koro_Server)

> Robust real-time multiplayer backend for the Machi Koro board game, built with Kotlin, Spring Boot, and WebSockets.

This server manages real-time Machi Koro game sessions, player communication, game state, and persistent storage on
PostgreSQL. Designed for reliability, scalability, and developer productivity.

## Key Features

- **Real-Time Multiplayer:** Instant bidirectional communication using STOMP over WebSockets.
- **Game State Management:** Strict tracking of turn phases (Roll Dice, Resolve Effects, Buy/Build, End Turn) and game
  statuses.
- **Game Logic Engine:** Calculates earnings, applies card effects based on dice rolls, and detects win conditions (
  e.g., landmark completion).
- **Authoritative Turns:** Clients request roll, resolve, purchase, and end-turn actions; only the server advances legal phases.
- **In-Game Chat:** Built-in chat system for players in the lobby and during the game.
- **Accounts & Authentication:** REST registration, login, and logout with token-based session authentication, enforced
  for both REST calls and STOMP WebSocket sessions.
- **Leaderboard:** REST endpoint exposing player rankings across finished games.
- **Data Persistence:** Uses JetBrains Exposed ORM to safely store users, games, cards, and landmarks in PostgreSQL.
- **Quality Assured:** Comprehensive test suite with Testcontainers, JUnit5, and a strict ≥80% Jacoco coverage quality
  gate.

## Tech Stack

- **Language:** Kotlin 2.2.21 (JDK 21 toolchain)
- **Framework:** Spring Boot 4.0.3
- **Security:** Spring Security with token-based session authentication
- **Database:** PostgreSQL 18.0
- **Database Migrations:** Flyway - Handles database schema migrations automatically on startup, ensuring the database structure is always in sync with the codebase.
- **ORM:** JetBrains Exposed 1.0.0 (DSL only)
- **Real-Time Communication:** Spring WebSockets (STOMP / SockJS)
- **API Documentation:** Springdoc OpenAPI (Swagger UI) 3.0.2
- **AsyncAPI Documentation:** Springwolf 2.3.0 - Auto-generates AsyncAPI documentation for the WebSocket endpoints, allowing developers to explore and interact with message channels directly from the browser.
- **Testing:** JUnit 5, Mockito-Kotlin, Testcontainers
- **Containerization:** Docker & Docker Compose

## Architecture Overview

The project follows a standard multi-layer Spring Boot architecture:

```mermaid
flowchart TD
    Client((Client)) <-->|WebSocket STOMP| Controllers[WebSocket / REST Controllers]
    Controllers <-->|DTOs| Services[Business Logic Services]
    Services <-->|Domain Models| DAOs[Data Access Objects]
    DAOs <-->|Exposed DSL| Database[(PostgreSQL)]
    
    subgraph Spring Boot Backend
        Controllers
        Services
        DAOs
    end
```

- **Controllers (`controller/`):** Expose WebSocket and REST endpoints (e.g., `GameController`, `WebSocketController`).
- **Services (`service/`):** Contain the core game logic (`GamePhaseService`, `EarningsService`, `WinConditionService`).
- **Domain Models (`domain/`):** Pure Kotlin data classes representing the business logic and game state (`GameModel`,
  `PlayerModel`, Enums like `TurnPhase`).
- **Data Access (`dao/`):** DAOs interact directly with the database using Exposed DSL and map raw results to domain
  models.
- **DTOs (`dto/`):** Data Transfer Objects for client-server communication.
- **Configuration (`config/`):** Setup for WebSockets, Spring Security, and OpenAPI.
- **Authentication (`auth/`):** Token authentication filter for REST plus a STOMP channel interceptor and user
  principal handling for WebSocket sessions.
- **Exception Handling (`exception/`, `handler/`):** Domain exceptions with dedicated REST and WebSocket exception
  handlers.
- **Database Bootstrap (`database/`):** Exposed table definitions, datasource wiring, and reference-data seeding.
- **Listeners (`listener/`):** WebSocket connect/disconnect event handling.

## Data Layer Concepts

### Data Access Objects (DAOs)

DAOs are the only layer that directly interacts with the database.
Each DAO encapsulates all database operations for its domain (e.g., games, players, cards) and is used by the service
layer to retrieve and modify state.

Key responsibilities:

- Execute all queries inside a transaction
- Use JetBrains Exposed DSL to interact with the persistence layer
- Return domain models instead of raw rows, keeping persistence details isolated from the rest of the application

### Exposed DSL

This project uses JetBrains Exposed exclusively in DSL (Domain-Specific Language) mode — a type-safe, SQL-like query
builder that gives full, explicit control over every database operation.

Unlike an ORM's entity/object approach, the DSL does not hide what SQL runs behind the scenes. Every query is written
explicitly and maps directly to the SQL it produces:

```kotlin
// Select
Games.selectAll()
    .where { Games.status eq GameStatus.WAITING }
    .map { it.toModel() }

// Insert
Games.insertAndGetId {
    it[Games.lobbyCode] = "ABC123"
    it[Games.status] = GameStatus.WAITING
}.value

// Update
Games.update({ Games.id eq id }) {
    it[Games.turnPhase] = TurnPhase.ROLL_DICE
}

// Delete
Games.deleteWhere { Games.id eq id }
```

Table definitions are written as Kotlin objects and serve as the single source of truth for the schema:

```kotlin
object Games : IntIdTable("games") {
    val lobbyCode = varchar("lobby_code", 7).uniqueIndex()
    val status = enumerationByName("status", 20, GameStatus::class)
    val turnPhase = enumerationByName("turn_phase", 20, TurnPhase::class)
}
```

### ResultRow and toModel()

When the DSL executes a query, it returns `ResultRow` objects — essentially a raw map of column references to their
values for a single database row. These are internal to Exposed and must be converted into domain models before leaving
the DAO.

Each DAO defines a private `ResultRow.toModel()` extension function that performs this mapping:

```kotlin
private fun ResultRow.toModel() = GameModel(
    id = this[Games.id].value,
    status = this[Games.status],
    lobbyCode = this[Games.lobbyCode],
    turnPhase = this[Games.turnPhase]
)
```

Column values are accessed by referencing the table column directly (e.g., `this[Games.status]`), which is fully
type-safe — accessing a column that does not exist in the result or reading it as the wrong type is caught at compile
time.

This pattern keeps the mapping logic close to where it is used, and ensures that no Exposed types ever leak outside the
DAO layer. Services and controllers only ever see clean domain models.

### Domain Models

Domain models represent the data used in the application's core business logic.

They are pure Kotlin data classes that encapsulate business state independently of persistence frameworks and transport
layers. These models are used primarily by the service layer to implement game rules and manage the overall game state.

Key characteristics:

- Encapsulate pure business data independent of persistence and frameworks
- Used by the service layer to implement game logic
- Represent the current game state and rule-related state transitions
- Do not contain database logic or persistence concerns
- Can be used as a foundation for DTOs when appropriate, though DTOs should remain transport-focused

### Data Transfer Objects (DTOs)

DTOs are simple objects used exclusively to carry data between the server and clients. They define the shape of requests
and responses for REST and WebSocket communication, decoupling the API contract from internal domain models.

Key responsibilities:

- Represent the structure of incoming requests (e.g., a player joining a game) and outgoing responses (e.g., the current
  game state sent to all clients)
- Contain only the fields relevant to the client — no business logic, no persistence concerns
- Prevent internal domain models from leaking into the API layer, making it safe to evolve the two independently

For example, a `GameStateDto` sent over WebSocket may include only the data a client needs to render the UI, while the
internal `GameModel` may hold additional state used purely for server-side logic.

## Environment Configuration

### Database Initialization

- **Flyway** is the authoritative source for database schema creation and migration across environments. We use Flyway to version control our database setup, ensuring consistency from local development to production.
- Migrations live in `src/main/resources/db/migration/` (`V1__init_schema.sql` defines the initial schema and reference data; later versions evolve it).
- `machikoro.db.init.enabled` (default `true`) gates the startup runners that wire Exposed to the already-migrated datasource and seed reference data. It does **not** create or modify the schema — Flyway owns that. Tests that boot the Spring context without a database set it to `false`.

1. Copy the example environment file and adjust as needed:

```bash
   cp .env.example .env
```

2. Edit the following required variables in `.env`:

   | Variable                    | Description                                                                   |
   |-----------------------------|-------------------------------------------------------------------------------|
   | DB_HOST                     | Database hostname (`localhost` for local dev, `postgres` inside compose)     |
   | DB_USERNAME                 | PostgreSQL database username                                                  |
   | DB_PASSWORD                 | PostgreSQL database password                                                  |
   | DB_NAME                     | Database name                                                                 |
   | DB_PORT                     | Database port (default: 5432, local dev only)                                 |
   | SERVER_PORT                 | Port for backend server inside the container (default: 8080)                  |
   | PUBLIC_PORT                 | Host port the backend is published on in production (AAU group 6: `53210`)    |
   | WEBSOCKET_ALLOWED_ORIGINS   | Comma-separated list of allowed CORS origins for the WebSocket endpoint       |
   | PGADMIN_EMAIL               | Email for pgAdmin (local dev only — see `compose-dev.yaml`)                   |
   | PGADMIN_PASSWORD            | Password for pgAdmin (local dev only — see `compose-dev.yaml`)                |
   | DEBUG_ENABLED               | Enables the `/debug` endpoints and admin account seeding (default: `false`; keep off in production) |
   | ADMIN_PASSWORD              | Password for the seeded admin accounts (required when `DEBUG_ENABLED=true`)   |

Example `.env`:

```env
DB_HOST=localhost
DB_USERNAME=admin
DB_PASSWORD=password123
DB_NAME=machikoro
DB_PORT=5432
SERVER_PORT=8080
PUBLIC_PORT=53210
WEBSOCKET_ALLOWED_ORIGINS=http://localhost:8080,http://localhost:3000
PGADMIN_EMAIL=admin@admin.com
PGADMIN_PASSWORD=admin
DEBUG_ENABLED=false
ADMIN_PASSWORD=
```

## Local Build & Run

Prerequisites: JDK 21 (the Gradle toolchain enforces it) and Docker (used by the dev database, Testcontainers, and the
local image build).

Clone the repository and set up your environment:

```bash
git clone git@github.com:SE2-Machi-Koro/Server.git
cd Server
cp .env.example .env
# Edit .env as needed
```

Build the project:

```bash
./gradlew build
```

Gradle dependency verification is enabled via [gradle/verification-metadata.xml](gradle/verification-metadata.xml).
If you add or update dependencies, refresh both generated dependency files before committing:

```bash
./gradlew dependencies --write-locks
./gradlew --write-verification-metadata sha256 build
```

Review the updated verification metadata before committing it. Bootstrapping records the artifacts currently resolved by
your configured repositories, so it should be treated as generated security-sensitive state rather than an opaque cache.

Run the server locally:

```bash
./gradlew bootRun
```

Spring Boot's Docker Compose support automatically starts the dev database stack from
[compose-dev.yaml](compose-dev.yaml) (PostgreSQL, plus pgAdmin at `http://localhost:5050`), so Docker must be running.

The backend will be available at: `http://localhost:8080`

### Local Docker build

For an end-to-end local run that mirrors the production container, use the smoke-test compose
override that builds the backend image from source:

```bash
docker compose -f compose.yaml -f compose.smoke-test.yaml --env-file .env up -d --build
```

The backend is published on the host port from `PUBLIC_PORT` in your `.env` (the override
defaults to `58080` when unset) and the database on `SMOKE_DB_PORT` (default `55432`).
For a fully automated build–start–verify–teardown cycle, run
[scripts/backend-restart-recovery-smoke.sh](scripts/backend-restart-recovery-smoke.sh) instead.

This local Docker path keeps the source-based fallback in the `Dockerfile`, while
the GitHub publish workflow uses a faster CI-only path that builds the Spring Boot
jar once and reuses it for the multi-architecture image push.

## Testing

Run the full test suite (unit + integration):

```bash
./gradlew check
```

Generate a coverage report:

```bash
./gradlew jacocoTestReport
```

HTML report: `build/reports/jacoco/test/html/index.html`

Run SonarCloud analysis (requires `SONAR_TOKEN` in your shell):

```bash
SONAR_TOKEN=<your-token> \
./gradlew --no-daemon clean check jacocoTestReport sonar \
  --info --stacktrace
```

Sonar analysis settings are defined in `build.gradle.kts` under the Gradle `sonar { properties { ... } }` block, so local and CI use the same configuration path.

## API & WebSocket Documentation

For detailed API documentation, the server exposes both standard REST and asynchronous API documentation.

The gameplay loop is action-driven: `ROLL_DICE --rollDice--> RESOLVE_EFFECTS --resolveEffects--> BUY_OR_BUILD --endTurn--> ROLL_DICE`. Purchases are optional and limited to one during `BUY_OR_BUILD`. `/app/game.advancePhase` is a deprecated compatibility destination that is rejected and cannot skip required turn actions.

- **Swagger UI (REST):** Accessible at `http://localhost:8080/swagger-ui.html`. Powered by Springdoc OpenAPI, this interface provides interactive documentation for our standard REST endpoints.
- **Springwolf UI (AsyncAPI/WebSockets):** Accessible at `http://localhost:8080/springwolf/asyncapi-ui.html`. Powered by Springwolf, this provides interactive documentation and an event publisher for our STOMP over WebSocket channels, which are the backbone of our real-time game communications.
- **WebSocket Endpoints:** `ws://localhost:8080/ws` (native STOMP) and `http://localhost:8080/ws-sockjs` (SockJS fallback)
- **WebSocket Game Protocol:** Client subscriptions, send destinations, message envelopes, and reconnect synchronization are documented in [docs/websocket-game-protocol.md](docs/websocket-game-protocol.md).
- **Backend Restart Recovery:** The manual restart procedure and `/app/game.sync` verification checklist are documented in [docs/backend-restart-recovery.md](docs/backend-restart-recovery.md).

Run the backend restart recovery smoke test with:

```bash
./scripts/backend-restart-recovery-smoke.sh
```

---

For advanced usage, message formats, and integration details, refer to the dedicated documentation.

## Health Check

To verify the server is running:

```
GET http://localhost:8080/actuator/health
```

## Deployment

The server is deployed to the AAU shared infrastructure (`se2-demo.aau.at`, group 6) via
[doco-cd](https://github.com/kimdre/doco-cd), which reconciles the running stack from this
repository's `compose.yaml` on every push to `main`.

### Pipeline overview

```mermaid
flowchart LR
    Push(Push to main) --> Action[GitHub Actions CI/CD]
    Action --> Test[Build & Test]
    Action --> Docker[Build Multi-Arch Docker Image]
    Docker --> GHCR[(GHCR Image Registry)]
    
    GHCR --> DocoCD(doco-cd on AAU Server)
    DocoCD -->|Pulls Image| Production[Docker Compose Stack]
    Production --> Backend[Machi Koro Backend]
    Production --> DB[(PostgreSQL)]
```

1. A push to `main` triggers the [`Publish Docker image to GHCR`](.github/workflows/docker-publish.yml)
  workflow.
2. The workflow first runs a `build-jar` job on `ubuntu-latest`, sets up JDK 21 with
  Gradle dependency caching, and executes `./gradlew bootJar -x test` exactly once.
  The resulting application jar is uploaded as a short-lived workflow artifact.
3. The `build-and-push` job downloads that artifact and uses Docker Buildx to package
  and push the multi-architecture runtime image for `linux/amd64` and `linux/arm64`
  without recompiling the application per architecture. This avoids the slow Gradle
  build under QEMU emulation on `arm64`.
4. The published image is pushed to
   `ghcr.io/se2-machi-koro/server` with the tags:
   - `latest` (only on `main`)
   - `sha-<short-commit>` (every build, used for rollback)
   - `v*` (when a Git tag matching `v*` is pushed)
5. doco-cd on the AAU server detects the change, pulls the new image (`pull_policy: always`),
   and restarts the `backend` service defined in [compose.yaml](compose.yaml), when the
   course deployment config contains a stack entry for this repository.
6. The Postgres service runs alongside the backend on the internal compose network and is
   **not exposed to the host** — only the backend is published on `PUBLIC_PORT` (`53210`).

### Live endpoints

| Resource     | URL                                                  |
|--------------|------------------------------------------------------|
| Backend      | `http://se2-demo.aau.at:53210`                       |
| Health check | `http://se2-demo.aau.at:53210/actuator/health`       |
| WebSocket    | `ws://se2-demo.aau.at:53210/ws`                      |

### Server access

```bash
ssh grp-6@se2-demo.aau.at -p 53200
```

The doco-cd working copy of this repo lives at:

```
/var/lib/docker/volumes/doco-cd-setup_data/_data/github.com/SE2-Machi-Koro/Server/
```

This path is owned by Docker and may not be directly readable from the `grp-6` shell.
If doco-cd has not created a group 6 stack yet, deploy from the group home directory:

```bash
mkdir -p /home/grp-6/machi-koro-server-deploy
cp compose.yaml /home/grp-6/machi-koro-server-deploy/compose.yaml
cd /home/grp-6/machi-koro-server-deploy
```

The production `.env` must be placed next to `compose.yaml` in the active deployment
directory and locked down with `chmod 600`. Use [.env.example](.env.example) as the
template; the production values for `DB_USERNAME` / `DB_PASSWORD` are set by the team
and never committed.

Minimal production `.env` for the AAU group 6 server:

```env
DB_NAME=machikoro
DB_USERNAME=machikoro
DB_PASSWORD=<production-password>
PUBLIC_PORT=53210
WEBSOCKET_ALLOWED_ORIGINS=http://se2-demo.aau.at:53210,https://se2-demo.aau.at:53210
IMAGE_TAG=latest
```

Start or refresh the manual deployment:

```bash
docker compose pull
docker compose up -d
docker compose ps
```

This manual fallback does not auto-restart on every push to `main`. After a new image is
published to GHCR, refresh the running stack from `/home/grp-6/machi-koro-server-deploy`
with the same `docker compose pull && docker compose up -d` commands, or add a group 6
stack entry to the course doco-cd config so reconciliation is automatic.

The deployment is healthy when `docker compose ps` shows both `machikoro-db` and
`machikoro-server` as healthy and the external health check returns `status: UP`:

```bash
curl http://se2-demo.aau.at:53210/actuator/health
```

### Rollback

To roll back to a previous image, edit the production `.env` on the server and set
`IMAGE_TAG=sha-<short-commit>` (or any other tag published to GHCR), then trigger a
manual `docker compose up -d` or a doco-cd reconcile. The `compose.yaml` resolves the image as
`ghcr.io/se2-machi-koro/server:${IMAGE_TAG:-latest}`.

## Frontend dependencies (Subresource Integrity)

The static landing page at [`src/main/resources/static/index.html`](src/main/resources/static/index.html)
loads three pinned assets from the cdnjs CDN (Bootstrap 4.6.0, SockJS-client 1.1.4,
stomp.js 2.3.3). Each `<link>`/`<script>` tag includes a `sha512` Subresource Integrity
(SRI) hash plus `crossorigin="anonymous"` and `referrerpolicy="no-referrer"`, so the
browser refuses to execute any payload that does not match the pinned hash. This
satisfies SonarCloud rule *"Make sure not using resource integrity feature is safe here"*
(CWE / former-hotspot) and protects users if the CDN is ever compromised.

When bumping any of these CDN versions, regenerate the matching hash:

```bash
curl -sSL <new-cdn-url> | openssl dgst -sha512 -binary | openssl base64 -A
```

Replace the `integrity="sha512-..."` value on the same tag and verify in the browser
DevTools console that no *"Failed to find a valid digest in the 'integrity' attribute"*
error is logged.

---
*Last Updated: 10.06.2026*
