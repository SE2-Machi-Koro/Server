# Server

## Getting Started

### Setup Environment Variables

1. Rename `.env.example` to `.env`
2. Configure database credentials:
   ```env
   DB_HOST=localhost
   DB_PORT=5432
   DB_NAME=machi_koro
   DB_USERNAME=your_username
   DB_PASSWORD=your_password
   SERVER_PORT=8080
   ```

### Start Docker Services

Execute the Docker Compose configuration:
```bash
docker compose up -d
```

### Configure PostgreSQL Database

1. Access pgAdmin: `http://localhost:5050/`
   - **Email**: admin@admin.com
   - **Password**: admin

2. Register a new server:
   - **General Tab**
     - Name: Machi Koro DB
   - **Connection Tab**
     - Host: postgres
     - Port: 5432
     - Database: `${DB_NAME}`
     - Username: `${DB_USERNAME}`
     - Password: `${DB_PASSWORD}`

### Build and Run

```bash
# Build the project
./gradlew build

# Run the server
./gradlew bootRun
```

The server will be available at: `http://localhost:8080`

---

## WebSocket Configuration

### Overview

The server implements **real-time bidirectional communication** between frontend and backend using the **STOMP over WebSocket** protocol. This architecture enables:

- Instant message delivery across all connected clients
- Automatic game state synchronization
- Real-time notifications and events
- SockJS fallback for browser compatibility

### Endpoint Details

| Parameter | Value |
|-----------|-------|
| **URL** | `ws://localhost:8080/ws` |
| **Protocol** | STOMP with SockJS |
| **Port** | 8080 |

### Message Broker Configuration

| Setting | Configuration |
|---------|---------------|
| **Broker Type** | Simple in-memory broker |
| **Topic Destinations** | `/topic/public`, `/topic/*`, `/topic/errors` |
| **Queue Destinations** | `/queue/*`, `/queue/errors` |
| **App Prefix** | `/app` |

---

## API Reference

### Available Endpoints

#### Send Chat Message
- **Endpoint**: `/app/chat.send`
- **Direction**: Client → Server
- **Response**: Broadcast to `/topic/public`
- **Description**: Send a message to all connected users

#### Add User (Join)
- **Endpoint**: `/app/chat.addUser`
- **Direction**: Client → Server
- **Response**: Broadcast to `/topic/public`
- **Description**: Register user connection and announce to chat

### Message Format

```json
{
  "type": "CHAT|JOIN|LEAVE|GAME_START|GAME_ACTION|GAME_END",
  "sender": "username",
  "content": "message content",
  "payload": null,
  "timestamp": 1711270800000
}
```

### Message Type Reference

| Type | Purpose | Trigger |
|------|---------|---------|
| `CHAT` | Standard chat message | User message in chat |
| `JOIN` | User connection event | User enters chat |
| `LEAVE` | User disconnection event | User closes connection |
| `GAME_START` | Game initialization | Game session starts |
| `GAME_ACTION` | Game-specific action | Player makes game move |
| `GAME_END` | Game conclusion | Game session ends |

Currently emitted by the server: `CHAT`, `JOIN`, `LEAVE`.

---

## Error Handling

### Current Behavior

- There is no dedicated backend error topic publisher (for example, `/topic/errors`) in the current implementation.
- Input validation and structured WebSocket error payloads are not implemented yet in message handlers.
- Connection failures are handled on the client via the STOMP `onError` callback.
- Server-side events are logged, and disconnect events publish a `LEAVE` message on `/topic/public`.

### Planned Error Response Format (Not Yet Implemented)

```json
{
  "code": "ERROR_CODE",
  "message": "Human-readable error description",
  "timestamp": 1711270800000
}
```

### Planned Error Code Reference (Not Yet Implemented)

| Code | HTTP Status | Description | Resolution |
|------|-------------|-------------|-----------|
| `INVALID_SENDER` | 400 | Sender name is empty | Provide a non-empty sender name |
| `INVALID_MESSAGE` | 400 | Message content is empty | Provide message content |
| `INVALID_USERNAME` | 400 | Username is empty | Provide a username to join |
| `SEND_MESSAGE_ERROR` | 500 | Message processing failed | Retry the operation |
| `ADD_USER_ERROR` | 500 | User registration failed | Retry joining the chat |
| `VALIDATION_ERROR` | 400 | Invalid message format | Check message structure |
| `INTERNAL_ERROR` | 500 | Unexpected server error | Contact support |

### Client-Side Error Handling

The frontend automatically:
- Handles connection failures in the STOMP `onError` callback
- Displays a red connection error message in the UI when connection fails
- Logs runtime errors in the browser console

### Server-Side Error Handling

- **Controller Logging**: Incoming chat and join events are logged in `WebSocketController`
- **Disconnect Handling**: `WebSocketEventListener` publishes `LEAVE` events and logs disconnects
- **Exception Classes**: `CustomWebSocketException` and `GlobalWebSocketExceptionHandler` are present as placeholders

### Example Error Response

```json
{
  "code": "INVALID_MESSAGE",
  "message": "Message content cannot be empty",
  "timestamp": 1711270800000
}
```

---

## Architecture

### Project Structure

