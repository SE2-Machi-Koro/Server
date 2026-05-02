package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerCardModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GameSyncServiceTest {

    private val gameDao = mock<GameDao>()
    private val playerDao = mock<PlayerDao>()
    private val playerCardDao = mock<PlayerCardDao>()
    private val service = GameSyncService(gameDao, playerDao, playerCardDao)

    private fun game(id: Int, status: GameStatus) = GameModel(
        id = id,
        status = status,
        hostUserId = 1,
        lobbyCode = "ABC1234",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        roundNumber = 1,
        hasPurchasedThisTurn = false,
    )

    @Test
    fun `findActiveInProgressGameId delegates to PlayerDao`() {
        whenever(playerDao.findActiveGameIdByUserId(7)).thenReturn(99)

        assertEquals(99, service.findActiveInProgressGameId(7))

        verify(playerDao).findActiveGameIdByUserId(7)
    }

    @Test
    fun `buildSnapshot returns players cards and sorted turnOrder`() {
        val gameId = 10
        val p1 = PlayerModel(id = 1, gameId = gameId, userId = 11, turnOrder = 1, coins = 3, lastSeenAt = null)
        val p2 = PlayerModel(id = 2, gameId = gameId, userId = 22, turnOrder = 0, coins = 3, lastSeenAt = null)

        whenever(gameDao.findById(gameId)).thenReturn(game(gameId, GameStatus.IN_PROGRESS))
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(p1, p2))
        whenever(playerCardDao.findByPlayerId(1)).thenReturn(listOf(PlayerCardModel(1, CardType.BAKERY, 1)))
        whenever(playerCardDao.findByPlayerId(2)).thenReturn(emptyList())

        val snapshot = service.buildSnapshot(gameId)

        assertEquals(gameId, snapshot.game.id)
        assertEquals(listOf(p1, p2), snapshot.players)
        assertEquals(listOf(2, 1), snapshot.turnOrder)
        assertEquals(1, snapshot.playerCards[1]?.size)
        assertTrue(snapshot.playerCards[2].isNullOrEmpty())
    }

    @Test
    fun `buildSnapshot throws when game is missing`() {
        whenever(gameDao.findById(404)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            service.buildSnapshot(404)
        }
    }

    @Test
    fun `isInProgress returns true only for IN_PROGRESS games`() {
        whenever(gameDao.findById(1)).thenReturn(game(1, GameStatus.IN_PROGRESS))
        whenever(gameDao.findById(2)).thenReturn(game(2, GameStatus.WAITING))
        whenever(gameDao.findById(3)).thenReturn(null)

        assertTrue(service.isInProgress(1))
        assertFalse(service.isInProgress(2))
        assertFalse(service.isInProgress(3))
    }

    @Test
    fun `isUserInGame delegates to PlayerDao presence`() {
        whenever(playerDao.findByGameIdAndUserId(5, 50)).thenReturn(
            PlayerModel(id = 9, gameId = 5, userId = 50, turnOrder = 0, coins = 3, lastSeenAt = null)
        )
        whenever(playerDao.findByGameIdAndUserId(6, 50)).thenReturn(null)

        assertTrue(service.isUserInGame(50, 5))
        assertFalse(service.isUserInGame(50, 6))
    }
}

