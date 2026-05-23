package org.machikoro.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.util.MimeTypeUtils
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.docker.compose.enabled=false",
        "spring.flyway.enabled=false",
        "machikoro.db.init.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    ],
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GameWebSocketBroadcastIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mapper: ObjectMapper

    @MockitoBean
    private lateinit var userDao: UserDao

    @MockitoBean
    private lateinit var lobbyService: LobbyService

    @MockitoBean
    private lateinit var gamePhaseService: GamePhaseService

    @MockitoBean
    private lateinit var gameStateGuard: GameStateGuard

    @MockitoBean
    private lateinit var gameSyncService: GameSyncService

    @Test
    fun `two active players receive the same scoped game broadcasts`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val host = LoginResult(sessionToken = "host-token-$suffix", username = "ws_host_$suffix", userId = 101)
        val guest = LoginResult(sessionToken = "guest-token-$suffix", username = "ws_guest_$suffix", userId = 202)
        val gameId = 303
        val lobbyCode = "ABC$suffix".take(8).uppercase()
        configureDomainStubs(host, guest, gameId, lobbyCode)

        val hostSession = connect(host.sessionToken)
        val guestSession = connect(guest.sessionToken)

        sendJson(
            hostSession,
            "/app/lobby.create",
            mapOf("type" to MessageType.JOIN.name, "sender" to host.username),
        )

        val hostGameQueue = LinkedBlockingQueue<JsonNode>()
        val guestGameQueue = LinkedBlockingQueue<JsonNode>()
        hostSession.subscribe("/topic/game/$gameId", queueHandler(hostGameQueue))
        guestSession.subscribe("/topic/game/$gameId", queueHandler(guestGameQueue))
        waitForBrokerSubscription()

        sendJson(
            hostSession,
            "/app/chat.addUser",
            mapOf("type" to MessageType.JOIN.name, "sender" to host.username, "gameId" to gameId),
        )
        sendJson(
            guestSession,
            "/app/lobby.join",
            mapOf(
                "type" to MessageType.JOIN.name,
                "sender" to guest.username,
                "payload" to mapOf("lobbyCode" to lobbyCode),
            ),
        )
        assertSameGameBroadcast(hostGameQueue, guestGameQueue, MessageType.LOBBY_JOINED)

        sendJson(
            guestSession,
            "/app/chat.addUser",
            mapOf("type" to MessageType.JOIN.name, "sender" to guest.username, "gameId" to gameId),
        )

        sendJson(hostSession, "/app/game.start", mapOf("gameId" to gameId))
        val startedPair = assertSameGameBroadcast(hostGameQueue, guestGameQueue, MessageType.GAME_STARTED)
        assertSameGameActionBroadcast(hostGameQueue, guestGameQueue, "GAME_STARTED")

        val firstActiveUserId = startedPair.first["payload"]["activePlayerId"].asInt()
        val firstActiveSession = sessionFor(firstActiveUserId, host, hostSession, guestSession)
        sendJson(firstActiveSession, "/app/game.advancePhase", mapOf("gameId" to gameId))
        assertSameGameActionBroadcast(hostGameQueue, guestGameQueue, "PHASE_ADVANCED")

        sendJson(firstActiveSession, "/app/game.advancePhase", mapOf("gameId" to gameId))
        assertSameGameActionBroadcast(hostGameQueue, guestGameQueue, "PHASE_ADVANCED")

        sendJson(firstActiveSession, "/app/game.endTurn", mapOf("gameId" to gameId))
        val turnEnded = assertSameGameActionBroadcast(hostGameQueue, guestGameQueue, "TURN_ENDED")
        val secondActiveUserId = turnEnded.first["payload"]["activePlayerId"].asInt()
        assertTrue(
            secondActiveUserId == host.userId || secondActiveUserId == guest.userId,
            "Next active player must be one of the two connected users",
        )
        assertTrue(
            secondActiveUserId != firstActiveUserId,
            "Two-player end turn must pass control to the other player",
        )

        val secondActiveSession = sessionFor(secondActiveUserId, host, hostSession, guestSession)
        sendJson(secondActiveSession, "/app/game.advancePhase", mapOf("gameId" to gameId))
        assertSameGameActionBroadcast(hostGameQueue, guestGameQueue, "PHASE_ADVANCED")
    }

    private fun configureDomainStubs(
        host: LoginResult,
        guest: LoginResult,
        gameId: Int,
        lobbyCode: String,
    ) {
        val hostPlayer = player(playerId = 11, gameId = gameId, userId = host.userId, turnOrder = 0)
        val guestPlayer = player(playerId = 22, gameId = gameId, userId = guest.userId, turnOrder = 1)
        val createdGame = game(
            gameId = gameId,
            hostUserId = host.userId,
            phase = TurnPhase.ROLL_DICE,
            lobbyCode = lobbyCode,
            status = GameStatus.WAITING,
        )
        val inProgressGame = createdGame.copy(status = GameStatus.IN_PROGRESS)
        val startedState = state(inProgressGame, hostPlayer, guestPlayer, activeUserId = host.userId)
        val resolveEffectsState = state(
            inProgressGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS),
            hostPlayer,
            guestPlayer,
            activeUserId = host.userId,
        )
        val buyOrBuildState = state(
            inProgressGame.copy(turnPhase = TurnPhase.BUY_OR_BUILD),
            hostPlayer,
            guestPlayer,
            activeUserId = host.userId,
        )
        val guestTurnState = state(
            inProgressGame.copy(turnPhase = TurnPhase.ROLL_DICE, currentTurnIndex = 1),
            hostPlayer,
            guestPlayer,
            activeUserId = guest.userId,
        )
        val guestResolveEffectsState = state(
            inProgressGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, currentTurnIndex = 1),
            hostPlayer,
            guestPlayer,
            activeUserId = guest.userId,
        )

        whenever(userDao.findBySessionToken(host.sessionToken)).thenReturn(user(host))
        whenever(userDao.findBySessionToken(guest.sessionToken)).thenReturn(user(guest))
        whenever(gameSyncService.findActiveInProgressGameId(any())).thenReturn(null)
        whenever(lobbyService.createLobby(host.userId)).thenReturn(createdGame)
        whenever(lobbyService.addUserToLobby(gameId, host.userId)).thenReturn(hostPlayer)
        whenever(lobbyService.addUserToLobby(gameId, guest.userId)).thenReturn(guestPlayer)
        whenever(lobbyService.joinLobby(lobbyCode, guest.userId)).thenReturn(guestPlayer)
        whenever(lobbyService.getLobbyRoster(gameId)).thenReturn(emptyList())
        whenever(lobbyService.startGame(gameId, host.userId)).thenReturn(startedState)
        whenever(gamePhaseService.advancePhase(gameId)).thenReturn(TurnPhase.RESOLVE_EFFECTS, TurnPhase.BUY_OR_BUILD)
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))
        whenever(gameSyncService.buildSnapshot(gameId)).thenReturn(
            resolveEffectsState,
            buyOrBuildState,
            guestTurnState,
            guestResolveEffectsState,
        )
        whenever(gameStateGuard.ensureSenderIsActivePlayer(eq(gameId), any<UserPrincipal>()))
            .thenAnswer { invocation ->
                when (invocation.getArgument<UserPrincipal>(1).userId) {
                    host.userId -> hostPlayer
                    guest.userId -> guestPlayer
                    else -> error("Unexpected active player principal")
                }
            }
    }

    private fun connect(sessionToken: String): StompSession {
        val connectHeaders = StompHeaders().apply {
            add("Authorization", "Bearer $sessionToken")
        }
        return stompClient()
            .connectAsync(
                "ws://localhost:$port/ws",
                WebSocketHttpHeaders(),
                connectHeaders,
                object : StompSessionHandlerAdapter() {},
            )
            .get(10, TimeUnit.SECONDS)
    }

    private fun stompClient(): WebSocketStompClient =
        WebSocketStompClient(StandardWebSocketClient()).apply {
            messageConverter = MappingJackson2MessageConverter().apply {
                objectMapper = mapper
            }
        }

    private fun queueHandler(queue: BlockingQueue<JsonNode>): StompFrameHandler =
        object : StompFrameHandler {
            override fun getPayloadType(headers: StompHeaders): Type = JsonNode::class.java

            override fun handleFrame(headers: StompHeaders, payload: Any?) {
                queue.offer(payload as JsonNode)
            }
        }

    private fun sendJson(session: StompSession, destination: String, value: Any) {
        val headers = StompHeaders().apply {
            this.destination = destination
            contentType = MimeTypeUtils.APPLICATION_JSON
        }
        session.send(headers, value)
    }

    private fun assertSameGameBroadcast(
        hostQueue: BlockingQueue<JsonNode>,
        guestQueue: BlockingQueue<JsonNode>,
        type: MessageType,
    ): Pair<JsonNode, JsonNode> {
        val hostMessage = takeMessage(hostQueue, type)
        val guestMessage = takeMessage(guestQueue, type)
        assertEquivalentGameBroadcast(hostMessage, guestMessage)
        return hostMessage to guestMessage
    }

    private fun assertSameGameActionBroadcast(
        hostQueue: BlockingQueue<JsonNode>,
        guestQueue: BlockingQueue<JsonNode>,
        event: String,
    ): Pair<JsonNode, JsonNode> {
        val hostMessage = takeGameAction(hostQueue, event)
        val guestMessage = takeGameAction(guestQueue, event)
        assertEquivalentGameBroadcast(hostMessage, guestMessage)
        assertEquals(event, hostMessage["payload"]["event"].asText())
        assertEquals(event, guestMessage["payload"]["event"].asText())
        return hostMessage to guestMessage
    }

    private fun takeGameAction(queue: BlockingQueue<JsonNode>, event: String): JsonNode =
        generateSequence { takeMessage(queue, MessageType.GAME_ACTION) }
            .first { it["payload"]["event"].asText() == event }

    private fun assertEquivalentGameBroadcast(first: JsonNode, second: JsonNode) {
        assertEquals(first["type"].asText(), second["type"].asText())
        assertEquals(first["gameId"].asInt(), second["gameId"].asInt())
        assertEquals(first["payload"].path("event").asText(null), second["payload"].path("event").asText(null))
        assertEquals(
            first["payload"].path("activePlayerId").asInt(0),
            second["payload"].path("activePlayerId").asInt(0),
        )
    }

    private fun takeMessage(queue: BlockingQueue<JsonNode>, type: MessageType): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        val seen = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            seen += message.toString()
            if (message["type"].asText() == type.name) {
                return message
            }
        }
        error("Timed out waiting for $type; seen=$seen")
    }

    private fun sessionFor(
        userId: Int,
        host: LoginResult,
        hostSession: StompSession,
        guestSession: StompSession,
    ): StompSession =
        if (userId == host.userId) {
            hostSession
        } else {
            guestSession
        }

    private fun waitForBrokerSubscription() {
        TimeUnit.MILLISECONDS.sleep(250)
    }

    private data class LoginResult(
        val sessionToken: String,
        val username: String,
        val userId: Int,
    )

    private fun user(login: LoginResult): UserModel =
        UserModel(
            id = login.userId,
            username = login.username,
            passwordHash = null,
            sessionToken = login.sessionToken,
            totalWins = 0,
            totalGamesPlayed = 0,
        )

    private fun game(
        gameId: Int,
        hostUserId: Int,
        phase: TurnPhase,
        lobbyCode: String,
        status: GameStatus = GameStatus.IN_PROGRESS,
    ): GameModel =
        GameModel(
            id = gameId,
            status = status,
            hostUserId = hostUserId,
            lobbyCode = lobbyCode,
            maxPlayers = 4,
            currentTurnIndex = 0,
            turnPhase = phase,
            lastDiceRoll = null,
            roundNumber = 1,
            hasPurchasedThisTurn = false,
        )

    private fun player(playerId: Int, gameId: Int, userId: Int, turnOrder: Int): PlayerModel =
        PlayerModel(
            id = playerId,
            gameId = gameId,
            userId = userId,
            turnOrder = turnOrder,
            coins = 3,
            lastSeenAt = null,
        )

    private fun state(
        game: GameModel,
        firstPlayer: PlayerModel,
        secondPlayer: PlayerModel,
        activeUserId: Int,
    ): GameStateDto =
        GameStateDto(
            game = game,
            players = listOf(firstPlayer, secondPlayer),
            playerCards = emptyMap(),
            playerLandmarks = emptyMap(),
            marketplace = emptyMap(),
            turnOrder = listOf(firstPlayer.userId, secondPlayer.userId),
            activePlayerId = activeUserId,
            playerUsernames = mapOf(
                firstPlayer.id to "host",
                secondPlayer.id to "guest",
            ),
        )
}
