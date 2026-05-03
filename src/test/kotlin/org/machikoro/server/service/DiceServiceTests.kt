package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DiceServiceTests {

    private val gameDao = mock<GameDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameStateGuard = mock<GameStateGuard>()
    private val diceService = DiceService(gameDao, playerLandmarkDao, gameStateGuard)

    private val defaultGame = GameModel(
        id = 1,
        status = GameStatus.IN_PROGRESS,
        hostUserId = 1,
        currentTurnIndex = 0,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        roundNumber = 1,
        lobbyCode = "ABC123",
        hasPurchasedThisTurn = false,
        maxPlayers = 4
    )

    @Test
    fun rollDiceShouldReturnSingleDieValueBetween1And6() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val result = diceService.rollDice(request)

        assertEquals(1, result.dice.size)
        assert(result.total in 1..6)
        assertEquals(result.dice.sum(), result.total)
    }

    @Test
    fun rollDiceShouldThrowWhenGameNotFound() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenThrow(GameNotFoundException("Game 1 not found"))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertThrows(GameNotFoundException::class.java) {
            diceService.rollDice(request)
        }
        verify(gameDao, never()).updateAfterRoll(any(), any(), any())
    }

    @Test
    fun rollDiceShouldThrowWhenGameIsFinished() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenThrow(CustomWebSocketException("GAME_FINISHED", "Game 1 has already ended"))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)
        verify(gameDao, never()).updateAfterRoll(any(), any(), any())
    }

    @Test
    fun rollDiceShouldThrowWhenWrongPhase() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.BUY_OR_BUILD))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request)
        }
    }

    @Test
    fun rollDiceShouldThrowWhenRollTwoDiceWithoutTrainStation() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = false))

        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request)
        }
    }

    @Test
    fun rollTwoDiceShouldReturnTwoDiceValuesBetween2And12WhenTrainStationOwned() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))

        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)
        val result = diceService.rollDice(request)

        assertEquals(2, result.dice.size)
        assert(result.total in 2..12)
        assertEquals(result.dice.sum(), result.total)
    }
}
