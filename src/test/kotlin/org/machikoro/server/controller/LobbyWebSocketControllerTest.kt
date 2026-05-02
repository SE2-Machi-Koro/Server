package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.LobbyService
import org.junit.jupiter.api.assertThrows
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

        assertThrows<RuntimeException> {
            controller.createLobby(
                WebSocketMessage(
                    type = MessageType.JOIN,
                    sender = "ghost",
                    content = "create lobby",
                ),
                accessor,
            )
        }

        verify(lobbyService, never()).createLobby(any())
    }
}