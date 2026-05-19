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
import org.machikoro.server.dto.LobbyRosterDto
import org.machikoro.server.dto.LobbyRosterPlayerDto
import org.machikoro.server.exception.GameNotFoundException
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
        // Expect two sends: LOBBY_ROSTER to the joiner's queue, then LOBBY_JOINED to the topic
        verify(messagingTemplate, times(2)).convertAndSend(destCaptor.capture(), msgCaptor.capture())

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

        verify(lobbyService).joinLobby("ABC1234", 20)
        verify(lobbyService).getLobbyRoster(1)
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

    @Test
    fun `joinLobby skips LOBBY_ROSTER when sessionId is null`() {
        whenever(lobbyService.joinLobby("ABC1234", 20)).thenReturn(player())

        // Accessor with no sessionId — LOBBY_JOINED still broadcasts, LOBBY_ROSTER is skipped
        val accessor = SimpMessageHeaderAccessor.create().apply {
            user = UserPrincipal(userId = 20, username = "Player2")
        }

        controller.joinLobby(
            WebSocketMessage(type = MessageType.JOIN, sender = "ignored-by-server", payload = mapOf("lobbyCode" to "ABC1234")),
            accessor,
        )

        val destCaptor = argumentCaptor<String>()
        val msgCaptor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(destCaptor.capture(), msgCaptor.capture())
        assertEquals("/topic/game/1", destCaptor.firstValue)
        assertEquals(MessageType.LOBBY_JOINED, msgCaptor.firstValue.type)
        verify(lobbyService, never()).getLobbyRoster(any())
    }
}
