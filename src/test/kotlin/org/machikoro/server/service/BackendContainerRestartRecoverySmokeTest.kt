package org.machikoro.server.service

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.PurchaseType
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.util.MimeTypeUtils
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@Tag("docker-smoke")
@EnabledIfEnvironmentVariable(named = "MACHIKORO_DOCKER_SMOKE", matches = "true")
class BackendContainerRestartRecoverySmokeTest {

    private val mapper = com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val baseUrl = env("MACHIKORO_SMOKE_BASE_URL", "http://localhost:58080")
    private val wsUrl = env("MACHIKORO_SMOKE_WS_URL", "ws://localhost:58080/ws")
    private val stompClient = WebSocketStompClient(StandardWebSocketClient()).apply {
        messageConverter = MappingJackson2MessageConverter().apply {
            objectMapper = mapper
        }
    }

    @Test
    fun `in-progress game survives backend container restart and sync reconnect`() {
        waitForHealth()
        seedReferenceData()

        val suffix = UUID.randomUUID().toString().replace("-", "").take(10)
        val host = registerAndLogin("smoke_host_$suffix")
        val guest = registerAndLogin("smoke_guest_$suffix")
        val hostSession = connect(host.sessionToken)
        val guestSession = connect(guest.sessionToken)
        val publicMessages = LinkedBlockingQueue<JsonNode>()

        hostSession.subscribe("/topic/public", queueHandler(publicMessages))
        guestSession.subscribe("/topic/public", queueHandler(publicMessages))
        Thread.sleep(250)

        sendJson(
            hostSession,
            "/app/lobby.create",
            mapOf("type" to "JOIN", "sender" to host.username),
        )
        val created = takeMessage(publicMessages, "LOBBY_CREATED")
        val gameId = created["gameId"].asInt()
        val lobbyCode = created["payload"]["lobbyCode"].asText()

        sendJson(
            hostSession,
            "/app/chat.addUser",
            mapOf("type" to "JOIN", "sender" to host.username, "gameId" to gameId),
        )
        takeMessage(publicMessages, "JOIN")
        sendJson(
            guestSession,
            "/app/lobby.join",
            mapOf("type" to "JOIN", "sender" to guest.username, "payload" to mapOf("lobbyCode" to lobbyCode)),
        )
        takeMessage(publicMessages, "LOBBY_JOINED")
        sendJson(
            guestSession,
            "/app/chat.addUser",
            mapOf("type" to "JOIN", "sender" to guest.username, "gameId" to gameId),
        )
        takeMessage(publicMessages, "JOIN")

        val gameMessages = LinkedBlockingQueue<JsonNode>()
        hostSession.subscribe("/topic/game/$gameId", queueHandler(gameMessages))
        guestSession.subscribe("/topic/game/$gameId", queueHandler(gameMessages))
        Thread.sleep(250)

        sendJson(hostSession, "/app/game.start", mapOf("gameId" to gameId))
        val started = takeMessage(gameMessages, "GAME_STARTED")
        val startedState = started["payload"]
        val firstActiveUserId = startedState["activePlayerId"].asInt()
        var activeUserId = firstActiveUserId
        val firstActiveSession = if (activeUserId == host.userId) hostSession else guestSession

        advanceToBuy(firstActiveSession, gameId, gameMessages)
        sendJson(
            firstActiveSession,
            "/app/game.purchase",
            mapOf(
                "gameId" to gameId,
                "purchaseType" to PurchaseType.ESTABLISHMENT.name,
                "cardType" to CardType.BAKERY.name,
            ),
        )
        takePurchase(gameMessages)
        sendJson(firstActiveSession, "/app/game.endTurn", mapOf("gameId" to gameId))
        val nextTurn = takeGameAction(gameMessages) { it.path("turnPhase").asText() == TurnPhase.ROLL_DICE.name }
        activeUserId = nextTurn["payload"]["activePlayerId"].asInt()

        setCoins(gameId, activeUserId, 10)
        val secondActiveSession = if (activeUserId == host.userId) hostSession else guestSession
        advanceToBuy(secondActiveSession, gameId, gameMessages)
        sendJson(
            secondActiveSession,
            "/app/game.purchase",
            mapOf(
                "gameId" to gameId,
                "purchaseType" to PurchaseType.LANDMARK.name,
                "landmarkType" to LandmarkType.TRAIN_STATION.name,
            ),
        )
        takePurchase(gameMessages)

        restartBackendContainer()
        waitForHealth()

        val reconnectedHost = connect(host.sessionToken)
        val syncMessages = LinkedBlockingQueue<JsonNode>()
        reconnectedHost.subscribe("/user/queue/game-sync", queueHandler(syncMessages))
        reconnectedHost.subscribe("/queue/game-sync", queueHandler(syncMessages))
        reconnectedHost.subscribe("/user/${host.username}/queue/game-sync", queueHandler(syncMessages))
        Thread.sleep(250)
        sendJson(
            reconnectedHost,
            "/app/chat.addUser",
            mapOf("type" to "JOIN", "sender" to host.username, "gameId" to gameId),
        )
        sendJson(reconnectedHost, "/app/game.sync", mapOf("gameId" to gameId))

        val sync = takeMessage(syncMessages, "SYNC", timeoutSeconds = 20)
        val recoveredState = sync["payload"]["state"]

        assertEquals(gameId, recoveredState["game"]["id"].asInt())
        assertEquals(GameStatus.IN_PROGRESS.name, recoveredState["game"]["status"].asText())
        assertEquals(TurnPhase.BUY_OR_BUILD.name, recoveredState["game"]["turnPhase"].asText())
        assertEquals(2, recoveredState["players"].size())
        assertTrue(recoveredState["turnOrder"].map { it.asInt() }.containsAll(listOf(host.userId, guest.userId)))
        assertNotNull(recoveredState["activePlayerId"])
        assertEquals(5, recoveredState["marketplace"][CardType.BAKERY.name].asInt())

        val bakeryBuyer = recoveredState["players"].first { it["userId"].asInt() == firstActiveUserId }
        val bakeryBuyerCards = recoveredState["playerCards"][bakeryBuyer["id"].asText()]
        assertEquals(
            2,
            bakeryBuyerCards.first { it["cardType"].asText() == CardType.BAKERY.name }["quantity"].asInt(),
        )

        val landmarkBuilder = recoveredState["players"].first { it["userId"].asInt() == activeUserId }
        val landmarkBuilderLandmarks = recoveredState["playerLandmarks"][landmarkBuilder["id"].asText()]
        assertTrue(
            landmarkBuilderLandmarks.first {
                it["landmarkType"].asText() == LandmarkType.TRAIN_STATION.name
            }["isBuilt"].asBoolean(),
        )

        val continuingSession = if (activeUserId == host.userId) reconnectedHost else connect(guest.sessionToken)
        continuingSession.subscribe("/topic/game/$gameId", queueHandler(gameMessages))
        sendJson(continuingSession, "/app/game.endTurn", mapOf("gameId" to gameId))
        takeGameAction(gameMessages) { it.path("turnPhase").asText() == TurnPhase.ROLL_DICE.name }
    }

