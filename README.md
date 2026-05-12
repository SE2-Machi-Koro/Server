# Machi Koro Server

> Robust real-time multiplayer backend for the Machi Koro board game, built with Kotlin, Spring Boot, and WebSockets.

This server manages real-time Machi Koro game sessions, player communication, game state, and persistent storage on
PostgreSQL. Designed for reliability, scalability, and developer productivity.

## Key Features

- **Real-Time Multiplayer:** Instant bidirectional communication using STOMP over WebSockets.
- **Game State Management:** Strict tracking of turn phases (Roll Dice, Resolve Effects, Buy/Build, End Turn) and game
  statuses.
- **Game Logic Engine:** Calculates earnings, applies card effects based on dice rolls, and detects win conditions (
  e.g., landmark completion).
- **In-Game Chat:** Built-in chat system for players in the lobby and during the game.
- **Data Persistence:** Uses JetBrains Exposed ORM to safely store users, games, cards, and landmarks in PostgreSQL.
- **Quality Assured:** Comprehensive test suite with Testcontainers, JUnit5, and a strict ≥80% Jacoco coverage quality
  gate.

## Tech Stack

- **Language:** Kotlin 2.2.21
- **Framework:** Spring Boot 4.0.3
- **Database:** PostgreSQL 18.0
- **ORM:** JetBrains Exposed 1.0.0 (DSL only)
- **Real-Time Communication:** Spring WebSockets (STOMP / SockJS)
- **API Documentation:** Springdoc OpenAPI (Swagger UI) 3.0.2
- **Testing:** JUnit 5, Mockito-Kotlin, Testcontainers
- **Containerization:** Docker & Docker Compose

## Architecture Overview

The project follows a standard multi-layer Spring Boot architecture:

- **Controllers (`controller/`):** Expose WebSocket and REST endpoints (e.g., `GameController`, `WebSocketController`).
- **Services (`service/`):** Contain the core game logic (`GamePhaseService`, `EarningsService`, `WinConditionService`).
- **Domain Models (`domain/`):** Pure Kotlin data classes representing the business logic and game state (`GameModel`,
  `PlayerModel`, Enums like `TurnPhase`).
- **Data Access (`dao/`):** DAOs interact directly with the database using Exposed DSL and map raw results to domain
  models.
- **DTOs (`dto/`):** Data Transfer Objects for client-server communication.
- **Configuration (`config/`):** Setup for WebSockets, Spring Security, and OpenAPI.

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

1. Copy the example environment file and adjust as needed:

```bash
   cp .env.example .env
```

2. Edit the following required variables in `.env`:

   | Variable                    | Description                                                                  |
   |-----------------------------|------------------------------------------------------------------------------|
   | DB_USERNAME                 | PostgreSQL database username                                                 |
   | DB_PASSWORD                 | PostgreSQL database password                                                 |
   | DB_NAME                     | Database name                                                                |
   | DB_PORT                     | Database port (default: 5432, local dev only)                                |
   | SERVER_PORT                 | Port for backend server inside the container (default: 8080)                 |
   | PUBLIC_PORT                 | Host port the backend is published on in production (AAU group 6: `53210`)   |
   | WEBSOCKET_ALLOWED_ORIGINS   | Comma-separated list of allowed CORS origins for the WebSocket endpoint      |
   | PGADMIN_EMAIL               | Email for pgAdmin (local dev only — see `compose-dev.yaml`)                  |
   | PGADMIN_PASSWORD            | Password for pgAdmin (local dev only — see `compose-dev.yaml`)               |

Example `.env`:

```env
DB_USERNAME=admin
DB_PASSWORD=password123
DB_NAME=machikoro
DB_PORT=5432
SERVER_PORT=8080
PUBLIC_PORT=53210
WEBSOCKET_ALLOWED_ORIGINS=http://localhost:8080,http://localhost:3000
PGADMIN_EMAIL=admin@admin.com
PGADMIN_PASSWORD=admin
```

## Local Build & Run

Clone the repository and set up your environment:

```bash
git clone <repo-url>
cd SE2-SERVER
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

The backend will be available at: `http://localhost:8080`

### Local Docker build

For an end-to-end local run that mirrors the production container, use the compose
override that builds the backend image from source:

```bash
docker compose -f compose.yaml -f compose.local-test.yaml --env-file .env.test up -d --build
```

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
SONAR_PROJECT_KEY="$(grep '^sonar.projectKey=' sonar-project.properties | cut -d= -f2-)"
SONAR_ORGANIZATION="$(grep '^sonar.organization=' sonar-project.properties | cut -d= -f2-)"
SONAR_HOST_URL="$(grep '^sonar.host.url=' sonar-project.properties | cut -d= -f2-)"

SONAR_TOKEN=<your-token> \
./gradlew --no-daemon clean check jacocoTestReport sonar \
  -Dsonar.projectKey="$SONAR_PROJECT_KEY" \
  -Dsonar.organization="$SONAR_ORGANIZATION" \
  -Dsonar.host.url="$SONAR_HOST_URL"
```

The CI workflow resolves the same values from `sonar-project.properties` to keep local and CI Sonar settings aligned.

## API & WebSocket Documentation

For detailed REST and WebSocket API documentation, see the `docs/` directory or access Swagger UI at `/swagger-ui.html`
after starting the server.

Key endpoints:

- API: `http://localhost:8080`
- WebSocket: `ws://localhost:8080/ws`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

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
