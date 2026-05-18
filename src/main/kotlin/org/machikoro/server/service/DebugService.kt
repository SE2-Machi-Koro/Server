package org.machikoro.server.service

import org.machikoro.server.dao.UserDao
import org.machikoro.server.dto.DebugSeedResponse
import org.machikoro.server.dto.LoginResponse
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.exception.LobbyFullException
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID

// Provisions dummy users, a shared lobby, and starts the game in one call
@Service
class DebugService(
    private val userDao: UserDao,
    private val lobbyService: LobbyService,
    private val passwordEncoder: PasswordEncoder,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    private val logger = LoggerFactory.getLogger(DebugService::class.java)

    companion object {
        // Fixed credentials so callers always know which users own the debug game
        private val PLAYER_USERNAMES = listOf("debug_player1", "debug_player2", "debug_player3", "debug_player4")
        // Dummy players used to fill an existing lobby (skips debug_player1 who is the seed host)
        private val FILL_USERNAMES = listOf("debug_player2", "debug_player3", "debug_player4")
        private const val DEBUG_PASSWORD = "debug_password"
    }

    fun seed(): DebugSeedResponse {
        val playerCreds = PLAYER_USERNAMES.map { ensureUser(it) }

        val game = lobbyService.createLobby(playerCreds.first().userId)
        playerCreds.forEach { lobbyService.addUserToLobby(game.id, it.userId) }

        // Start without host check so the service call is self-contained
        val gameState = lobbyService.startGame(game.id)
        logger.info("Debug game {} seeded with {} players", game.id, playerCreds.size)

        return DebugSeedResponse(
            gameState = gameState,
            players = playerCreds,
        )
    }

    /**
     * Adds up to 3 dummy players to an existing lobby identified by [lobbyCode].
     * Broadcasts a LOBBY_JOINED WebSocket message for each added player so the
     * real client's lobby screen updates in real time.
     */
    fun fillLobby(lobbyCode: String): List<LoginResponse> {
        val game = lobbyService.validateLobbyCode(lobbyCode)
        val added = mutableListOf<LoginResponse>()

        for (username in FILL_USERNAMES) {
            val creds = ensureUser(username)
            try {
                val player = lobbyService.addUserToLobby(game.id, creds.userId)
                // Broadcast so the real client's handleLobbyJoined picks it up
                messagingTemplate.convertAndSend(
                    "/topic/public",
                    WebSocketMessage(
                        type = MessageType.LOBBY_JOINED,
                        sender = "SERVER",
                        content = "Player joined lobby",
                        gameId = player.gameId,
                        payload = mapOf(
                            "playerId" to player.id,
                            "userId" to player.userId,
                            "username" to username,
                            "gameId" to player.gameId,
                            "coins" to player.coins,
                        )
                    )
                )
                added.add(creds)
                logger.info("Added dummy '{}' to game {}", username, game.id)
            } catch (e: LobbyFullException) {
                logger.info("Lobby {} is full, stopping dummy fill", game.id)
                break
            } catch (e: Exception) {
                // Player already in this lobby — skip silently
                logger.debug("Skipped dummy '{}': {}", username, e.message)
            }
        }
        return added
    }

    // Creates the user if absent, then issues a fresh session token
    private fun ensureUser(username: String): LoginResponse {
        val existing = userDao.findByUsername(username)
        val userId = if (existing != null) {
            existing.id
        } else {
            val hash = passwordEncoder.encode(DEBUG_PASSWORD)
            userDao.create(username, hash).also {
                logger.info("Created debug user '{}'", username)
            }
        }

        val token = UUID.randomUUID().toString()
        userDao.updateSessionToken(userId, token)
        return LoginResponse(sessionToken = token, username = username, userId = userId)
    }
}