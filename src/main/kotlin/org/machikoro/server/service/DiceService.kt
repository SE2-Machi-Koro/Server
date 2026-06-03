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

    fun rollDice(request: RollDiceRequest, rollingPlayerId: Int): RollDiceResponse = synchronized(rollLocks.computeIfAbsent(request.gameId) { Any() }) {
        val game = gameStateGuard.ensureGameIsRunning(request.gameId)

        if (game.turnPhase != TurnPhase.ROLL_DICE || game.lastDiceRoll != null) {
            throw CustomWebSocketException("ROLL_ALREADY_COMPLETED", "Dice have already been rolled for this turn")
        }

        val requestedDiceCount = request.diceCount ?: request.payload?.diceCount ?: 1
        if (requestedDiceCount !in 1..2) {
            throw CustomWebSocketException("INVALID_DICE_COUNT", "diceCount must be 1 or 2")
        }

        val rollTwoDice = request.rollTwoDice ||
                requestedDiceCount == TWO_DICE_COUNT ||
                request.payload?.rollTwoDice == true

        if (rollTwoDice) {
            val hasTrainStation = playerLandmarkDao
                .findByPlayerIdAndType(rollingPlayerId, LandmarkType.TRAIN_STATION)
                ?.isBuilt ?: false

            if (!hasTrainStation) {
                throw CustomWebSocketException("NO_TRAIN_STATION", "You need a Train Station to roll two dice!")
            }
        }

        val dice = if (rollTwoDice) {
            listOf((1..6).random(), (1..6).random())
        } else {
            listOf((1..6).random())
        }
        val total = dice.sum()

        if (!gameDao.tryRecordDiceRoll(request.gameId, total)) {
            throw CustomWebSocketException("ROLL_ALREADY_COMPLETED", "Dice have already been rolled for this turn")
        }

        return RollDiceResponse(dice = dice, total = total)
    }

    private companion object {
        const val TWO_DICE_COUNT = 2
    }
}