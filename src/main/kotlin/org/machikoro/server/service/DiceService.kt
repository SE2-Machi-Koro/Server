package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class DiceService(
    private val gameDao: GameDao,
    private val playerLandmarkDao: PlayerLandmarkDao,
    private val gameStateGuard: GameStateGuard,
) {
    private val rollLocks = ConcurrentHashMap<Int, Any>()

    fun rollDice(request: RollDiceRequest, rollingPlayerId: Int): RollDiceResponse =
        synchronized(rollLocks.computeIfAbsent(request.gameId) { Any() }) {
            val game = gameStateGuard.ensureGameIsRunning(request.gameId)

            if (game.turnPhase != TurnPhase.ROLL_DICE || game.lastDiceRoll != null) {
                throw CustomWebSocketException("ROLL_ALREADY_COMPLETED", "Dice have already been rolled for this turn")
            }

            val diceCount = resolveDiceCount(request)
            validateDiceCount(diceCount)

            if (diceCount == TWO_DICE_COUNT) {
                requireTrainStation(rollingPlayerId)
            }

            val dice = rollDice(diceCount)
            val total = dice.sum()

            if (!gameDao.tryRecordDiceRoll(request.gameId, total)) {
                throw CustomWebSocketException("ROLL_ALREADY_COMPLETED", "Dice have already been rolled for this turn")
            }

            RollDiceResponse(result = dice, total = total)
        }

    fun rerollDice(request: RollDiceRequest, rollingPlayerId: Int): RollDiceResponse =
        synchronized(rollLocks.computeIfAbsent(request.gameId) { Any() }) {
            val game = gameStateGuard.ensureGameIsRunning(request.gameId)

            // Reroll allowed only while resolving effects (after initial roll)
            if (game.turnPhase != TurnPhase.RESOLVE_EFFECTS || game.lastDiceRoll == null) {
                throw CustomWebSocketException("REROLL_NOT_ALLOWED", "You can only reroll after rolling and while effects are resolving")
            }

            // Ensure player has built Radio Tower
            val hasRadioTower = playerLandmarkDao
                .findByPlayerIdAndType(rollingPlayerId, LandmarkType.RADIO_TOWER)
                ?.isBuilt ?: false

            if (!hasRadioTower) {
                throw CustomWebSocketException("NO_RADIO_TOWER", "You need a built Radio Tower to reroll")
            }

            // Determine dice count from request (same resolver used for initial roll)
            val diceCount = resolveDiceCount(request)
            validateDiceCount(diceCount)

            if (diceCount == TWO_DICE_COUNT) {
                requireTrainStation(rollingPlayerId)
            }

            // Atomically mark that this turn consumed the reroll
            if (!gameDao.tryMarkRerolledThisTurn(request.gameId)) {
                throw CustomWebSocketException("REROLL_ALREADY_USED", "Reroll has already been used this turn")
            }

            val dice = rollDice(diceCount)
            val total = dice.sum()

            // Persist the new dice roll and keep phase at RESOLVE_EFFECTS (still resolving)
            gameDao.updateAfterRoll(request.gameId, total, TurnPhase.RESOLVE_EFFECTS)

            RollDiceResponse(result = dice, total = total)
        }

    private fun resolveDiceCount(request: RollDiceRequest): Int {
        if (request.diceCount != null) return request.diceCount
        if (request.payload?.diceCount != null) return request.payload.diceCount
        if (request.rollTwoDice || request.payload?.rollTwoDice == true) return TWO_DICE_COUNT
        return ONE_DICE_COUNT
    }

    private fun validateDiceCount(diceCount: Int) {
        if (diceCount !in 1..2) {
            throw CustomWebSocketException("INVALID_DICE_COUNT", "diceCount must be 1 or 2")
        }
    }

    private fun requireTrainStation(playerId: Int) {
        val hasTrainStation = playerLandmarkDao
            .findByPlayerIdAndType(playerId, LandmarkType.TRAIN_STATION)
            ?.isBuilt ?: false

        if (!hasTrainStation) {
            throw CustomWebSocketException("NO_TRAIN_STATION", "You need a Train Station to roll two dice!")
        }
    }

    private fun rollDice(count: Int): List<Int> = List(count) { (1..6).random() }

    private companion object {
        const val ONE_DICE_COUNT = 1
        const val TWO_DICE_COUNT = 2
    }
}