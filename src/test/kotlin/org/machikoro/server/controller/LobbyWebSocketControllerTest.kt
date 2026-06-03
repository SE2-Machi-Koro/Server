package org.machikoro.server.controller

import org.junit.jupiter.api.Test
import org.machikoro.server.auth.USER_PRINCIPAL_KEY
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.LobbyService
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.LobbyLeavingOutcome
import org.machikoro.server.dto.LobbyRosterDto
import org.machikoro.server.dto.LobbyRosterPlayerDto
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.exception.PlayerNotFoundException
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate

class LobbyWebSocketControllerTest {

    private val lobbyService = mock<LobbyService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val controller = LobbyWebSocketController(lobbyService, messagingTemplate)

    // Helper: accessor with authenticated principal and a session ID
    private fun authenticatedAccessor(userId: Int, username: String, sessionId: String = "test-session"): SimpMessageHeaderAccessor =
        SimpMessageHeaderAccessor.create().apply {
            user = UserPrincipal(userId = userId, username = username)
            this.sessionId = sessionId
        }

    private fun game() = GameModel(
        id = 1,
        status = GameStatus.WAITING,
        hostUserId = 10,
        lobbyCode = "ABC1234",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        roundNumber = 1,
        hasPurchasedThisTurn = false
    )

    private fun player() = PlayerModel(
        id = 5,
        gameId = 1,
        userId = 20,
        turnOrder = 1,
        coins = 3,
        lastSeenAt = null
    )

    @Test
    fun `createLobby sends LOBBY_CREATED only to the creator's session queue`() {
        whenever(lobbyService.createLobby(10)).thenReturn(game())

        val accessor = authenticatedAccessor(userId = 10, username = "Player1", sessionId = "sess-42")

        controller.createLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", content = "create lobby"),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        // Must go to creator's session queue, not a global topic
        assertEquals("/queue/lobby-user${"sess-42"}", destCaptor.firstValue)

        val msg = msgCaptor.firstValue
        assertEquals(MessageType.LOBBY_CREATED, msg.type)
        assertEquals("SERVER", msg.sender)
        assertEquals("Lobby created", msg.content)
        assertEquals(1, msg.gameId)

        val payload = msg.payload as? Map<*, *> ?: throw AssertionError("Payload is not a Map")
        assertEquals("ABC1234", payload["lobbyCode"])
        assertEquals(10, payload["hostUserId"])
        assertEquals("WAITING", payload["status"])

        verify(lobbyService).createLobby(10)
    }

