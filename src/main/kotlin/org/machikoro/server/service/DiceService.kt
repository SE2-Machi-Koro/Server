package org.machikoro.server.service

import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dao.PlayerLandmarkDao
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.stereotype.Service

@Service
class DiceService(
    private val gameDao: GameDao,
    private val playerDao: PlayerDao,
    private val playerLandmarkDao: PlayerLandmarkDao
) {
    /**
     * Validates and processes a dice roll request.
     * - Checks if the game exists
     * - Checks if it's the correct turn phase (ROLL_DICE)
     * - Checks if the requesting player is the active player
     * - Checks if player owns Train Station before allowing two dice
     * - Rolls one or two dice and persists the result
     */
    fun rollDice(request: RollDiceRequest): Int {
        val game = gameDao.findById(request.gameId)
            ?: throw CustomWebSocketException("GAME_NOT_FOUND", "Game ${request.gameId} not found")

        if (game.turnPhase != TurnPhase.ROLL_DICE) {
            throw CustomWebSocketException("WRONG_PHASE", "It's not the roll dice phase")
        }

        val activePlayer = playerDao.findActivePlayer(request.gameId, game.currentTurnIndex)
            ?: throw CustomWebSocketException("PLAYER_NOT_FOUND", "Active player not found")

        if (activePlayer.id != request.playerId) {
            throw CustomWebSocketException("NOT_YOUR_TURN", "It's not your turn!")
        }

        if (request.rollTwoDice) {
            val hasTrainStation = playerLandmarkDao
                .findByPlayerIdAndType(request.playerId, LandmarkType.TRAIN_STATION)
                ?.isBuilt ?: false

            if (!hasTrainStation) {
                throw CustomWebSocketException("NO_TRAIN_STATION", "You need a Train Station to roll two dice!")
            }
        }

        val diceRoll = if (request.rollTwoDice) {
            (1..6).random() + (1..6).random()
        } else {
            (1..6).random()
        }

        gameDao.updateAfterRoll(request.gameId, diceRoll, TurnPhase.RESOLVE_EFFECTS)

        return diceRoll
    }
}