    private fun advanceToBuy(
        session: StompSession,
        gameId: Int,
        queue: BlockingQueue<JsonNode>,
    ) {
        sendJson(session, "/app/game.advancePhase", mapOf("gameId" to gameId))
        takeGameAction(queue) { it.path("turnPhase").asText() == TurnPhase.RESOLVE_EFFECTS.name }
        sendJson(session, "/app/game.advancePhase", mapOf("gameId" to gameId))
        takeGameAction(queue) { it.path("turnPhase").asText() == TurnPhase.BUY_OR_BUILD.name }
    }

    private fun takePurchase(queue: BlockingQueue<JsonNode>): JsonNode =
        takeGameAction(queue) { it.path("event").asText() == "PURCHASE_COMPLETED" }

    private fun takeGameAction(
        queue: BlockingQueue<JsonNode>,
        predicate: (JsonNode) -> Boolean,
    ): JsonNode =
        generateSequence { takeMessage(queue, "GAME_ACTION", timeoutSeconds = 20) }
            .first { predicate(it["payload"]) }

    private fun connect(sessionToken: String): StompSession {
        val connectHeaders = StompHeaders().apply {
            add("Authorization", "Bearer $sessionToken")
        }
        return stompClient
            .connectAsync(wsUrl, WebSocketHttpHeaders(), connectHeaders, object : StompSessionHandlerAdapter() {})
            .get(10, TimeUnit.SECONDS)
    }

