package org.machikoro.server.service

import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.CheatFlagDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.dto.AccusationOutcome
import org.machikoro.server.exception.CustomWebSocketException
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Adjudicates the cheating-accusation mechanic (issue #361). The server is the
 * sole authority: it silently records cheat usage (self-reported by the active
 * player's client) and, when another player accuses, decides caught vs. wrong
 * and applies the coin penalty.
 *
 * Each accuser may accuse at most once per turn (anti-spam guardrail from
 * issue #361); further attempts in the same turn are rejected with
 * `INVALID_ACCUSATION`.
 */
@Service
class AccusationService(
    private val gameStateGuard: GameStateGuard,
    private val playerDao: PlayerDao,
    private val cheatFlagDao: CheatFlagDao,
    private val gameTransactionRunner: GameTransactionRunner,
) {

    /**
     * Last turn — (roundNumber, currentTurnIndex) — in which each accuser
     * (keyed by PlayerModel.id) made a *successful* accusation. Kept in memory
     * on purpose (issue #361 sanctions this): losing it on restart merely
     * re-opens at most one extra accusation for the in-flight turn. Entries are
     * a few bytes per player that ever accused, so no cleanup is needed.
     *
     * Only read inside the accuse transaction and only written after it
     * commits: the Exposed runner retries the whole lambda on transient
     * SQLExceptions and rolls it back on failure, so an in-transaction write
     * would burn the turn's slot on a rollback and reject its own retry.
     */
    private val lastAccusationTurn = ConcurrentHashMap<Int, Pair<Int, Int>>()

    /** Accuser userIds with an accuse call in flight — closes the double-submit race. */
    private val inFlightAccusers = ConcurrentHashMap.newKeySet<Int>()

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
     * goes negative) and the outcome reports the coins actually deducted. Runs in
     * a single transaction so the flag-consume and coin change are atomic; the
     * consume's affected-row count makes concurrent accusations against the same
     * cheater race-safe.
     *
     * @throws CustomWebSocketException `INVALID_ACCUSATION` for self-accusation,
     *   a non-member accuser/accused, or a second accusation by the same accuser
     *   in the same turn; `GAME_FINISHED` / `GAME_NOT_STARTED` when the game is
     *   not in progress.
     */
    fun accuse(gameId: Int, accuser: UserPrincipal, accusedPlayerId: Int): AccusationOutcome {
        if (!inFlightAccusers.add(accuser.userId)) {
            throw CustomWebSocketException(
                INVALID_ACCUSATION,
                "Your previous accusation is still being processed",
            )
        }
        try {
            var claim: Pair<Int, Pair<Int, Int>>? = null
            val outcome = gameTransactionRunner.inTransaction {
                val game = gameStateGuard.ensureGameIsRunning(gameId)

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

                // One accusation per accuser per turn (issue #361 anti-spam
                // guardrail). Read-only here — the slot is claimed only after
                // the transaction commits, so a rollback (or an Exposed retry
                // of this lambda) never burns the turn's slot. The in-flight
                // guard above keeps concurrent same-accuser calls out.
                val turnKey = game.roundNumber to game.currentTurnIndex
                if (lastAccusationTurn[accuserPlayer.id] == turnKey) {
                    throw CustomWebSocketException(
                        INVALID_ACCUSATION,
                        "You can only accuse once per turn",
                    )
                }
                claim = accuserPlayer.id to turnKey

                val caught = cheatFlagDao.consume(accused.id) > 0
                if (caught) {
                    val newCoins = maxOf(0, accused.coins - CHEATER_PENALTY)
                    playerDao.updateCoins(accused.id, newCoins)
                    AccusationOutcome(
                        accuserPlayerId = accuserPlayer.id,
                        accusedPlayerId = accused.id,
                        caught = true,
                        penalizedPlayerId = accused.id,
                        penaltyCoins = accused.coins - newCoins,
                    )
                } else {
                    val newCoins = maxOf(0, accuserPlayer.coins - WRONG_ACCUSER_PENALTY)
                    playerDao.updateCoins(accuserPlayer.id, newCoins)
                    AccusationOutcome(
                        accuserPlayerId = accuserPlayer.id,
                        accusedPlayerId = accused.id,
                        caught = false,
                        penalizedPlayerId = accuserPlayer.id,
                        penaltyCoins = accuserPlayer.coins - newCoins,
                    )
                }
            }
            claim?.let { (playerId, turnKey) -> lastAccusationTurn[playerId] = turnKey }
            return outcome
        } finally {
            inFlightAccusers.remove(accuser.userId)
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