```
src/main/kotlin/org/machikoro/server/
├── config/
│   ├── WebSocketConfig.kt          # STOMP broker configuration
│   └── SecurityConfig.kt            # Spring Security setup
├── controller/
│   └── WebSocketController.kt       # Message handler endpoints
├── dto/
│   └── ChatMessage.kt               # Message types and WebSocket message model
├── exception/
│   └── CustomWebSocketException.kt  # Placeholder for custom WebSocket exceptions
├── handler/
│   └── GlobalWebSocketExceptionHandler.kt  # Placeholder for centralized exception handling
├── listener/
│   └── WebSocketEventListener.kt    # Connection lifecycle events
└── ServerApplication.kt             # Application entry point
```

### Component Description

#### Configuration Layer
- **WebSocketConfig**: Configures STOMP message broker and registers `/ws` endpoint
- **SecurityConfig**: Sets up Spring Security for WebSocket access

#### Controller Layer
- **WebSocketController**: Handles message routing and validation

#### Exception Handling
- **CustomWebSocketException**: Placeholder for application-level WebSocket exceptions
- **GlobalWebSocketExceptionHandler**: Placeholder for centralized error management

#### Event Management
- **WebSocketEventListener**: Handles user connect/disconnect events

#### Data Models
- **WebSocketMessage**: Message structure for all communications (defined in `dto/ChatMessage.kt`)

---

## Security

### Current Implementation (Development)

⚠️ **Development Mode Configuration:**
- ✅ Unauthenticated WebSocket connections allowed
- ✅ CORS configured for all origins (`*`)
- ✅ CSRF protection disabled
- ✅ All endpoints publicly accessible

### Production Recommendations

**Before deploying to production, implement:**

1. **Authentication**
   - JWT-based authentication
   - Session token validation
   - User identity verification

2. **CORS Restrictions**
   - Whitelist specific frontend origins
   - Restrict allowed methods and headers

3. **CSRF Protection**
   - Enable CSRF tokens
   - Validate token on each request

4. **Rate Limiting**
   - Implement per-user message limits
   - Add connection throttling

5. **Input Sanitization**
   - Escape user input
   - Validate payload sizes
   - Prevent injection attacks

6. **SSL/TLS**
   - Use `wss://` protocol
   - Enable certificate validation

---

## Client Implementation

### JavaScript/SockJS Example

```javascript
// Initialize WebSocket connection
const socket = new SockJS('/ws');
const stompClient = Stomp.over(socket);

// Connect and subscribe
stompClient.connect({}, function() {
  // Subscribe to chat messages
  stompClient.subscribe('/topic/public', function(message) {
    console.log('Received:', JSON.parse(message.body));
  });
});

// Send chat message
function sendMessage(username, content) {
  const chatMessage = {
    sender: username,
    type: 'CHAT',
    content: content
  };
  stompClient.send('/app/chat.send', {}, JSON.stringify(chatMessage));
}

// Join chat
function joinChat(username) {
  const joinMessage = {
    sender: username,
    type: 'JOIN'
  };
  stompClient.send('/app/chat.addUser', {}, JSON.stringify(joinMessage));
}
```

---

## Testing

### Quality Gate

![Coverage Gate](https://img.shields.io/badge/coverage%20gate-%E2%89%A580%25%20per%20class-brightgreen)

- Unit and integration tests enforce a **minimum 80% line coverage per class** via JaCoCo.
- The build fails automatically if any production class drops below the threshold.

Run verification locally:

```bash
./gradlew check
```

Generate an HTML/XML coverage report:

```bash
./gradlew jacocoTestReport
```

Coverage report paths:

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

### Built-In Test Client

Access the web-based test client:

1. Start the server: `./gradlew bootRun`
2. Open browser: `http://localhost:8080`
3. Enter username and start chatting
4. Messages are broadcasted in real-time to all connected clients

### Testing Features

- ✅ Real-time message delivery
- ✅ User join/leave notifications
- ✅ Connection error indicator on failed WebSocket connect
- ✅ Connection status indicator

---

## Troubleshooting

### Common Issues and Solutions

| Issue | Cause | Solution |
|-------|-------|----------|
| WebSocket connection refused | Server not running | Verify server is running on port 8080 |
| Messages not received | Incorrect subscription | Check browser console for subscription errors |
| Connection drops frequently | Network issues | Verify network stability and firewall rules |
| Error messages appearing | Invalid input | Ensure sender and content are non-empty |
| Validation errors | Malformed message | Check message JSON structure |
| Database connection fails | Invalid credentials | Verify `.env` file configuration |
| Port 8080 already in use | Port conflict | Change `SERVER_PORT` in `.env` |

### Debug Mode

Enable detailed logging:

```bash
./gradlew bootRun --args='--logging.level.root=DEBUG'
```

Check logs for WebSocket connection details and error traces.

---

## Additional Resources

- [Spring Boot WebSocket Documentation](https://spring.io/guides/gs/messaging-stomp-websocket/)
- [STOMP Protocol Specification](https://stomp.github.io/)
- [SockJS Documentation](https://sockjs.org/)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)

---

**Last Updated**: March 2026
**Version**: 1.0.0
