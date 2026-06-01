package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.GameMarketplaceDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.dao.UserDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import kotlin.test.assertTrue

class GamePhaseServiceTest {

    private val gameDao = mock<GameDao>()
    private val userDao = mock<UserDao>()
    private val playerDao = mock<PlayerDao>()
    private val playerCardDao = mock<PlayerCardDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameMarketplaceDao = mock<GameMarketplaceDao>()
    private val gameStateGuard = mock<GameStateGuard>()
    private val winConditionService = mock<WinConditionService>()
    private val transactionRunner = object : GameTransactionRunner {
        override fun <T> inTransaction(action: () -> T): T = action()
    }

    private val service = GamePhaseService(
        gameDao,
        playerDao,
        userDao,
        playerCardDao,
        playerLandmarkDao,
        gameMarketplaceDao,
        gameStateGuard,
        winConditionService,
        transactionRunner,
    )

    private fun gameInPhase(
        id: Int,
        phase: TurnPhase,
        currentTurnIndex: Int = 0,
        roundNumber: Int = 1
    ) = GameModel(
        id = id,
        status = GameStatus.IN_PROGRESS,
        hostUserId = 1,
        lobbyCode = "ABC1234",
        maxPlayers = 4,
        currentTurnIndex = currentTurnIndex,
        turnPhase = phase,
        lastDiceRoll = null,
        hasPurchasedThisTurn = false,
        roundNumber = roundNumber,
    )

