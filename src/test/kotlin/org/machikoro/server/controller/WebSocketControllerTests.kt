package org.machikoro.server.controller

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.GameStartedException
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.WebSocketConnectionTracker
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate

class WebSocketControllerTests {

    private val lobbyService = mock<LobbyService>()
    private val userDao = mock<UserDao>()
    private val connectionTracker = mock<WebSocketConnectionTracker>()
    private val gameSyncService = mock<GameSyncService>()
    private val messagingTemplate = mock<SimpMessagingTemplate>()
    private val controller = WebSocketController(
        lobbyService,
        userDao,
        connectionTracker,
        gameSyncService,
        messagingTemplate,
    )

    private fun user(id: Int, username: String) = UserModel(
        id = id, username = username, passwordHash = null,
        sessionToken = null, totalWins = 0, totalGamesPlayed = 0
    )

    @Test
    fun sendMessageShouldReturnUnchangedMessage() {
        val message = WebSocketMessage(type = MessageType.CHAT, sender = "alice", content = "Hello")

        val result = controller.sendMessage(message)

        assertEquals(message, result)
    }

    @Test
    fun addUserShouldStoreUsernameInSessionAttributes() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "bob")
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionAttributes = mutableMapOf()

        val result = controller.addUser(message, accessor)

        assertEquals(message, result)
        assertEquals("bob", accessor.sessionAttributes?.get("username"))
    }

    @Test
    fun addUserShouldNotFailWhenSessionAttributesAreMissing() {
        val message = WebSocketMessage(type = MessageType.JOIN, sender = "carol")
        val accessor = SimpMessageHeaderAccessor.create()

        val result = controller.addUser(message, accessor)

        assertEquals(message, result)
        assertNull(accessor.sessionAttributes)
    }

    @Test
    fun `addUser calls addUserToLobby and registers session when user and gameId are present`() {
        val gameId = 1
        val foundUser = user(id = 10, username = "dave")
        whenever(userDao.findByUsername("dave")).thenReturn(foundUser)

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "dave", gameId = gameId)
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionId = "session-abc"
        accessor.sessionAttributes = mutableMapOf()

        controller.addUser(message, accessor)

        verify(lobbyService).addUserToLobby(gameId, foundUser.id)
        verify(connectionTracker).register("session-abc", foundUser.id, gameId)
    }

    @Test
    fun `addUser does not call addUserToLobby when user is not found`() {
        whenever(userDao.findByUsername("unknown")).thenReturn(null)

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "unknown", gameId = 1)
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionAttributes = mutableMapOf()

        controller.addUser(message, accessor)

        verify(lobbyService, never()).addUserToLobby(any(), any())
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `addUser does not call addUserToLobby when gameId is null`() {
        val foundUser = user(id = 5, username = "eve")
        whenever(userDao.findByUsername("eve")).thenReturn(foundUser)

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "eve", gameId = null)
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionAttributes = mutableMapOf()

        controller.addUser(message, accessor)

        verify(lobbyService, never()).addUserToLobby(any(), any())
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `addUser does not register session when sessionId is null`() {
        val foundUser = user(id = 7, username = "frank")
        whenever(userDao.findByUsername("frank")).thenReturn(foundUser)

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "frank", gameId = 2)
        // SimpMessageHeaderAccessor.create() without setting sessionId → sessionId is null
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionAttributes = mutableMapOf()

        controller.addUser(message, accessor)

        verify(lobbyService).addUserToLobby(2, foundUser.id)
        verify(connectionTracker, never()).register(any(), any(), any())
    }

    @Test
    fun `addUser maps reconnecting user to active in-progress game and sends SYNC`() {
        val foundUser = user(id = 11, username = "gina")
        whenever(userDao.findByUsername("gina")).thenReturn(foundUser)
        whenever(gameSyncService.findActiveInProgressGameId(foundUser.id)).thenReturn(7)
        whenever(gameSyncService.buildSnapshot(7)).thenReturn(mock<GameStateDto>())

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "gina", gameId = null)
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionId = "session-rejoin"
        accessor.sessionAttributes = mutableMapOf()

        controller.addUser(message, accessor)

        verify(lobbyService).addUserToLobby(7, foundUser.id)
        verify(connectionTracker).register("session-rejoin", foundUser.id, 7)

        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/7"), captor.capture())
        assertEquals(MessageType.SYNC, captor.firstValue.type)
        assertEquals(7, captor.firstValue.gameId)
    }

    @Test
    fun `addUser handles transition race by re-mapping user and syncing state`() {
        val foundUser = user(id = 12, username = "harry")
        whenever(userDao.findByUsername("harry")).thenReturn(foundUser)
        whenever(gameSyncService.findActiveInProgressGameId(foundUser.id)).thenReturn(null, 3, 3)
        whenever(lobbyService.addUserToLobby(3, foundUser.id)).thenThrow(GameStartedException("already started"))
        whenever(gameSyncService.buildSnapshot(3)).thenReturn(mock<GameStateDto>())

        val message = WebSocketMessage(type = MessageType.JOIN, sender = "harry", gameId = 3)
        val accessor = SimpMessageHeaderAccessor.create()
        accessor.sessionId = "session-race"
        accessor.sessionAttributes = mutableMapOf()

        controller.addUser(message, accessor)

        verify(connectionTracker).register("session-race", foundUser.id, 3)
        val captor = argumentCaptor<WebSocketMessage>()
        verify(messagingTemplate).convertAndSend(eq("/topic/game/3"), captor.capture())
        assertEquals(MessageType.SYNC, captor.firstValue.type)
    }
}