    @Test
    fun `createLobby does nothing when sessionId is missing`() {
        // Accessor has a principal but no sessionId — edge case for connections mid-handshake
        val accessor = SimpMessageHeaderAccessor.create().apply {
            user = UserPrincipal(userId = 10, username = "Player1")
            // sessionId intentionally not set
        }
        whenever(lobbyService.createLobby(10)).thenReturn(game())

        controller.createLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "Player1", content = "create lobby"),
            accessor,
        )

        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `createLobby throws when no authenticated principal is present`() {
        val accessor = SimpMessageHeaderAccessor.create().apply { sessionId = "sess-1" }

        val ex = assertThrows<CustomWebSocketException> {
            controller.createLobby(
                WebSocketMessage(type = MessageType.JOIN, sender = "ghost", content = "create lobby"),
                accessor,
            )
        }

        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(lobbyService, never()).createLobby(any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `createLobby uses principal from session attributes when header user is missing`() {
        whenever(lobbyService.createLobby(10)).thenReturn(game())

        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionId = "sess-99"
            sessionAttributes = mutableMapOf(
                USER_PRINCIPAL_KEY to UserPrincipal(userId = 10, username = "Player1")
            )
        }

        controller.createLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", content = "create lobby"),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        assertEquals("/queue/lobby-usersess-99", destCaptor.firstValue)
        assertEquals(MessageType.LOBBY_CREATED, msgCaptor.firstValue.type)
        assertEquals("ABC1234", (msgCaptor.firstValue.payload as? Map<*, *>)?.get("lobbyCode"))

        verify(lobbyService).createLobby(10)
    }

    @Test
    fun `joinLobby broadcasts LOBBY_JOINED to the lobby's game topic`() {
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 1, userId = 10, username = "Player1", gameId = 1, turnOrder = 0, coins = 3),
            LobbyRosterPlayerDto(playerId = 5, userId = 20, username = "Player2", gameId = 1, turnOrder = 1, coins = 3),
        )
        whenever(lobbyService.joinLobby("ABC1234", 20)).thenReturn(player())
        whenever(lobbyService.getLobbyRoster(1)).thenReturn(roster)

        val accessor = authenticatedAccessor(userId = 20, username = "Player2", sessionId = "sess-77")

        controller.joinLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", payload = mapOf("lobbyCode" to "ABC1234")),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        // 3 sends: LOBBY_ROSTER to joiner's queue, LOBBY_JOINED to topic, LOBBY_ROSTER to topic
        verify(messagingTemplate, times(3)).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        // First send: LOBBY_ROSTER only to the joiner's session queue
        assertEquals("/queue/lobby-usersess-77", destCaptor.allValues[0])
        val rosterMsg = msgCaptor.allValues[0]
        assertEquals(MessageType.LOBBY_ROSTER, rosterMsg.type)
        assertEquals("SERVER", rosterMsg.sender)
        assertEquals(1, rosterMsg.gameId)
        assertEquals(LobbyRosterDto(players = roster), rosterMsg.payload)

        // Second send: LOBBY_JOINED to the lobby's game topic
        assertEquals("/topic/game/1", destCaptor.allValues[1])
        val joinMsg = msgCaptor.allValues[1]
        assertEquals(MessageType.LOBBY_JOINED, joinMsg.type)
        assertEquals("SERVER", joinMsg.sender)
        assertEquals("Player joined lobby", joinMsg.content)
        assertEquals(1, joinMsg.gameId)
        val joinPayload = joinMsg.payload as? Map<*, *> ?: throw AssertionError("Payload is not a Map")
        assertEquals(5, joinPayload["playerId"])
        assertEquals(20, joinPayload["userId"])
        assertEquals(1, joinPayload["gameId"])
        assertEquals(3, joinPayload["coins"])

        // Third send: updated LOBBY_ROSTER broadcast so all lobby members stay in sync
        assertEquals("/topic/game/1", destCaptor.allValues[2])
        val broadcastRoster = msgCaptor.allValues[2]
        assertEquals(MessageType.LOBBY_ROSTER, broadcastRoster.type)
        assertEquals(LobbyRosterDto(players = roster), broadcastRoster.payload)

        verify(lobbyService).joinLobby("ABC1234", 20)
        // getLobbyRoster called twice: once for private send, once for topic broadcast
        verify(lobbyService, times(2)).getLobbyRoster(1)
    }

    @Test
    fun `joinLobby throws when no authenticated principal is present`() {
        val accessor = SimpMessageHeaderAccessor.create().apply { sessionId = "sess-1" }

        val ex = assertThrows<CustomWebSocketException> {
            controller.joinLobby(
                WebSocketMessage(type = MessageType.JOIN, sender = "ghost", payload = mapOf("lobbyCode" to "ABC1234")),
                accessor,
            )
        }

        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(lobbyService, never()).joinLobby(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `joinLobby throws when payload is not a Map`() {
        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val ex = assertThrows<CustomWebSocketException> {
            controller.joinLobby(
                // String payload instead of a Map — triggers INVALID_PAYLOAD guard
                WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", payload = "not-a-map"),
                accessor,
            )
        }

        assertEquals("INVALID_PAYLOAD", ex.errorCode)
        verify(lobbyService, never()).joinLobby(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `joinLobby throws when lobby code is missing`() {
        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val ex = assertThrows<CustomWebSocketException> {
            controller.joinLobby(
                WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", payload = emptyMap<String, Any>()),
                accessor,
            )
        }

        assertEquals("INVALID_LOBBY_CODE", ex.errorCode)
        verify(lobbyService, never()).joinLobby(any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `joinLobby sends ERROR to requester's session queue when lobby code is invalid`() {
        whenever(lobbyService.joinLobby("INVALID", 20))
            .thenThrow(GameNotFoundException("Lobby with code INVALID not found"))

        val accessor = authenticatedAccessor(userId = 20, username = "Player2", sessionId = "sess-err")

        controller.joinLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", payload = mapOf("lobbyCode" to "INVALID")),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        // Error must go only to the requester, not a global topic
        assertEquals("/queue/lobby-usersess-err", destCaptor.firstValue)

        val msg = msgCaptor.firstValue
        assertEquals(MessageType.ERROR, msg.type)
        assertEquals("SERVER", msg.sender)
        assertEquals("Lobby code is invalid", msg.content)

        val payload = msg.payload as? Map<*, *> ?: throw AssertionError("Payload is not a Map")
        assertEquals("INVALID_LOBBY_CODE", payload["errorCode"])
    }

    // === leaveLobby() ===

    @Test
    fun `leaveLobby throws when no authenticated principal is present`() {
        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionId = "sess-1"
        }

        val ex = assertThrows<CustomWebSocketException> {
            controller.leaveLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "ghost",
                    payload = mapOf("gameId" to 1)
                ),
                accessor,
            )
        }

        assertEquals("UNAUTHENTICATED", ex.errorCode)

        verify(lobbyService, never())
            .leaveLobby(any(), any())

        verify(messagingTemplate, never())
            .convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `leaveLobby throws when payload is not a Map`() {
        val accessor = authenticatedAccessor(
            userId = 10,
            username = "Player1"
        )

        val ex = assertThrows<CustomWebSocketException> {
            controller.leaveLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "Player1",
                    payload = "not-a-map"
                ),
                accessor,
            )
        }

        assertEquals("INVALID_PAYLOAD", ex.errorCode)

        verify(lobbyService, never())
            .leaveLobby(any(), any())
    }

    @Test
    fun `leaveLobby throws when gameId is missing from payload`() {
        val accessor = authenticatedAccessor(
            userId = 10,
            username = "Player1"
        )

        val ex = assertThrows<CustomWebSocketException> {
            controller.leaveLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "Player1",
                    payload = emptyMap<String, Any>()
                ),
                accessor,
            )
        }

        assertEquals("MISSING_GAME_ID", ex.errorCode)

        verify(lobbyService, never())
            .leaveLobby(any(), any())
    }

    @Test
    fun `leaveLobby throws when player is not in lobby`() {
        whenever(lobbyService.leaveLobby(1, 10))
            .thenThrow(
                PlayerNotFoundException(
                    "Player 10 not found in game 1"
                )
            )

        val accessor = authenticatedAccessor(
            userId = 10,
            username = "Player1"
        )

        assertThrows<PlayerNotFoundException> {
            controller.leaveLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "Player1",
                    payload = mapOf("gameId" to 1)
                ),
                accessor,
            )
        }

        verify(lobbyService)
            .leaveLobby(1, 10)

        verify(messagingTemplate, never())
            .convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `leaveLobby broadcasts LOBBY_LEFT then LOBBY_ROSTER to game topic when lobby persists`() {
        val updatedRoster = listOf(
            LobbyRosterPlayerDto(playerId = 2, userId = 20, username = "Player2", gameId = 1, turnOrder = 1, coins = 3),
        )
        whenever(lobbyService.leaveLobby(1, 10))
            .thenReturn(LobbyLeavingOutcome.LobbyRemains(10))
        whenever(lobbyService.getLobbyRoster(1)).thenReturn(updatedRoster)

        val accessor = authenticatedAccessor(
            userId = 10,
            username = "Player1",
            sessionId = "sess-leave"
        )

        controller.leaveLobby(
            WebSocketMessage(
                type = MessageType.JOIN,
                sender = "Player1",
                payload = mapOf("gameId" to 1)
            ),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        // 2 sends: LOBBY_LEFT, then LOBBY_ROSTER so remaining members see updated ready states
        verify(messagingTemplate, times(2)).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        assertEquals("/topic/game/1", destCaptor.allValues[0])
        val leftMsg = msgCaptor.allValues[0]
        assertEquals(MessageType.LOBBY_LEFT, leftMsg.type)
        assertEquals("SERVER", leftMsg.sender)
        assertEquals(1, leftMsg.gameId)
        val payload = leftMsg.payload as? Map<*, *> ?: throw AssertionError("Payload is not a Map")
        assertEquals(10, payload["userId"])

        // Follow-up roster broadcast keeps remaining players in sync
        assertEquals("/topic/game/1", destCaptor.allValues[1])
        val rosterMsg = msgCaptor.allValues[1]
        assertEquals(MessageType.LOBBY_ROSTER, rosterMsg.type)
        assertEquals(LobbyRosterDto(players = updatedRoster), rosterMsg.payload)
    }

    @Test
    fun `leaveLobby broadcasts HOST_LEFT when lobby is deleted`() {
        whenever(lobbyService.leaveLobby(1, 10))
            .thenReturn(
                LobbyLeavingOutcome.LobbyDeleted(1)
            )

        val accessor = authenticatedAccessor(
            userId = 10,
            username = "Player1"
        )

        controller.leaveLobby(
            WebSocketMessage(
                type = MessageType.JOIN,
                sender = "Player1",
                payload = mapOf("gameId" to 1)
            ),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()

        verify(messagingTemplate)
            .convertAndSend(
                destCaptor.capture(),
                msgCaptor.capture()
            )

        assertEquals(
            "/topic/game/1",
            destCaptor.firstValue
        )

        val msg = msgCaptor.firstValue
        assertEquals(MessageType.HOST_LEFT, msg.type)
        assertEquals("SERVER", msg.sender)
        assertEquals(1, msg.gameId)

        val payload = msg.payload as? Map<*, *>
            ?: throw AssertionError("Payload is not a Map")

        assertEquals(10, payload["userId"])
    }
    // === toggleReady() ===

    @Test
    fun `toggleReady throws when no authenticated principal is present`() {
        val accessor = SimpMessageHeaderAccessor.create().apply { sessionId = "sess-1" }

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.JOIN, sender = "ghost", payload = mapOf("gameId" to 1, "isReady" to true)),
                accessor,
            )
        }

        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(lobbyService, never()).setReadyState(any(), any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `toggleReady throws when payload is not a Map`() {
        val accessor = authenticatedAccessor(userId = 10, username = "Player1")

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.JOIN, sender = "Player1", payload = "not-a-map"),
                accessor,
            )
        }

        assertEquals("INVALID_PAYLOAD", ex.errorCode)
        verify(lobbyService, never()).setReadyState(any(), any(), any())
    }

    @Test
    fun `toggleReady throws when gameId is missing from payload`() {
        val accessor = authenticatedAccessor(userId = 10, username = "Player1")

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.JOIN, sender = "Player1", payload = mapOf("isReady" to true)),
                accessor,
            )
        }

        assertEquals("MISSING_GAME_ID", ex.errorCode)
        verify(lobbyService, never()).setReadyState(any(), any(), any())
    }

    @Test
    fun `toggleReady throws when isReady is missing from payload`() {
        val accessor = authenticatedAccessor(userId = 10, username = "Player1")

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.JOIN, sender = "Player1", payload = mapOf("gameId" to 1)),
                accessor,
            )
        }

        assertEquals("MISSING_IS_READY", ex.errorCode)
        verify(lobbyService, never()).setReadyState(any(), any(), any())
    }

    @Test
    fun `toggleReady broadcasts LOBBY_ROSTER with updated ready states to game topic`() {
        val gameId = 1
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 1, userId = 10, username = "Player1", gameId = gameId, turnOrder = 0, coins = 3, isReady = true),
            LobbyRosterPlayerDto(playerId = 2, userId = 20, username = "Player2", gameId = gameId, turnOrder = 1, coins = 3, isReady = false),
        )
        whenever(lobbyService.setReadyState(gameId, 10, true)).thenReturn(roster)

        val accessor = authenticatedAccessor(userId = 10, username = "Player1", sessionId = "sess-ready")

        controller.toggleReady(
            WebSocketMessage(type = MessageType.JOIN, sender = "Player1", payload = mapOf("gameId" to gameId, "isReady" to true)),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        // Broadcast goes to the shared game topic, not a private queue
        assertEquals("/topic/game/$gameId", destCaptor.firstValue)

        val msg = msgCaptor.firstValue
        assertEquals(MessageType.LOBBY_ROSTER, msg.type)
        assertEquals("SERVER", msg.sender)
        assertEquals(gameId, msg.gameId)
        assertEquals(LobbyRosterDto(players = roster), msg.payload)
    }

    @Test
    fun `toggleReady broadcasts unready state when isReady is false`() {
        val gameId = 1
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 1, userId = 10, username = "Player1", gameId = gameId, turnOrder = 0, coins = 3, isReady = false),
        )
        whenever(lobbyService.setReadyState(gameId, 10, false)).thenReturn(roster)

        val accessor = authenticatedAccessor(userId = 10, username = "Player1")

        controller.toggleReady(
            WebSocketMessage(type = MessageType.JOIN, sender = "Player1", payload = mapOf("gameId" to gameId, "isReady" to false)),
            accessor,
        )

        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(any<String>(), msgCaptor.capture())

        val payload = msgCaptor.firstValue.payload as LobbyRosterDto
        assertEquals(false, payload.players[0].isReady)
    }

    @Test
    fun `joinLobby skips private LOBBY_ROSTER when sessionId is null but still broadcasts to topic`() {
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 5, userId = 20, username = "Player2", gameId = 1, turnOrder = 1, coins = 3),
        )
        whenever(lobbyService.joinLobby("ABC1234", 20)).thenReturn(player())
        whenever(lobbyService.getLobbyRoster(1)).thenReturn(roster)

        // No sessionId — private LOBBY_ROSTER skipped, but LOBBY_JOINED + topic LOBBY_ROSTER still sent
        val accessor = SimpMessageHeaderAccessor.create().apply {
            user = UserPrincipal(userId = 20, username = "Player2")
        }

        controller.joinLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", payload = mapOf("lobbyCode" to "ABC1234")),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        // 2 sends to topic: LOBBY_JOINED + LOBBY_ROSTER (private LOBBY_ROSTER skipped)
        verify(messagingTemplate, times(2)).convertAndSend(destCaptor.capture(), msgCaptor.capture())
        assertEquals("/topic/game/1", destCaptor.allValues[0])
        assertEquals(MessageType.LOBBY_JOINED, msgCaptor.allValues[0].type)
        assertEquals("/topic/game/1", destCaptor.allValues[1])
        assertEquals(MessageType.LOBBY_ROSTER, msgCaptor.allValues[1].type)
        // getLobbyRoster called once for the topic broadcast (not for the skipped private send)
        verify(lobbyService, times(1)).getLobbyRoster(1)
    }
}
