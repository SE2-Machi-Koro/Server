# Server

## Get Docker up and running
- Rename the .env.example file into .env
- Fill out the .env file
- Execute the compose.yaml file
- Connect to pgAdmin: http://localhost:5050/
- Username for pgAdmin: admin@admin.com
- Password for pgAdmin: admin 
- Register Database:
  - General Tab:
    - Give it a name: e.g. Machi Koro DB 
  - Connection Tab:
    - Host name: postgres
    - Port: 5432
    - Maintenance Database: DB_NAME from .env file
    - Username: DB_USERNAME from .env file
    - PASSWORD: DB_PASSWORD from .env file

## WebSocket Configuration

### Overview
The server implements **real-time bidirectional communication** between frontend and backend using the **STOMP over WebSocket** protocol. This enables instant message delivery and game state synchronization across all connected clients.

### Architecture

#### Endpoint
- **URL**: `ws://localhost:8080/ws`
- **Protocol**: STOMP with SockJS fallback
- **Port**: 8080 (default)

#### Message Broker
- **Type**: Simple in-memory broker
- **Topic Destinations**: `/topic/public`, `/topic/*`
- **Queue Destinations**: `/queue/*`
- **Application Prefix**: `/app`

### Client-Server Communication

#### Available Message Mappings

| Endpoint | Direction | Purpose | Response Topic |
|----------|-----------|---------|-----------------|
| `/app/chat.send` | Client → Server | Send chat message | `/topic/public` |
| `/app/chat.addUser` | Client → Server | Register user join event | `/topic/public` |

#### Message Format

```json
{
  "type": "CHAT|JOIN|LEAVE|GAME_START|GAME_ACTION|GAME_END",
  "sender": "username",
  "content": "message content",
  "payload": null,
  "timestamp": 1711270800000
}
```

### Message Types

| Type | Description | Usage |
|------|-------------|-------|
| `CHAT` | Standard chat message | User-to-user communication |
| `JOIN` | User connection event | Broadcast when user joins |
| `LEAVE` | User disconnection event | Broadcast when user leaves |
| `GAME_START` | Game initialization | Start new game session |
| `GAME_ACTION` | Game-specific action | Players' game moves |
| `GAME_END` | Game conclusion | End game session |

### Client Implementation

#### JavaScript/SockJS Example

```javascript
// Connect to WebSocket
const socket = new SockJS('/ws');
const stompClient = Stomp.over(socket);

// Connect and subscribe
stompClient.connect({}, function() {
    stompClient.subscribe('/topic/public', function(message) {
        console.log('Received:', JSON.parse(message.body));
    });
});

// Send message
const chatMessage = {
    sender: 'username',
    type: 'CHAT',
    content: 'Hello!'
};
stompClient.send('/app/chat.send', {}, JSON.stringify(chatMessage));
```

### Testing

Access the built-in test client:
- **URL**: `http://localhost:8080`
- Enter username and start chatting
- Messages are broadcasted to all connected clients in real-time

### Security

- **Authentication**: Currently allows unauthenticated WebSocket connections (development mode)
- **CORS**: Configured to accept connections from any origin (`*`)
- **CSRF**: Disabled for WebSocket endpoints

⚠️ **Note**: For production deployment, implement proper authentication, CORS restrictions, and CSRF protection.

### Components

#### Configuration
- `config/WebSocketConfig.kt` - STOMP broker and endpoint configuration
- `config/SecurityConfig.kt` - Spring Security filters for WebSocket access

#### Controllers
- `controller/WebSocketController.kt` - Message mapping handlers

#### Event Listeners
- `listener/WebSocketEventListener.kt` - Connection/disconnection event handling

#### Data Transfer Objects
- `dto/WebSocketMessage.kt` - Message serialization model

### Troubleshooting

| Issue | Solution |
|-------|----------|
| WebSocket connection refused | Ensure server is running on port 8080 |
| Messages not received | Check browser console for errors, verify subscription topics |
| Connection drops | Verify network connectivity and firewall settings |
