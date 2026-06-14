package org.machikoro.server.controller

import org.junit.jupiter.api.Test
import org.machikoro.server.auth.USER_PRINCIPAL_KEY
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketErrorDto
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.WebSocketConnectionTracker
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
    private val connectionTracker = mock<WebSocketConnectionTracker>()
    private val controller = LobbyWebSocketController(lobbyService, messagingTemplate, connectionTracker)

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
        hasPurchasedThisTurn = false,
        rerolledThisTurn = false
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
    fun `createLobby sends created joined and roster messages to creator session queue`() {
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 1, userId = 10, username = "Player1", gameId = 1, turnOrder = 0, coins = 3),
        )
        whenever(lobbyService.createLobby(10)).thenReturn(game())
        whenever(lobbyService.getLobbyRoster(1)).thenReturn(roster)

        val accessor = authenticatedAccessor(userId = 10, username = "Player1", sessionId = "sess-42")

        controller.createLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", content = "create lobby"),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate, times(3)).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        destCaptor.allValues.forEach { destination ->
            // Must go to creator's session queue, not a global topic
            assertEquals("/queue/lobby-usersess-42", destination)
        }

        val createdMsg = msgCaptor.allValues[0]
        assertEquals(MessageType.LOBBY_CREATED, createdMsg.type)
        assertEquals("SERVER", createdMsg.sender)
        assertEquals("Lobby created", createdMsg.content)
        assertEquals(1, createdMsg.gameId)

        val payload = createdMsg.payload as? Map<*, *> ?: throw AssertionError("Payload is not a Map")
        assertEquals("ABC1234", payload["lobbyCode"])
        assertEquals(10, payload["hostUserId"])
        assertEquals("WAITING", payload["status"])

        val joinedMsg = msgCaptor.allValues[1]
        assertEquals(MessageType.LOBBY_JOINED, joinedMsg.type)
        assertEquals("SERVER", joinedMsg.sender)
        assertEquals("You joined the lobby", joinedMsg.content)
        assertEquals(1, joinedMsg.gameId)
        val joinedPayload = joinedMsg.payload as? Map<*, *> ?: throw AssertionError("Payload is not a Map")
        assertEquals(1, joinedPayload["playerId"])
        assertEquals(10, joinedPayload["userId"])
        assertEquals("Player1", joinedPayload["username"])
        assertEquals(1, joinedPayload["gameId"])
        assertEquals(3, joinedPayload["coins"])

        val rosterMsg = msgCaptor.allValues[2]
        assertEquals(MessageType.LOBBY_ROSTER, rosterMsg.type)
        assertEquals("SERVER", rosterMsg.sender)
        assertEquals("Lobby roster", rosterMsg.content)
        assertEquals(1, rosterMsg.gameId)
        assertEquals(LobbyRosterDto(players = roster), rosterMsg.payload)

        verify(lobbyService).createLobby(10)
        verify(lobbyService).getLobbyRoster(1)
        verify(connectionTracker).register("sess-42", 10, 1)
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
        verify(connectionTracker, never()).register(any(), any(), any())
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
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `createLobby uses principal from session attributes when header user is missing`() {
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 1, userId = 10, username = "Player1", gameId = 1, turnOrder = 0, coins = 3),
        )
        whenever(lobbyService.createLobby(10)).thenReturn(game())
        whenever(lobbyService.getLobbyRoster(1)).thenReturn(roster)

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
        verify(messagingTemplate, times(3)).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        assertEquals("/queue/lobby-usersess-99", destCaptor.firstValue)
        assertEquals(MessageType.LOBBY_CREATED, msgCaptor.firstValue.type)
        assertEquals("ABC1234", (msgCaptor.firstValue.payload as? Map<*, *>)?.get("lobbyCode"))
        assertEquals(MessageType.LOBBY_JOINED, msgCaptor.allValues[1].type)
        assertEquals(MessageType.LOBBY_ROSTER, msgCaptor.allValues[2].type)

        verify(lobbyService).createLobby(10)
        verify(lobbyService).getLobbyRoster(1)
        verify(connectionTracker).register("sess-99", 10, 1)
    }

    @Test
    fun `joinLobby sends LOBBY_JOINED and LOBBY_ROSTER to joiner queue then broadcasts LOBBY_ROSTER to topic`() {
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
        // Three sends: LOBBY_JOINED to joiner queue, LOBBY_ROSTER to joiner queue, LOBBY_ROSTER to topic
        verify(messagingTemplate, times(3)).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        // First send: LOBBY_JOINED to joiner's session queue — navigation trigger
        assertEquals("/queue/lobby-usersess-77", destCaptor.allValues[0])
        val joinMsg = msgCaptor.allValues[0]
        assertEquals(MessageType.LOBBY_JOINED, joinMsg.type)
        assertEquals("SERVER", joinMsg.sender)
        assertEquals("You joined the lobby", joinMsg.content)
        assertEquals(1, joinMsg.gameId)
        val joinPayload = joinMsg.payload as? Map<*, *> ?: throw AssertionError("Payload is not a Map")
        assertEquals(5, joinPayload["playerId"])
        assertEquals(20, joinPayload["userId"])
        assertEquals(1, joinPayload["gameId"])
        assertEquals(3, joinPayload["coins"])

        // Second send: LOBBY_ROSTER to joiner's session queue — existing players before topic subscription
        assertEquals("/queue/lobby-usersess-77", destCaptor.allValues[1])
        val rosterMsg = msgCaptor.allValues[1]
        assertEquals(MessageType.LOBBY_ROSTER, rosterMsg.type)
        assertEquals("SERVER", rosterMsg.sender)
        assertEquals(1, rosterMsg.gameId)
        assertEquals(LobbyRosterDto(players = roster), rosterMsg.payload)

        // Third send: LOBBY_ROSTER to topic — existing members get full accurate roster with isReady = false
        assertEquals("/topic/game/1", destCaptor.allValues[2])
        val topicMsg = msgCaptor.allValues[2]
        assertEquals(MessageType.LOBBY_ROSTER, topicMsg.type)
        assertEquals("SERVER", topicMsg.sender)
        assertEquals(1, topicMsg.gameId)
        assertEquals(LobbyRosterDto(players = roster), topicMsg.payload)

        verify(lobbyService).joinLobby("ABC1234", 20)
        verify(lobbyService).getLobbyRoster(1)
        verify(connectionTracker).register("sess-77", 20, 1)
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

        val payload = msg.payload as? WebSocketErrorDto ?: throw AssertionError("Payload is not a WebSocketErrorDto")
        assertEquals("INVALID_LOBBY_CODE", payload.code)
        assertEquals("Lobby code is invalid", payload.message)
        assertEquals("LOBBY_JOIN_FAILED", payload.context["event"])
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
    fun `leaveLobby broadcasts LOBBY_LEFT to game topic when lobby persists`() {
        whenever(lobbyService.leaveLobby(1, 10))
            .thenReturn(
                LobbyLeavingOutcome.LobbyRemains( 10)
            )

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
        assertEquals(MessageType.LOBBY_LEFT, msg.type)
        assertEquals("SERVER", msg.sender)
        assertEquals(1, msg.gameId)

        val payload = msg.payload as? Map<*, *>
            ?: throw AssertionError("Payload is not a Map")

        assertEquals(10, payload["userId"])
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
    fun `toggleReady broadcasts updated LOBBY_ROSTER to game topic`() {
        val gameId = 1
        val userId = 20
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 5, userId = userId, username = "Player2", gameId = gameId, turnOrder = 1, coins = 3, isReady = true),
        )
        whenever(lobbyService.getLobbyRoster(gameId)).thenReturn(roster)

        val accessor = authenticatedAccessor(userId = userId, username = "Player2")

        controller.toggleReady(
            WebSocketMessage(type = MessageType.LOBBY_ROSTER, sender = "android-client",
                payload = mapOf("gameId" to gameId, "isReady" to true)),
            accessor,
        )

        verify(lobbyService).toggleReady(gameId, userId, true)

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), msgCaptor.capture())

        assertEquals("/topic/game/$gameId", destCaptor.firstValue)
        val msg = msgCaptor.firstValue
        assertEquals(MessageType.LOBBY_ROSTER, msg.type)
        assertEquals("SERVER", msg.sender)
        assertEquals(gameId, msg.gameId)
        assertEquals(LobbyRosterDto(players = roster), msg.payload)
    }

    @Test
    fun `toggleReady throws when no authenticated principal is present`() {
        val accessor = SimpMessageHeaderAccessor.create().apply { sessionId = "sess-1" }

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.LOBBY_ROSTER, sender = "ghost",
                    payload = mapOf("gameId" to 1, "isReady" to true)),
                accessor,
            )
        }

        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(lobbyService, never()).toggleReady(any(), any(), any())
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `toggleReady throws when payload is not a Map`() {
        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.LOBBY_ROSTER, sender = "Player2",
                    payload = "not-a-map"),
                accessor,
            )
        }

        assertEquals("INVALID_PAYLOAD", ex.errorCode)
        verify(lobbyService, never()).toggleReady(any(), any(), any())
    }

    @Test
    fun `toggleReady throws when gameId is missing from payload`() {
        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.LOBBY_ROSTER, sender = "Player2",
                    payload = mapOf("isReady" to true)),
                accessor,
            )
        }

        assertEquals("MISSING_GAME_ID", ex.errorCode)
        verify(lobbyService, never()).toggleReady(any(), any(), any())
    }

    @Test
    fun `toggleReady throws when isReady is missing from payload`() {
        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val ex = assertThrows<CustomWebSocketException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.LOBBY_ROSTER, sender = "Player2",
                    payload = mapOf("gameId" to 1)),
                accessor,
            )
        }

        assertEquals("MISSING_IS_READY", ex.errorCode)
        verify(lobbyService, never()).toggleReady(any(), any(), any())
    }

    @Test
    fun `toggleReady propagates GameNotFoundException from service`() {
        whenever(lobbyService.toggleReady(99, 20, true))
            .thenThrow(GameNotFoundException("Game 99 not found"))

        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        assertThrows<GameNotFoundException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.LOBBY_ROSTER, sender = "Player2",
                    payload = mapOf("gameId" to 99, "isReady" to true)),
                accessor,
            )
        }

        verify(lobbyService).toggleReady(99, 20, true)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `toggleReady propagates PlayerNotFoundException from service`() {
        whenever(lobbyService.toggleReady(1, 20, true))
            .thenThrow(PlayerNotFoundException("Player 20 not found in game 1"))

        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        assertThrows<PlayerNotFoundException> {
            controller.toggleReady(
                WebSocketMessage(type = MessageType.LOBBY_ROSTER, sender = "Player2",
                    payload = mapOf("gameId" to 1, "isReady" to true)),
                accessor,
            )
        }

        verify(lobbyService).toggleReady(1, 20, true)
        verify(messagingTemplate, never()).convertAndSend(any<String>(), any<WebSocketMessage>())
    }

    @Test
    fun `joinLobby skips personal-queue messages when sessionId is null but still broadcasts LOBBY_ROSTER to topic`() {
        val roster = listOf(
            LobbyRosterPlayerDto(playerId = 5, userId = 20, username = "Player2", gameId = 1, turnOrder = 1, coins = 3),
        )
        whenever(lobbyService.joinLobby("ABC1234", 20)).thenReturn(player())
        whenever(lobbyService.getLobbyRoster(1)).thenReturn(roster)

        // Accessor with no sessionId — personal-queue messages are skipped, topic broadcast still fires
        val accessor = SimpMessageHeaderAccessor.create().apply {
            user = UserPrincipal(userId = 20, username = "Player2")
        }

        controller.joinLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", payload = mapOf("lobbyCode" to "ABC1234")),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        // Only the topic broadcast fires — no session queue sends
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), msgCaptor.capture())
        assertEquals("/topic/game/1", destCaptor.firstValue)
        assertEquals(MessageType.LOBBY_ROSTER, msgCaptor.firstValue.type)
        verify(lobbyService).getLobbyRoster(1)
    }
}
