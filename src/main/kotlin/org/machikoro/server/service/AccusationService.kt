package org.machikoro.server.service

import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.CheatFlagDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dto.AccusationOutcome
import org.machikoro.server.dto.AccusationOutcomeType
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.stereotype.Service

/**
 * Adjudicates the cheating-accusation mechanic (issue #361). The server is the
 * sole authority: it silently records cheat usage (self-reported by the active
 * player's client) and, when another player accuses, decides caught vs. wrong
 * and applies the coin penalty.
 *
 * No explicit rate limit: a wrong accusation costs the accuser a coin, so spam
 * accusing is self-punishing.
 */
@Service
class AccusationService(
    private val gameStateGuard: GameStateGuard,
    private val playerDao: PlayerDao,
    private val cheatFlagDao: CheatFlagDao,
    private val gameTransactionRunner: GameTransactionRunner,
) {

    /**
     * Records that the active player used the Insider Trading cheat this game.
     * Only the active player on their own turn may report — enforced via
     * [GameStateGuard.ensureSenderIsActivePlayer] so a client can only ever
     * incriminate itself (no framing). Silent: no broadcast.
     */
    fun reportCheat(gameId: Int, reporter: UserPrincipal) {
        val active = gameStateGuard.ensureSenderIsActivePlayer(gameId, reporter)
        cheatFlagDao.setOutstanding(active.id)
    }

    /**
     * [accuser] accuses player [accusedPlayerId] of cheating. Caught (accused had
     * an outstanding cheat) → cheater loses [CHEATER_PENALTY]; wrong → accuser
     * loses [WRONG_ACCUSER_PENALTY]. Coins are clamped at 0 (the economy never
     * goes negative). Runs in a single transaction so the flag-consume and coin
     * change are atomic; the consume's affected-row count makes concurrent
     * accusations against the same cheater race-safe.
     *
     * @throws CustomWebSocketException `INVALID_ACCUSATION` for self-accusation or
     *   a non-member accuser/accused; `GAME_FINISHED` / `GAME_NOT_STARTED` when
     *   the game is not in progress.
     */
    fun accuse(gameId: Int, accuser: UserPrincipal, accusedPlayerId: Int): AccusationOutcome =
        gameTransactionRunner.inTransaction {
            gameStateGuard.ensureGameIsRunning(gameId)

            val accuserPlayer = playerDao.findByGameIdAndUserId(gameId, accuser.userId)
                ?: throw CustomWebSocketException(
                    INVALID_ACCUSATION,
                    "You are not a player in game $gameId",
                )

            val accused = playerDao.findById(accusedPlayerId)
            if (accused == null || accused.gameId != gameId) {
                throw CustomWebSocketException(
                    INVALID_ACCUSATION,
                    "Player $accusedPlayerId is not in game $gameId",
                )
            }
            if (accused.id == accuserPlayer.id) {
                throw CustomWebSocketException(
                    INVALID_ACCUSATION,
                    "You cannot accuse yourself",
                )
            }

            val caught = cheatFlagDao.consume(accused.id) > 0
            if (caught) {
                playerDao.updateCoins(accused.id, maxOf(0, accused.coins - CHEATER_PENALTY))
                AccusationOutcome(
                    outcome = AccusationOutcomeType.CAUGHT,
                    accuserPlayerId = accuserPlayer.id,
                    accusedPlayerId = accused.id,
                    penalizedPlayerId = accused.id,
                )
            } else {
                playerDao.updateCoins(accuserPlayer.id, maxOf(0, accuserPlayer.coins - WRONG_ACCUSER_PENALTY))
                AccusationOutcome(
                    outcome = AccusationOutcomeType.WRONG,
                    accuserPlayerId = accuserPlayer.id,
                    accusedPlayerId = accused.id,
                    penalizedPlayerId = accuserPlayer.id,
                )
            }
        }

    companion object {
        /** Stable error code returned for a rejected accusation. */
        private const val INVALID_ACCUSATION = "INVALID_ACCUSATION"

        /** Coins a caught cheater loses. */
        const val CHEATER_PENALTY = 2

        /** Coins a wrong accuser loses (the lost bet). */
        const val WRONG_ACCUSER_PENALTY = 1
    }
}
