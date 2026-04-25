# Machi Koro Server

> A robust, real-time multiplayer backend for the Machi Koro board game, built with Kotlin, Spring Boot, and WebSockets.

The Machi Koro Server provides a complete backend infrastructure for hosting real-time Machi Koro game sessions. It handles game state management, real-time player communication, dice rolls, turn phases, and win condition evaluations, all backed by a PostgreSQL database for persistent state.

## Key Features

- **Real-Time Multiplayer:** Instant bidirectional communication using STOMP over WebSockets.
- **Game State Management:** Strict tracking of turn phases (Roll Dice, Resolve Effects, Buy/Build, End Turn) and game statuses.
- **Game Logic Engine:** Calculates earnings, applies card effects based on dice rolls, and detects win conditions (e.g., landmark completion).
- **In-Game Chat:** Built-in chat system for players in the lobby and during the game.
- **Data Persistence:** Uses JetBrains Exposed ORM to safely store users, games, cards, and landmarks in PostgreSQL.
- **Quality Assured:** Comprehensive test suite with Testcontainers, JUnit5, and a strict ≥80% Jacoco coverage quality gate.

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
- **Domain Models (`domain/`):** Pure Kotlin data classes representing the business logic and game state (`GameModel`, `PlayerModel`, Enums like `TurnPhase`).
- **Data Access (`database/`):** Defines database tables and entities using Exposed ORM (`GameEntity`, `CardEntity`).
- **DTOs (`dto/`):** Data Transfer Objects for client-server communication.
- **Configuration (`config/`):** Setup for WebSockets, Spring Security, and OpenAPI.

## Prerequisites & Installation

### Prerequisites
- Docker & Docker Compose

### Start the Application

The entire application (Database, pgAdmin, and the Spring Boot Backend) is containerized and can be started with a single command. Environment variables are managed automatically by Docker Compose.

```bash
# Start all services in the background
docker compose up -d
```

### Accessing the Services

- **Backend API & WebSockets:** `http://localhost:8080`
- **pgAdmin (Database Management):** `http://localhost:5050`
  - **Email**: `admin@admin.com` (default)
  - **Password**: `admin` (default)
  - **Database Connection**: Use host `postgres`, port `5432`, user `myuser`, database `mydatabase` (default environment configuration).

## Usage

Once the server is running via Docker, you can connect via a WebSocket client or use the built-in testing features.

**API Documentation (Swagger UI):**
Available at `http://localhost:8080/swagger-ui.html` (via Springdoc).

**Health Endpoint:**
You can check the server status via Spring Boot Actuator at `http://localhost:8080/actuator/health`.

## API Endpoints & WebSockets

### WebSocket Connection
- **Endpoint:** `ws://localhost:8080/ws` (Native) or `http://localhost:8080/ws-sockjs` (SockJS fallback)
- **Broker Topics:**
  - `/topic/public`: Global broadcast channel (Chat, User Joins, Game events)

### Key Message Mappings
- **`SEND` `/app/chat.send`**: Send a chat message.
- **`SEND` `/app/chat.addUser`**: Register a user connection.
- **`SEND` `/app/game.advancePhase`**: Advances the game state to the next phase.
- **`SEND` `/app/game.endTurn`**: Ends a player's turn and evaluates win conditions.

### Message Format Example
```json
{
  "type": "GAME_ACTION",
  "sender": "server",
  "payload": {
    "turnPhase": "BUY_OR_BUILD"
  }
}
```