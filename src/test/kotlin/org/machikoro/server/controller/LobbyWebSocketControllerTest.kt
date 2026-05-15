package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
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
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor

class LobbyWebSocketControllerTest {

    private val lobbyService = mock<LobbyService>()
    private val controller = LobbyWebSocketController(lobbyService)

    /** Helper: accessor with an authenticated principal attached. */
    private fun authenticatedAccessor(userId: Int, username: String): SimpMessageHeaderAccessor =
        SimpMessageHeaderAccessor.create().apply {
            user = UserPrincipal(userId = userId, username = username)
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
    fun `createLobby creates lobby for authenticated user and returns LOBBY_CREATED message`() {
        whenever(lobbyService.createLobby(10)).thenReturn(game())

        val accessor = authenticatedAccessor(userId = 10, username = "Player1")

        val result = controller.createLobby(
            WebSocketMessage(
                type = MessageType.JOIN,
                sender = "ignored-by-server",
                content = "create lobby"
            ),
            accessor,
        )

        val payload = result.payload as? Map<String, Any>
            ?: throw AssertionError("Payload is not a Map")

        assertEquals(MessageType.LOBBY_CREATED, result.type)
        assertEquals("SERVER", result.sender)
        assertEquals("Lobby created", result.content)
        assertEquals(1, result.gameId)
        assertEquals("ABC1234", payload["lobbyCode"])
        assertEquals(10, payload["hostUserId"])
        assertEquals("WAITING", payload["status"])

        // Must call lobbyService with the principal's userId, not message.sender
        verify(lobbyService).createLobby(10)
    }

    @Test
    fun `createLobby throws when no authenticated principal is present`() {
        // Accessor with no UserPrincipal — simulates a bypassed STOMP auth
        val accessor = SimpMessageHeaderAccessor.create()

        val ex = assertThrows<CustomWebSocketException> {
            controller.createLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "ghost",
                    content = "create lobby",
                ),
                accessor,
            )
        }

        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(lobbyService, never()).createLobby(any())
    }

    @Test
    fun `createLobby uses principal from session attributes when header user is missing`() {
        whenever(lobbyService.createLobby(10)).thenReturn(game())

        val accessor = SimpMessageHeaderAccessor.create().apply {
            sessionAttributes = mutableMapOf(
                USER_PRINCIPAL_KEY to UserPrincipal(userId = 10, username = "Player1")
            )
        }

        val result = controller.createLobby(
            WebSocketMessage(
                type = MessageType.JOIN,
                sender = "ignored-by-server",
                content = "create lobby"
            ),
            accessor,
        )

        val payload = result.payload as? Map<String, Any>
            ?: throw AssertionError("Payload is not a Map")

        assertEquals(MessageType.LOBBY_CREATED, result.type)
        assertEquals("ABC1234", payload["lobbyCode"])

        verify(lobbyService).createLobby(10)
    }

    @Test
    fun `joinLobby joins lobby for authenticated user and returns LOBBY_JOINED message`() {
        whenever(lobbyService.joinLobby("ABC1234", 20)).thenReturn(player())

        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val result = controller.joinLobby(
            WebSocketMessage(
                type = MessageType.JOIN,
                sender = "ignored-by-server",
                payload = mapOf("lobbyCode" to "ABC1234")
            ),
            accessor,
        )

        val payload = result.payload as? Map<String, Any>
            ?: throw AssertionError("Payload is not a Map")

        assertEquals(MessageType.LOBBY_JOINED, result.type)
        assertEquals("SERVER", result.sender)
        assertEquals("Player joined lobby", result.content)
        assertEquals(1, result.gameId)
        assertEquals(5, payload["playerId"])
        assertEquals(20, payload["userId"])
        assertEquals(1, payload["gameId"])
        assertEquals(3, payload["coins"])

        verify(lobbyService).joinLobby("ABC1234", 20)
    }

    @Test
    fun `joinLobby throws when no authenticated principal is present`() {
        val accessor = SimpMessageHeaderAccessor.create()

        val ex = assertThrows<CustomWebSocketException> {
            controller.joinLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "ghost",
                    payload = mapOf("lobbyCode" to "ABC1234")
                ),
                accessor,
            )
        }

        assertEquals("UNAUTHENTICATED", ex.errorCode)
        verify(lobbyService, never()).joinLobby(any(), any())
    }

    @Test
    fun `joinLobby throws when lobby code is missing`() {
        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val ex = assertThrows<CustomWebSocketException> {
            controller.joinLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "ignored-by-server",
                    payload = emptyMap<String, Any>()
                ),
                accessor,
            )
        }

        assertEquals("INVALID_LOBBY_CODE", ex.errorCode)
        verify(lobbyService, never()).joinLobby(any(), any())
    }

    @Test
    fun `joinLobby returns ERROR message when lobby code is invalid`() {
        whenever(lobbyService.joinLobby("INVALID", 20))
            .thenThrow(GameNotFoundException("Lobby with code INVALID not found"))

        val accessor = authenticatedAccessor(userId = 20, username = "Player2")

        val result = controller.joinLobby(
            WebSocketMessage(
                type = MessageType.JOIN,
                sender = "ignored-by-server",
                payload = mapOf("lobbyCode" to "INVALID")
            ),
            accessor,
        )

        val payload = result.payload as? Map<String, Any>
            ?: throw AssertionError("Payload is not a Map")

        assertEquals(MessageType.ERROR, result.type)
        assertEquals("SERVER", result.sender)
        assertEquals("Lobby code is invalid", result.content)
        assertEquals("INVALID_LOBBY_CODE", payload["errorCode"])
    }
}
