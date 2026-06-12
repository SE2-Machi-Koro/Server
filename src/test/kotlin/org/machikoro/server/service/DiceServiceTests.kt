package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerLandmarkModel
import org.machikoro.server.dto.RollDicePayload
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.exception.CustomWebSocketException
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiceServiceTests {

    private val gameDao = mock<GameDao>()
    private val playerLandmarkDao = mock<PlayerLandmarkDao>()
    private val gameStateGuard = mock<GameStateGuard>()
    private val diceService = DiceService(gameDao, playerLandmarkDao, gameStateGuard)

    @BeforeEach
    fun permitFirstRecordedRoll() {
        whenever(gameDao.tryRecordDiceRoll(any(), any())).thenReturn(true)
    }

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
        maxPlayers = 4,
        rerolledThisTurn = false
    )

    @Test
    fun rollDiceShouldReturnSingleDieValueBetween1And6() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1)
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(1, result.result.size)
        assert(result.total in 1..6)
        assertEquals(result.result.sum(), result.total)
    }

    @Test
    fun rollDiceShouldThrowWhenGameNotFound() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenThrow(GameNotFoundException("Game 1 not found"))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertThrows(GameNotFoundException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        verify(gameDao, never()).tryRecordDiceRoll(any(), any())
    }

    @Test
    fun rollDiceShouldThrowWhenGameIsFinished() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenThrow(CustomWebSocketException("GAME_FINISHED", "Game 1 has already ended"))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        assertEquals("GAME_FINISHED", ex.errorCode)
        verify(gameDao, never()).tryRecordDiceRoll(any(), any())
    }

    @Test
    fun rollDiceShouldThrowWhenWrongPhase() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.BUY_OR_BUILD))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        assertEquals("ROLL_ALREADY_COMPLETED", ex.errorCode)
    }

    @Test
    fun rollDiceShouldThrowWhenRollPhaseAlreadyHasStoredRoll() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(lastDiceRoll = 4))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }

        assertEquals("ROLL_ALREADY_COMPLETED", ex.errorCode)
        verify(gameDao, never()).tryRecordDiceRoll(any(), any())
    }

    @Test
    fun rollDiceShouldRejectDuplicateAfterCompletedRoll() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame)
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 4))

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val first = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(true, first.completed)
        assertEquals(TurnPhase.RESOLVE_EFFECTS, first.turnPhase)
        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        assertEquals("ROLL_ALREADY_COMPLETED", ex.errorCode)
    }

    @Test
    fun rollDiceShouldSerializeConcurrentRequestsForSameGame() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenAnswer {
            firstEntered.countDown()
            releaseFirst.await(1, TimeUnit.SECONDS)
            defaultGame
        }.thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 5))

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable { diceService.rollDice(request, rollingPlayerId = 2) })
            firstEntered.await(1, TimeUnit.SECONDS)
            val second = executor.submit(Callable {
                assertThrows(CustomWebSocketException::class.java) {
                    diceService.rollDice(request, rollingPlayerId = 2)
                }.errorCode
            })
            releaseFirst.countDown()

            assertEquals(true, first.get(1, TimeUnit.SECONDS).completed)
            assertEquals("ROLL_ALREADY_COMPLETED", second.get(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun rollDiceShouldThrowWhenRollTwoDiceWithoutTrainStation() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = false))

        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
    }

    @Test
    fun rollTwoDiceShouldReturnTwoDiceValuesBetween2And12WhenTrainStationOwned() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))

        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(2, result.result.size)
        assert(result.total in 2..12)
        assertEquals(result.result.sum(), result.total)
    }

    @Test
    fun rollTwoDiceShouldUseNestedLegacyDiceCountWhenTrainStationOwned() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))

        val request = RollDiceRequest(gameId = 1, payload = RollDicePayload(gameId = 1, diceCount = 2))
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(2, result.result.size)
        assert(result.total in 2..12)
        assertEquals(result.result.sum(), result.total)
    }

    @Test
    fun rollTwoDiceShouldUseTopLevelLegacyDiceCountWhenTrainStationOwned() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))

        val request = RollDiceRequest(gameId = 1, diceCount = 2)
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(2, result.result.size)
        assert(result.total in 2..12)
        assertEquals(result.result.sum(), result.total)
    }

    @Test
    fun rollTwoDiceShouldUseNestedLegacyRollTwoDiceWhenTrainStationOwned() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))

        val request = RollDiceRequest(gameId = 1, payload = RollDicePayload(gameId = 1, rollTwoDice = true))
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(2, result.result.size)
        assert(result.total in 2..12)
        assertEquals(result.result.sum(), result.total)
    }

    @Test
    fun rollDiceShouldRejectWhenAtomicRecordFails() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(gameDao.tryRecordDiceRoll(any(), any())).thenReturn(false)

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }

        assertEquals("ROLL_ALREADY_COMPLETED", ex.errorCode)
        verify(gameDao).tryRecordDiceRoll(any(), any())
    }

    @Test
    fun rollDiceShouldSaveResultInGameState() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        verify(gameDao).tryRecordDiceRoll(1, result.total)
        assertEquals(true, result.completed)
        assertEquals(TurnPhase.RESOLVE_EFFECTS, result.turnPhase)
    }

    @Test
    fun rollDiceShouldAdvancePhaseToResolveEffects() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        verify(gameDao).tryRecordDiceRoll(any(), any())
    }

    @Test
    fun rollDiceShouldNotSaveResultWhenWrongPhase() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.BUY_OR_BUILD))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        verify(gameDao, never()).tryRecordDiceRoll(any(), any())
    }

    // === NEW TESTS ===

    @Test
    fun rollDiceShouldRejectDiceCountZero() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, diceCount = 0)
        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        assertEquals("INVALID_DICE_COUNT", ex.errorCode)
    }

    @Test
    fun rollDiceShouldRejectDiceCountThree() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, diceCount = 3)
        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        assertEquals("INVALID_DICE_COUNT", ex.errorCode)
    }

    @Test
    fun rollDiceShouldRejectNegativeDiceCount() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, diceCount = -1)
        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        assertEquals("INVALID_DICE_COUNT", ex.errorCode)
    }

    @Test
    fun rollDiceShouldRejectDiceCountTwoWithoutTrainStation() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = false))

        val request = RollDiceRequest(gameId = 1, diceCount = 2)
        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rollDice(request, rollingPlayerId = 2)
        }
        assertEquals("NO_TRAIN_STATION", ex.errorCode)
    }

    @Test
    fun rollDiceShouldNotCheckTrainStationWhenDiceCountIsOne() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, diceCount = 1)
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(1, result.result.size)
        verify(playerLandmarkDao, never()).findByPlayerIdAndType(any(), any())
    }

    @Test
    fun rollDiceShouldPreferDiceCountOverRollTwoDice() {
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)

        val request = RollDiceRequest(gameId = 1, diceCount = 1, rollTwoDice = true)
        val result = diceService.rollDice(request, rollingPlayerId = 2)

        assertEquals(1, result.result.size)
        verify(playerLandmarkDao, never()).findByPlayerIdAndType(any(), any())
    }

    @Test
    fun rollTwoDiceDoublesShouldGrantExtraTurnWhenAmusementParkBuilt() {
        // Create a DiceService subclass to deterministically return doubles
        val diceServiceWithDoubles = object : DiceService(gameDao, playerLandmarkDao, gameStateGuard) {
            // make this method protected/open in production so tests may override it
            public override fun rollDice(count: Int): List<Int> = listOf(4, 4)
        }

        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        // Player has Amusement Park built
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.AMUSEMENT_PARK))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.AMUSEMENT_PARK, isBuilt = true))
        // Player has built Train Station
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))

        // Simulate DB granting extra turn
        whenever(gameDao.markExtraTurnIfEligible(1, 2, defaultGame.roundNumber)).thenReturn(true)

        val request = RollDiceRequest(gameId = 1, rollTwoDice = true)
        val result = diceServiceWithDoubles.rollDice(request, rollingPlayerId = 2)

        assertEquals(listOf(4, 4), result.result)
        assertEquals(8, result.total)
        assertEquals(true, result.extraTurnGranted)
        verify(gameDao).markExtraTurnIfEligible(1, 2, defaultGame.roundNumber)
    }

    @Test
    fun rollTwoDiceDoublesShouldNotGrantWhenNoAmusementPark() {
        val diceServiceWithDoubles = object : DiceService(gameDao, playerLandmarkDao, gameStateGuard) {
            public override fun rollDice(count: Int): List<Int> = listOf(2, 2)
        }

        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        // Player does NOT have Amusement Park built
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.AMUSEMENT_PARK))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.AMUSEMENT_PARK, isBuilt = false))
        // Player has built Train Station
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))


        val request = RollDiceRequest(gameId = 1, rollTwoDice = true)
        val result = diceServiceWithDoubles.rollDice(request, rollingPlayerId = 2)

        assertEquals(listOf(2, 2), result.result)
        assertEquals(4, result.total)
        assertEquals(false, result.extraTurnGranted)
        verify(gameDao, never()).markExtraTurnIfEligible(any(), any(), any())
    }

    @Test
    fun rollTwoDiceDoublesShouldNotGrantIfAlreadyGrantedThisRound() {
        val diceServiceWithDoubles = object : DiceService(gameDao, playerLandmarkDao, gameStateGuard) {
            public override fun rollDice(count: Int): List<Int> = listOf(6, 6)
        }

        whenever(gameStateGuard.ensureGameIsRunning(1)).thenReturn(defaultGame)
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.AMUSEMENT_PARK))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.AMUSEMENT_PARK, isBuilt = true))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))


        // Already granted (DB returns false when trying to mark again)
        whenever(gameDao.markExtraTurnIfEligible(1, 2, defaultGame.roundNumber)).thenReturn(false)

        val request = RollDiceRequest(gameId = 1, rollTwoDice = true)
        val result = diceServiceWithDoubles.rollDice(request, rollingPlayerId = 2)

        assertEquals(listOf(6, 6), result.result)
        assertEquals(12, result.total)
        // markExtraTurnIfEligible returned false indicating no new grant
        assertEquals(false, result.extraTurnGranted)
        verify(gameDao).markExtraTurnIfEligible(1, 2, defaultGame.roundNumber)
    }
    fun rerollDiceShouldThrowWhenGameNotFound() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenThrow(GameNotFoundException("Game 1 not found"))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        assertThrows(GameNotFoundException::class.java) {
            diceService.rerollDice(request, rollingPlayerId = 2)
        }
        verify(gameDao, never()).tryRerollThisTurn(any(), any())
    }

    @Test
    fun rerollDiceShouldThrowWhenNotInResolveEffectsPhase() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.ROLL_DICE))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rerollDice(request, rollingPlayerId = 2)
        }
        assertEquals("REROLL_NOT_ALLOWED", ex.errorCode)
        verify(gameDao, never()).tryRerollThisTurn(any(), any())
    }

    @Test
    fun rerollDiceShouldThrowWhenNoInitialRollYet() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = null))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rerollDice(request, rollingPlayerId = 2)
        }
        assertEquals("REROLL_NOT_ALLOWED", ex.errorCode)
    }

    @Test
    fun rerollDiceShouldThrowWhenPlayerHasNoRadioTower() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 4))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.RADIO_TOWER, isBuilt = false))

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rerollDice(request, rollingPlayerId = 2)
        }
        assertEquals("NO_RADIO_TOWER", ex.errorCode)
        verify(gameDao, never()).tryRerollThisTurn(any(), any())
    }

    @Test
    fun rerollDiceShouldThrowWhenPlayerLandmarkNotFound() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 4))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(null)

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rerollDice(request, rollingPlayerId = 2)
        }
        assertEquals("NO_RADIO_TOWER", ex.errorCode)
    }

    @Test
    fun rerollDiceShouldThrowWhenRerollAlreadyUsedThisTurn() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 4))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.RADIO_TOWER, isBuilt = true))
        whenever(gameDao.tryRerollThisTurn(eq(1), anyInt()))
            .thenReturn(false)

        val request = RollDiceRequest(gameId = 1, playerId = 2)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rerollDice(request, rollingPlayerId = 2)
        }
        assertEquals("REROLL_ALREADY_USED", ex.errorCode)
        verify(gameDao).tryRerollThisTurn(eq(1), anyInt())
    }

    @Test
    fun rerollDiceShouldSucceedWithSingleDieWhenRadioTowerBuilt() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 4))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.RADIO_TOWER, isBuilt = true))
        whenever(gameDao.tryRerollThisTurn(eq(1), anyInt())).thenReturn(true)

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val result = diceService.rerollDice(request, rollingPlayerId = 2)

        assertEquals(1, result.result.size)
        assert(result.total in 1..6)
        assertEquals(result.result.sum(), result.total)
        verify(gameDao).tryRerollThisTurn(eq(1), anyInt())
    }

    @Test
    fun rerollDiceShouldSucceedWithTwoDiceWhenRadioTowerAndTrainStationBuilt() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 5))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.RADIO_TOWER, isBuilt = true))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = true))
        whenever(gameDao.tryRerollThisTurn(eq(1), anyInt())).thenReturn(true)

        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)
        val result = diceService.rerollDice(request, rollingPlayerId = 2)

        assertEquals(2, result.result.size)
        assert(result.total in 2..12)
        assertEquals(result.result.sum(), result.total)
        verify(gameDao).tryRerollThisTurn(eq(1), anyInt())
    }

    @Test
    fun rerollDiceShouldThrowWhenTryingToRollTwoDiceWithoutTrainStation() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 4))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.RADIO_TOWER, isBuilt = true))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.TRAIN_STATION))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.TRAIN_STATION, isBuilt = false))

        whenever(gameDao.tryRerollThisTurn(eq(1), anyInt())).thenReturn(true)
        val request = RollDiceRequest(gameId = 1, playerId = 2, rollTwoDice = true)

        val ex = assertThrows(CustomWebSocketException::class.java) {
            diceService.rerollDice(request, rollingPlayerId = 2)
        }
        assertEquals("NO_TRAIN_STATION", ex.errorCode)
        verify(gameDao, never()).tryRerollThisTurn(eq(1), anyInt())
    }

    @Test
    fun rerollDiceShouldKeepPhaseAtResolveEffects() {
        whenever(gameStateGuard.ensureGameIsRunning(1))
            .thenReturn(defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 3))
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.RADIO_TOWER, isBuilt = true))
        whenever(gameDao.tryRerollThisTurn(eq(1), anyInt())).thenReturn(true)

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        diceService.rerollDice(request, rollingPlayerId = 2)
        verify(gameDao).tryRerollThisTurn(eq(1), anyInt())
        assertEquals(TurnPhase.RESOLVE_EFFECTS, gameStateGuard.ensureGameIsRunning(1).turnPhase)
    }

    @Test
    fun rerollDiceShouldSerializeConcurrentRerollsForSameGame() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        whenever(gameStateGuard.ensureGameIsRunning(1)).thenAnswer {
            firstEntered.countDown()
            releaseFirst.await(1, TimeUnit.SECONDS)
            defaultGame.copy(turnPhase = TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = 4)
        }
        whenever(playerLandmarkDao.findByPlayerIdAndType(2, LandmarkType.RADIO_TOWER))
            .thenReturn(PlayerLandmarkModel(playerId = 2, landmarkType = LandmarkType.RADIO_TOWER, isBuilt = true))
        whenever(gameDao.tryRerollThisTurn(eq(1), anyInt()))
            .thenReturn(true)
            .thenReturn(false)

        val request = RollDiceRequest(gameId = 1, playerId = 2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit(Callable { diceService.rerollDice(request, rollingPlayerId = 2) })
            firstEntered.await(1, TimeUnit.SECONDS)
            val second = executor.submit(Callable {
                assertThrows(CustomWebSocketException::class.java) {
                    diceService.rerollDice(request, rollingPlayerId = 2)
                }.errorCode
            })
            releaseFirst.countDown()

            assertEquals(1, first.get(1, TimeUnit.SECONDS).result.size)
            assertEquals("REROLL_ALREADY_USED", second.get(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

}