    @Test
    fun `endTurn rejects when another request has already completed the transition`() {
        val gameId = 42
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.BUY_OR_BUILD))
        whenever(gameDao.tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN))
            .thenReturn(false)

        val ex = assertThrows<CustomWebSocketException> { service.endTurn(gameId) }

        assertEquals("TURN_ALREADY_ENDED", ex.errorCode)
        verify(gameDao).tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN)
        verify(gameDao, never()).advanceTurn(any(), any(), any())
    }

    @Test
    fun `end_turn wraps END_TURN back to ROLL_DICE in EndTurnOutcome`() {
        val gameId = 7
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.BUY_OR_BUILD))
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(mock<PlayerModel>()))
        whenever(winConditionService.detectWinner(gameId))
            .thenReturn(null)
        whenever(gameDao.tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN))
            .thenReturn(true)

        val result = service.endTurn(gameId)
        assertTrue(result is EndTurnOutcome.Continue)
        assertEquals(TurnPhase.ROLL_DICE, result.nextPhase)
    }

    @Test
    fun `endTurn updates phase and rotates to next player`() {
        val gameId = 21
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.BUY_OR_BUILD, currentTurnIndex = 0, roundNumber = 1))
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(
                PlayerModel(1, gameId, 10, 0, 3, lastSeenAt = 30),
                PlayerModel(2, gameId, 11, 1, 3, lastSeenAt = 30),
            )
        )
        whenever(gameDao.tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN))
            .thenReturn(true)

        val result = service.endTurn(gameId)

        assertTrue(result is EndTurnOutcome.Continue)
        assertEquals(
            TurnPhase.ROLL_DICE,
            (result).nextPhase
        )

        val ordered = inOrder(gameStateGuard, gameDao)
        ordered.verify(gameStateGuard).ensureGameIsRunning(gameId)
        ordered.verify(gameDao).tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN)
        ordered.verify(gameDao).advanceTurn(gameId, 1, 1)
    }

    @Test
    fun `endTurn wraps back to first player and increments round`() {
        val gameId = 22
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.BUY_OR_BUILD, currentTurnIndex = 1, roundNumber = 3))
        whenever(playerDao.getPlayers(gameId)).thenReturn(
            listOf(
                PlayerModel(1, gameId, 10, 0, 3, lastSeenAt = 30),
                PlayerModel(2, gameId, 11, 1, 3, lastSeenAt = 30),
            )
        )
        whenever(gameDao.tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN))
            .thenReturn(true)

        val result = service.endTurn(gameId)

        assertTrue(result is EndTurnOutcome.Continue)
        assertEquals(
            TurnPhase.ROLL_DICE,
            (result).nextPhase
        )

        verify(gameDao).tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN)
        verify(gameDao).advanceTurn(gameId, 0, 4)
    }

    @Test
    fun `endTurn detects winner and finishes game`() {
        val gameId = 30
        val userId = 10
        val id = 1
        val roundsPlayed = 10
        val winner = PlayerModel(id, gameId, userId, 0, 3, 30)

        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.BUY_OR_BUILD, roundNumber = roundsPlayed ))
        whenever(winConditionService.detectWinner(gameId))
            .thenReturn(winner)
        whenever(playerDao.getPlayers(gameId))
            .thenReturn(listOf(winner))
        whenever(gameDao.tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN))
            .thenReturn(true)

        val result = service.endTurn(gameId)

        assertTrue(result is EndTurnOutcome.Won)
        assertEquals(
            id,
            (result).winnerId
        )
        assertEquals(
            roundsPlayed,
            (result).roundsPlayed
        )

        verify(gameDao).tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN)
        verify(userDao).incrementWins(userId)
        verify(gameDao).updateStatus(gameId, GameStatus.FINISHED)
    }

    @Test
    fun `finishGameWithWinner records stats for all players, finishes game, and returns Won`() {
        val gameId = 30
        val roundNumber = 7
        val winner = PlayerModel(id = 1, gameId = gameId, userId = 10, turnOrder = 0, coins = 3, lastSeenAt = null)
        val loser = PlayerModel(id = 2, gameId = gameId, userId = 20, turnOrder = 1, coins = 5, lastSeenAt = null)
        whenever(playerDao.getPlayers(gameId)).thenReturn(listOf(winner, loser))

        val result = service.finishGameWithWinner(gameId, winner, roundNumber)

        assertEquals(EndTurnOutcome.Won(winnerId = 1, roundsPlayed = roundNumber), result)
        verify(userDao).incrementWins(10)
        verify(userDao).incrementGamesPlayed(10)
        verify(userDao).incrementGamesPlayed(20)
        verify(gameDao).updateStatus(gameId, GameStatus.FINISHED)
    }

    @Test
    fun `endTurn rejects games outside buy or build phase`() {
        val gameId = 23
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenReturn(gameInPhase(gameId, TurnPhase.RESOLVE_EFFECTS))

        val ex = assertThrows<CustomWebSocketException> {
            service.endTurn(gameId)
        }
        assertEquals("INVALID_TURN_PHASE", ex.errorCode)

        verify(gameDao, never()).tryTransitionPhase(gameId, TurnPhase.BUY_OR_BUILD, TurnPhase.END_TURN)
        verify(gameDao, never()).advanceTurn(any(), any(), any())
        verifyNoMoreInteractions(playerDao)
    }

    @Test
    fun `endTurn rejects FINISHED games and does not advance`() {
        val gameId = 88
        whenever(gameStateGuard.ensureGameIsRunning(gameId))
            .thenThrow(CustomWebSocketException("GAME_FINISHED", "Game $gameId has already ended"))

        val ex = assertThrows<CustomWebSocketException> {
            service.endTurn(gameId)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)

        verify(gameDao, never()).tryTransitionPhase(any(), any(), any())
        verify(gameDao, never()).advanceTurn(any(), any(), any())
        verifyNoMoreInteractions(playerDao)
    }

    @Test
    fun `cleanupFinishedGameData deletes all finished game data in order`() {
        val gameId = 42

        val players = listOf(
            PlayerModel(1, gameId, 10, 0, 3, lastSeenAt = 30),
            PlayerModel(2, gameId, 11, 1, 3, lastSeenAt = 30),
        )

        whenever(playerDao.getPlayers(gameId))
            .thenReturn(players)

        service.cleanupFinishedGameData(gameId)

        val ordered = inOrder(
            gameStateGuard,
            gameMarketplaceDao,
            playerCardDao,
            playerLandmarkDao,
            playerDao,
            gameDao
        )

        ordered.verify(gameStateGuard).ensureGameIsFinished(gameId)
        ordered.verify(gameMarketplaceDao).deleteAllForGame(gameId)

        ordered.verify(playerCardDao).deleteAllByPlayerId(1)
        ordered.verify(playerLandmarkDao).deleteAllByPlayerId(1)

        ordered.verify(playerCardDao).deleteAllByPlayerId(2)
        ordered.verify(playerLandmarkDao).deleteAllByPlayerId(2)

        ordered.verify(playerDao).deleteByGameId(gameId)
        ordered.verify(gameDao).delete(gameId)
    }
}