    private fun queueHandler(queue: BlockingQueue<JsonNode>) = object : StompFrameHandler {
        override fun getPayloadType(headers: StompHeaders): Type = JsonNode::class.java

        override fun handleFrame(headers: StompHeaders, payload: Any?) {
            queue.offer(payload as JsonNode)
        }
    }

    private fun takeMessage(
        queue: BlockingQueue<JsonNode>,
        type: String,
        timeoutSeconds: Long = 10,
    ): JsonNode {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        val seen = mutableListOf<String>()
        while (System.nanoTime() < deadline) {
            val remaining = deadline - System.nanoTime()
            val message = queue.poll(remaining, TimeUnit.NANOSECONDS)
                ?: break
            seen += message.toString()
            if (message["type"].asText() == type) return message
        }
        error("Timed out waiting for $type; seen=$seen")
    }

    private fun registerAndLogin(username: String): LoginResult {
        val password = "SmokeTest-123!"
        post("/auth/register", mapOf("username" to username, "password" to password))
        val login = post("/auth/login", mapOf("username" to username, "password" to password))
        return LoginResult(
            sessionToken = login["sessionToken"].asText(),
            username = login["username"].asText(),
            userId = login["userId"].asInt(),
        )
    }

    private fun post(path: String, body: Any): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("POST $path failed with ${response.statusCode()}: ${response.body()}")
        }
        return mapper.readTree(response.body())
    }

    private fun waitForHealth() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
        while (System.nanoTime() < deadline) {
            runCatching {
                val request = HttpRequest.newBuilder(URI.create("$baseUrl/actuator/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }.onSuccess {
                if (it.statusCode() in 200..299) return
            }
            Thread.sleep(1_000)
        }
        error("Backend did not become healthy at $baseUrl")
    }

    private fun setCoins(gameId: Int, userId: Int, coins: Int) {
        smokeDbConnection().use { connection ->
            connection.prepareStatement(
                """
                update players
                set coins = ?
                where game_id = ? and user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, coins)
                statement.setInt(2, gameId)
                statement.setInt(3, userId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun seedReferenceData() {
        data class CardSeed(
            val type: CardType,
            val cost: Int,
            val income: Int,
            val color: String,
            val establishmentType: String,
            val paymentSource: String,
            val activationNumbers: List<Int>,
        )

        val cards = listOf(
            CardSeed(CardType.WHEAT_FIELD, 1, 1, "BLUE", "WHEAT", "BANK", listOf(1)),
            CardSeed(CardType.RANCH, 1, 1, "BLUE", "COW", "BANK", listOf(2)),
            CardSeed(CardType.FOREST, 3, 1, "BLUE", "GEAR", "BANK", listOf(5)),
            CardSeed(CardType.MINE, 6, 5, "BLUE", "GEAR", "BANK", listOf(9)),
            CardSeed(CardType.APPLE_ORCHARD, 3, 3, "BLUE", "WHEAT", "BANK", listOf(10)),
            CardSeed(CardType.BAKERY, 1, 1, "GREEN", "BREAD", "BANK", listOf(2, 3)),
            CardSeed(CardType.CONVENIENCE_STORE, 2, 3, "GREEN", "BREAD", "BANK", listOf(4)),
            CardSeed(CardType.CHEESE_FACTORY, 5, 3, "GREEN", "FACTORY", "BANK", listOf(7)),
            CardSeed(CardType.FURNITURE_FACTORY, 3, 3, "GREEN", "FACTORY", "BANK", listOf(8)),
            CardSeed(CardType.FRUIT_AND_VEGETABLE_MARKET, 2, 2, "GREEN", "FRUIT", "BANK", listOf(11, 12)),
            CardSeed(CardType.CAFE, 2, 1, "RED", "CUP", "ACTIVE_PLAYER", listOf(3)),
            CardSeed(CardType.FAMILY_RESTAURANT, 3, 2, "RED", "CUP", "ACTIVE_PLAYER", listOf(9, 10)),
            CardSeed(CardType.STADIUM, 6, 2, "PURPLE", "MAJOR", "ALL_PLAYERS", listOf(6)),
            CardSeed(CardType.TV_STATION, 7, 5, "PURPLE", "MAJOR", "CHOSEN_PLAYER", listOf(6)),
            CardSeed(CardType.BUSINESS_CENTER, 8, 0, "PURPLE", "MAJOR", "CHOSEN_PLAYER", listOf(6)),
        )
        val landmarks = mapOf(
            LandmarkType.TRAIN_STATION to 4,
            LandmarkType.SHOPPING_MALL to 10,
            LandmarkType.AMUSEMENT_PARK to 16,
            LandmarkType.RADIO_TOWER to 22,
        )

        smokeDbConnection().use { connection ->
            cards.forEach { card ->
                connection.prepareStatement(
                    """
                    insert into cards(card_type, cost, income, color, establishment_type, payment_source)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (card_type) do update set card_type = excluded.card_type
                    returning id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, card.type.name)
                    statement.setInt(2, card.cost)
                    statement.setInt(3, card.income)
                    statement.setString(4, card.color)
                    statement.setString(5, card.establishmentType)
                    statement.setString(6, card.paymentSource)
                    val result = statement.executeQuery()
                    result.next()
                    val cardId = result.getInt("id")
                    card.activationNumbers.forEach { number ->
                        connection.prepareStatement(
                            """
                            insert into card_activation_numbers(card_id, number)
                            values (?, ?)
                            on conflict do nothing
                            """.trimIndent(),
                        ).use { activationStatement ->
                            activationStatement.setInt(1, cardId)
                            activationStatement.setInt(2, number)
                            activationStatement.executeUpdate()
                        }
                    }
                }
            }

            landmarks.forEach { (type, cost) ->
                connection.prepareStatement(
                    """
                    insert into landmarks(landmark_type, cost)
                    values (?, ?)
                    on conflict (landmark_type) do nothing
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, type.name)
                    statement.setInt(2, cost)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun smokeDbConnection() =
        DriverManager.getConnection(
            "jdbc:postgresql://localhost:${env("MACHIKORO_SMOKE_DB_PORT", "55432")}/${env("MACHIKORO_SMOKE_DB_NAME", "machikoro_smoke")}",
            env("MACHIKORO_SMOKE_DB_USERNAME", "smoke"),
            env("MACHIKORO_SMOKE_DB_PASSWORD", "smoke"),
        )

    private fun restartBackendContainer() {
        val root = env("MACHIKORO_SMOKE_ROOT", System.getProperty("user.dir"))
        val envFile = env("MACHIKORO_SMOKE_ENV_FILE", "")
        require(envFile.isNotBlank()) { "MACHIKORO_SMOKE_ENV_FILE is required" }

        val result = ProcessBuilder(
            "docker",
            "compose",
            "-p",
            env("MACHIKORO_SMOKE_COMPOSE_PROJECT", "machikoro-recovery-smoke"),
            "-f",
            "$root/compose.yaml",
            "-f",
            "$root/compose.smoke-test.yaml",
            "--env-file",
            envFile,
            "restart",
            "backend",
        )
            .directory(java.io.File(root))
            .redirectErrorStream(true)
            .start()

        val output = result.inputStream.bufferedReader().readText()
        val exit = result.waitFor()
        if (exit != 0) {
            error("docker compose restart backend failed with exit $exit:\n$output")
        }
    }

    private fun sendJson(session: StompSession, destination: String, value: Any) {
        val headers = StompHeaders().apply {
            this.destination = destination
            contentType = MimeTypeUtils.APPLICATION_JSON
        }
        session.send(headers, value)
    }

    private fun env(name: String, default: String): String =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

    private data class LoginResult(
        val sessionToken: String,
        val username: String,
        val userId: Int,
    )
}
