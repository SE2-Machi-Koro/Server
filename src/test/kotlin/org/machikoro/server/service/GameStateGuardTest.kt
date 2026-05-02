package org.machikoro.server.service

import java.security.Principal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GameStateGuardTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val guard = GameStateGuard(gameDao, playerDao)

    private fun game(
        id: Int,
        status: GameStatus,
        currentTurnIndex: Int = 0,
    ) = GameModel(
        id = id,
        status = status,
        hostUserId = 1,
        lobbyCode = "ABC1234",
        maxPlayers = 4,
        currentTurnIndex = currentTurnIndex,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        roundNumber = 1,
        hasPurchasedThisTurn = false,
    )

    private fun player(id: Int, userId: Int, gameId: Int = 1, turnOrder: Int = 0) = PlayerModel(
        id = id,
        gameId = gameId,
        userId = userId,
        turnOrder = turnOrder,
        coins = 3,
        lastSeenAt = null,
    )

    @Test
    fun `ensureGameIsRunning returns the loaded game when status is IN_PROGRESS`() {
        val gameId = 1
        val loaded = game(gameId, GameStatus.IN_PROGRESS)
        whenever(gameDao.findById(gameId)).thenReturn(loaded)

        assertSame(loaded, guard.ensureGameIsRunning(gameId))
    }

    @Test
    fun `ensureGameIsRunning returns the loaded game when status is WAITING`() {
        val gameId = 1
        val loaded = game(gameId, GameStatus.WAITING)
        whenever(gameDao.findById(gameId)).thenReturn(loaded)

        assertSame(loaded, guard.ensureGameIsRunning(gameId))
    }

    @Test
    fun `ensureGameIsRunning throws GAME_FINISHED when status is FINISHED`() {
        val gameId = 42
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.FINISHED))

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureGameIsRunning(gameId)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)
    }

    @Test
    fun `ensureGameIsRunning throws GameNotFoundException when game does not exist`() {
        val gameId = 99
        whenever(gameDao.findById(gameId)).thenReturn(null)

        assertThrows(GameNotFoundException::class.java) {
            guard.ensureGameIsRunning(gameId)
        }
    }

    @Test
    fun `ensureGameIsFinished does nothing if game is finished`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(
            GameModel(
                id = gameId,
                status = GameStatus.FINISHED,
                hostUserId = 1,
                currentTurnIndex = 0,
                turnPhase = TurnPhase.ROLL_DICE,
                lastDiceRoll = null,
                roundNumber = 1,
                lobbyCode = "",
                hasPurchasedThisTurn = false,
                maxPlayers = 2
            )
        )

        guard.ensureGameIsFinished(gameId)
    }

    @Test
    fun `ensureGameIsFinished throws if game is not finished`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(
            GameModel(
                id = gameId,
                status = GameStatus.IN_PROGRESS,
                hostUserId = 1,
                currentTurnIndex = 0,
                turnPhase = TurnPhase.ROLL_DICE,
                lastDiceRoll = null,
                roundNumber = 1,
                lobbyCode = "",
                hasPurchasedThisTurn = false,
                maxPlayers = 2
            )
        )

        assertThrows<CustomWebSocketException> {
            guard.ensureGameIsFinished(gameId)
        }
    }

    @Test
    fun `ensureGameIsFinished throws if game not found`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            guard.ensureGameIsFinished(gameId)
        }
    }

    // ── ensureSenderIsActivePlayer ────────────────────────────────────────────

    @Test
    fun `ensureSenderIsActivePlayer returns the principal when caller is the active player`() {
        val gameId = 1
        whenever(gameDao.findById(gameId))
            .thenReturn(game(gameId, GameStatus.IN_PROGRESS, currentTurnIndex = 0))
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(id = 10, userId = 7), player(id = 11, userId = 8, turnOrder = 1)),
        )
        val principal = UserPrincipal(userId = 7, username = "alice")

        val result = guard.ensureSenderIsActivePlayer(gameId, principal)

        assertEquals(principal, result)
    }

    @Test
    fun `ensureSenderIsActivePlayer throws NOT_YOUR_TURN when caller is not the active player`() {
        val gameId = 1
        whenever(gameDao.findById(gameId))
            .thenReturn(game(gameId, GameStatus.IN_PROGRESS, currentTurnIndex = 0))
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(id = 10, userId = 7), player(id = 11, userId = 8, turnOrder = 1)),
        )
        val principal = UserPrincipal(userId = 8, username = "bob")

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderIsActivePlayer(gameId, principal)
        }
        assertEquals("NOT_YOUR_TURN", ex.errorCode)
    }

    @Test
    fun `ensureSenderIsActivePlayer throws UNAUTHENTICATED when principal is not a UserPrincipal`() {
        val gameId = 1
        val foreignPrincipal = Principal { "anon" }

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderIsActivePlayer(gameId, foreignPrincipal)
        }
        assertEquals("UNAUTHENTICATED", ex.errorCode)
    }

    @Test
    fun `ensureSenderIsActivePlayer throws UNAUTHENTICATED when principal is null`() {
        val gameId = 1

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderIsActivePlayer(gameId, null)
        }
        assertEquals("UNAUTHENTICATED", ex.errorCode)
    }

    @Test
    fun `ensureSenderIsActivePlayer throws NO_ACTIVE_PLAYER when currentTurnIndex is out of range`() {
        val gameId = 1
        whenever(gameDao.findById(gameId))
            .thenReturn(game(gameId, GameStatus.IN_PROGRESS, currentTurnIndex = 5))
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(player(id = 10, userId = 7)))
        val principal = UserPrincipal(userId = 7, username = "alice")

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderIsActivePlayer(gameId, principal)
        }
        assertEquals("NO_ACTIVE_PLAYER", ex.errorCode)
    }

    @Test
    fun `ensureSenderIsActivePlayer propagates GAME_FINISHED when game has ended`() {
        val gameId = 1
        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.FINISHED))
        val principal = UserPrincipal(userId = 7, username = "alice")

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderIsActivePlayer(gameId, principal)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)
    }

    // ── ensureSenderOwnsPlayer ────────────────────────────────────────────────

    @Test
    fun `ensureSenderOwnsPlayer returns the principal when caller owns the player`() {
        val gameId = 1
        val playerId = 10
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(id = playerId, userId = 7), player(id = 11, userId = 8, turnOrder = 1)),
        )
        val principal = UserPrincipal(userId = 7, username = "alice")

        val result = guard.ensureSenderOwnsPlayer(gameId, playerId, principal)

        assertEquals(principal, result)
    }

    @Test
    fun `ensureSenderOwnsPlayer throws NOT_YOUR_PLAYER when player belongs to a different user`() {
        val gameId = 1
        val playerId = 10
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(id = playerId, userId = 7), player(id = 11, userId = 8, turnOrder = 1)),
        )
        val principal = UserPrincipal(userId = 8, username = "bob")

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderOwnsPlayer(gameId, playerId, principal)
        }
        assertEquals("NOT_YOUR_PLAYER", ex.errorCode)
    }

    @Test
    fun `ensureSenderOwnsPlayer throws PLAYER_NOT_IN_GAME when playerId is not part of this game`() {
        val gameId = 1
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(player(id = 11, userId = 8, turnOrder = 1)),
        )
        val principal = UserPrincipal(userId = 7, username = "alice")

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderOwnsPlayer(gameId, playerId = 99, principal)
        }
        assertEquals("PLAYER_NOT_IN_GAME", ex.errorCode)
    }

    @Test
    fun `ensureSenderOwnsPlayer throws UNAUTHENTICATED when principal is not a UserPrincipal`() {
        val gameId = 1
        val foreignPrincipal = Principal { "anon" }

        val ex = assertThrows(CustomWebSocketException::class.java) {
            guard.ensureSenderOwnsPlayer(gameId, playerId = 10, foreignPrincipal)
        }
        assertEquals("UNAUTHENTICATED", ex.errorCode)
    }
}
