package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DiceServiceTests {

    private val gameDao: GameDao = mock(GameDao::class.java)
    private val playerDao: PlayerDao = mock(PlayerDao::class.java)
    private val playerLandmarkDao: PlayerLandmarkDao = mock(PlayerLandmarkDao::class.java)
    private val diceService = DiceService(gameDao, playerDao, playerLandmarkDao)

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

    private val activePlayer = PlayerModel(
        id = 2,
        gameId = 1,
        userId = 1,
        turnOrder = 0,
        coins = 3
    )

    @Test
    fun rollDiceShouldReturnSingleDieValueBetween1And6() {
        `when`(gameDao.findById(1)).thenReturn(defaultGame)
        `when`(playerDao.getPlayers(1)).thenReturn(listOf(activePlayer))

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val result = diceService.rollDice(request)

        assertEquals(1, result.dice.size)
        assert(result.total in 1..6)
        assertEquals(result.dice.sum(), result.total)
    }

    @Test
    fun rollDiceShouldThrowWhenGameNotFound() {
        `when`(gameDao.findById(1)).thenReturn(null)

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request)
        }
    }

    @Test
    fun rollDiceShouldThrowWhenWrongPhase() {
        `when`(gameDao.findById(1)).thenReturn(defaultGame.copy(turnPhase = TurnPhase.BUY_OR_BUILD))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request)
        }
    }

    @Test
    fun rollDiceShouldThrowWhenNotActivePlayer() {
        `when`(gameDao.findById(1)).thenReturn(defaultGame)
        `when`(playerDao.getPlayers(1)).thenReturn(listOf(activePlayer))

        val request = RollDiceRequest(gameId = 1, playerId = 99)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request)
        }
    }

    @Test
    fun rollDiceShouldThrowWhenRollTwoDiceWithoutTrainStation() {
        `when`(gameDao.findById(1)).thenReturn(defaultGame)
        `when`(playerDao.getPlayers(1)).thenReturn(listOf(activePlayer))
        `when`(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = false))

        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request)
        }
    }

    @Test
    fun rollTwoDiceShouldReturnTwoDiceValuesBetween2And12WhenTrainStationOwned() {
        `when`(gameDao.findById(1)).thenReturn(defaultGame)
        `when`(playerDao.getPlayers(1)).thenReturn(listOf(activePlayer))
        `when`(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))

        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)
        val result = diceService.rollDice(request)

        assertEquals(2, result.dice.size)
        assert(result.total in 2..12)
        assertEquals(result.dice.sum(), result.total)
    }
}