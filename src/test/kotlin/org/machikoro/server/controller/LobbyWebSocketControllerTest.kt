package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.LobbyService
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LobbyWebSocketControllerTest {

    private val lobbyService = mock<LobbyService>()
    private val userDao = mock<UserDao>()
    private val controller = LobbyWebSocketController(lobbyService, userDao)

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
    fun `createLobby creates lobby for sender and returns LOBBY_CREATED message`() {
        val username = "Player1"
        val user = UserModel(
            id = 10,
            username = username,
            passwordHash = null,
            sessionToken = null,
            totalWins = 0,
            totalGamesPlayed = 0
        )

        whenever(userDao.findByUsername(username)).thenReturn(user)
        whenever(lobbyService.createLobby(user.id)).thenReturn(game())

        val result = controller.createLobby(
            WebSocketMessage(
                type = MessageType.JOIN,
                sender = username,
                content = "create lobby"
            )
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

        verify(userDao).findByUsername(username)
        verify(lobbyService).createLobby(user.id)
    }
}