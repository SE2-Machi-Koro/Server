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
- **ORM:** JetBrains Exposed 1.0.0
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
- **Data Access (`database/`):** Defines database tables and entities using Exposed ORM (`GameEntity`, `CardEntity`).
- **DTOs (`dto/`):** Data Transfer Objects for client-server communication.
- **Configuration (`config/`):** Setup for WebSockets, Spring Security, and OpenAPI.

## Data Layer Concepts

### Data Access Objects (DAOs)

DAOs are the only layer that directly interacts with the database.
Each DAO encapsulates all database operations for its domain (e.g., games, players, cards) and is used by the service
layer to retrieve and modify state.

Key responsibilities:

- Execute all queries inside a transaction
- Use JetBrains Exposed (either DSL or Entity API) to interact with the persistence layer
- Return domain models instead of raw entities or rows, keeping persistence details isolated from the rest of the
  application

### Exposed DSL vs. Entity API

This project uses JetBrains Exposed, which offers two complementary styles for database access:

- **DSL (Domain-Specific Language):** A type-safe, SQL-like query builder. Queries return raw `ResultRow` objects that
  must be manually mapped to domain models. Best suited for complex queries, batch operations, or cases where
  fine-grained control over SQL is needed.

- **Entity API:** An object-relational mapper (ORM) style where each database row is represented as a Kotlin object (an
  `Entity`). Properties map directly to table columns, and relationships between tables can be navigated naturally.
  Entities are converted to domain models via a `toModel()` function before being returned outside the data layer.

### Entities

Exposed entities are object-oriented wrappers around a single database row. They provide direct access to column values
via delegated properties and handle the persistence mechanics internally. Entities are strictly internal to the database
layer — they are always converted into domain models before being passed to services or controllers.

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

   | Variable         | Description                     | 
   |------------------|---------------------------------|
   | DB_USERNAME      | PostgreSQL database username    |
   | DB_PASSWORD      | PostgreSQL database password    |
   | DB_NAME          | Database name                   |
   | DB_PORT          | Database port (default: 5432)   |
   | PGADMIN_EMAIL    | Email for pgAdmin (optional)    |
   | PGADMIN_PASSWORD | Password for pgAdmin (optional) |
   | SERVER_PORT      | Port for backend server         |

Example `.env`:

```env
DB_USERNAME=admin
DB_PASSWORD=password123
DB_NAME=machikoro
DB_PORT=5432
PGADMIN_EMAIL=admin@admin.com
PGADMIN_PASSWORD=admin
SERVER_PORT=8080
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

Run the server locally:

```bash
./gradlew bootRun
```

The backend will be available at: `http://localhost:8080`

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