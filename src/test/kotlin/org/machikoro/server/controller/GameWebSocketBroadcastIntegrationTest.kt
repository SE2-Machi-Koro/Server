package org.machikoro.server.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.domain.models.UserModel
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.dto.GameStateDto
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseType
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.GameStateGuard
import org.machikoro.server.service.GameSyncService
import org.machikoro.server.service.LobbyService
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.SimpMessagingTemplate
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
    private companion object {
        private const val MESSAGE_TIMEOUT_SECONDS = 20L
        private const val BROKER_SUBSCRIPTION_TIMEOUT_SECONDS = 10L
        private const val BROKER_SUBSCRIPTION_PROBE_WAIT_MILLIS = 250L
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var messagingTemplate: SimpMessagingTemplate

    private val activeSessions = mutableListOf<StompSession>()

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

    @MockitoBean
    private lateinit var purchaseService: PurchaseService

    @MockitoBean
    private lateinit var diceService: DiceService

    @MockitoBean
    private lateinit var earningsService: EarningsService

    @AfterEach
    fun disconnectSessions() {
        activeSessions.filter { it.isConnected }.forEach(StompSession::disconnect)
        activeSessions.clear()
    }

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
        val gameTopic = "/topic/game/$gameId"
        hostSession.subscribe(gameTopic, queueHandler(hostGameQueue))
        guestSession.subscribe(gameTopic, queueHandler(guestGameQueue))
        waitForBrokerSubscriptions(gameTopic, hostGameQueue, guestGameQueue)

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
        val startMessages = takeSameMessageBatch(hostGameQueue, guestGameQueue, expectedMessages = 2, description = "game start")
        val startedPair = assertSameBroadcastInBatch(startMessages, "GAME_STARTED frame") {
            it["type"].asText() == MessageType.GAME_STARTED.name
        }
        assertSameBroadcastInBatch(startMessages, "GAME_STARTED action") {
            it["type"].asText() == MessageType.GAME_ACTION.name &&
                it["payload"]["event"].asText() == "GAME_STARTED"
        }

        val firstActiveUserId = startedPair.first["payload"]["activePlayerId"].asInt()
        val firstActiveSession = sessionFor(firstActiveUserId, host, hostSession, guestSession)
        sendJson(firstActiveSession, "/app/game.rollDice", mapOf("gameId" to gameId))
        val firstRollMessages = takeSameMessageBatch(hostGameQueue, guestGameQueue, expectedMessages = 2, description = "first dice roll")
        assertSameBroadcastInBatch(firstRollMessages, "ROLL_DICE frame") {
            it["type"].asText() == MessageType.ROLL_DICE.name
        }
        assertSameBroadcastInBatch(firstRollMessages, "DICE_ROLLED action") {
            it["type"].asText() == MessageType.GAME_ACTION.name &&
                it["payload"]["event"].asText() == "DICE_ROLLED"
        }

        sendJson(firstActiveSession, "/app/game.resolveEffects", mapOf("gameId" to gameId))
        assertSameGameActionBroadcast(hostGameQueue, guestGameQueue, "EFFECTS_RESOLVED")

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
        sendJson(secondActiveSession, "/app/game.rollDice", mapOf("gameId" to gameId))
        val secondRollMessages = takeSameMessageBatch(hostGameQueue, guestGameQueue, expectedMessages = 2, description = "second dice roll")
        assertSameBroadcastInBatch(secondRollMessages, "second ROLL_DICE frame") {
            it["type"].asText() == MessageType.ROLL_DICE.name
        }
        assertSameBroadcastInBatch(secondRollMessages, "second DICE_ROLLED action") {
            it["type"].asText() == MessageType.GAME_ACTION.name &&
                it["payload"]["event"].asText() == "DICE_ROLLED"
        }
    }

    @Test
    fun `purchase rejection is broadcast as client consumable game topic error`() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val host = LoginResult(sessionToken = "host-token-$suffix", username = "ws_host_$suffix", userId = 101)
        val guest = LoginResult(sessionToken = "guest-token-$suffix", username = "ws_guest_$suffix", userId = 202)
        val gameId = 303
        configureDomainStubs(host, guest, gameId, "ABC$suffix".take(8).uppercase())
        whenever(purchaseService.purchase(gameId, PurchaseType.ESTABLISHMENT, CardType.STADIUM, null))
            .thenThrow(
                CustomWebSocketException(
                    "DUPLICATE_PURPLE_ESTABLISHMENT",
                    "Player already owns purple establishment STADIUM",
                )
            )

        val hostSession = connect(host.sessionToken)
        val guestSession = connect(guest.sessionToken)
        val hostGameQueue = LinkedBlockingQueue<JsonNode>()
        val guestGameQueue = LinkedBlockingQueue<JsonNode>()
        val gameTopic = "/topic/game/$gameId"
        hostSession.subscribe(gameTopic, queueHandler(hostGameQueue))
        guestSession.subscribe(gameTopic, queueHandler(guestGameQueue))
        waitForBrokerSubscriptions(gameTopic, hostGameQueue, guestGameQueue)

        sendJson(
            hostSession,
            "/app/game.purchase",
            mapOf(
                "gameId" to gameId,
                "purchaseType" to PurchaseType.ESTABLISHMENT.name,
                "cardType" to CardType.STADIUM.name,
            ),
        )

        val (hostMessage, guestMessage) = assertSameGameBroadcast(hostGameQueue, guestGameQueue, MessageType.ERROR)
        assertPurchaseFailurePayload(hostMessage)
        assertPurchaseFailurePayload(guestMessage)
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
        whenever(gamePhaseService.endTurn(gameId)).thenReturn(EndTurnOutcome.Continue(TurnPhase.ROLL_DICE))
        whenever(diceService.rollDice(any(), any())).thenReturn(RollDiceResponse(result = listOf(4), total = 4))
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
            .also { session ->
                activeSessions.add(session)
            }
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

    private fun waitForBrokerSubscriptions(destination: String, vararg queues: BlockingQueue<JsonNode>) {
        val ready = BooleanArray(queues.size)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BROKER_SUBSCRIPTION_TIMEOUT_SECONDS)

        while (System.nanoTime() < deadline) {
            val probeId = UUID.randomUUID().toString()
            messagingTemplate.convertAndSend(
                destination,
                WebSocketMessage(
                    type = MessageType.CHAT,
                    sender = "test",
                    payload = mapOf("event" to "SUBSCRIPTION_READY", "probeId" to probeId),
                ),
            )

            queues.forEachIndexed { index, queue ->
                if (drainSubscriptionProbes(queue, probeId)) {
                    ready[index] = true
                }
            }

            if (ready.all { it }) {
                return
            }
        }

        error("Timed out waiting for broker subscriptions to $destination; ready=${ready.toList()}")
    }

    private fun drainSubscriptionProbes(queue: BlockingQueue<JsonNode>, probeId: String): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BROKER_SUBSCRIPTION_PROBE_WAIT_MILLIS)
        var receivedExpectedProbe = false
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: return receivedExpectedProbe
            if (
                message.path("type").asText() == MessageType.CHAT.name &&
                message.path("payload").path("probeId").asText() == probeId
            ) {
                receivedExpectedProbe = true
            }
        }
        return receivedExpectedProbe
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

    private fun takeSameMessageBatch(
        hostQueue: BlockingQueue<JsonNode>,
        guestQueue: BlockingQueue<JsonNode>,
        expectedMessages: Int,
        description: String,
    ): Pair<List<JsonNode>, List<JsonNode>> =
        takeMessages(hostQueue, expectedMessages, description) to
            takeMessages(guestQueue, expectedMessages, description)

    private fun assertSameBroadcastInBatch(
        messages: Pair<List<JsonNode>, List<JsonNode>>,
        description: String,
        predicate: (JsonNode) -> Boolean,
    ): Pair<JsonNode, JsonNode> {
        val (hostMessages, guestMessages) = messages
        val hostMessage = hostMessages.firstOrNull(predicate)
            ?: error("Timed out waiting for $description; seen=$hostMessages")
        val guestMessage = guestMessages.firstOrNull(predicate)
            ?: error("Timed out waiting for $description; seen=$guestMessages")
        assertEquivalentGameBroadcast(hostMessage, guestMessage)
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

    private fun assertPurchaseFailurePayload(message: JsonNode) {
        val payload = message["payload"]
        val context = payload["context"]
        assertEquals("server", message["sender"].asText())
        assertEquals("DUPLICATE_PURPLE_ESTABLISHMENT", payload["code"].asText())
        assertEquals("Player already owns purple establishment STADIUM", payload["message"].asText())
        assertEquals("PURCHASE_FAILED", context["event"].asText())
        assertEquals("ESTABLISHMENT", context["purchaseType"].asText())
        assertEquals("STADIUM", context["cardType"].asText())
    }

    private fun takeMessage(queue: BlockingQueue<JsonNode>, type: MessageType): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MESSAGE_TIMEOUT_SECONDS)
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

    private fun takeMessages(queue: BlockingQueue<JsonNode>, count: Int, description: String): List<JsonNode> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(MESSAGE_TIMEOUT_SECONDS)
        val messages = mutableListOf<JsonNode>()
        while (messages.size < count && System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            messages.add(message)
        }
        if (messages.size < count) {
            error("Timed out waiting for $count messages for $description; seen=$messages")
        }
        return messages
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
            isAdmin = false,
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
