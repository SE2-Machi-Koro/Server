package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.CheatFlagDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AccusationServiceTest {

    private val gameStateGuard = mock<GameStateGuard>()
    private val playerDao = mock<PlayerDao>()
    private val cheatFlagDao = mock<CheatFlagDao>()

    // Fake runner that simply executes the lambda (the real one wraps it in an
    // Exposed transaction). This keeps the service unit-testable without a DB.
    private val transactionRunner = object : GameTransactionRunner {
        override fun <T> inTransaction(action: () -> T): T = action()
    }

    private val service = AccusationService(gameStateGuard, playerDao, cheatFlagDao, transactionRunner)

    private val accuser = UserPrincipal(userId = 1, username = "alice")

    init {
        // The service reads roundNumber/currentTurnIndex from the running game
        // for the one-accusation-per-turn guardrail. Individual tests re-stub
        // to advance the turn or to make the guard throw.
        whenever(gameStateGuard.ensureGameIsRunning(GAME)).thenReturn(game())
    }

    private fun player(id: Int, userId: Int, coins: Int, gameId: Int = GAME) =
        PlayerModel(id = id, gameId = gameId, userId = userId, turnOrder = 0, coins = coins, lastSeenAt = null)

    private fun game(roundNumber: Int = 1, currentTurnIndex: Int = 0) = GameModel(
        id = GAME,
        status = GameStatus.IN_PROGRESS,
        hostUserId = 1,
        lobbyCode = "ABC1234",
        maxPlayers = 4,
        currentTurnIndex = currentTurnIndex,
        turnPhase = TurnPhase.ROLL_DICE,
        lastDiceRoll = null,
        roundNumber = roundNumber,
        hasPurchasedThisTurn = false,
        rerolledThisTurn = false
    )

    // ── reportCheat ───────────────────────────────────────────────────────────

    @Test
    fun `reportCheat flags the active player`() {
        whenever(gameStateGuard.ensureSenderIsActivePlayer(GAME, accuser)).thenReturn(player(10, 1, 3))

        service.reportCheat(GAME, accuser)

        verify(cheatFlagDao).setOutstanding(10)
    }

    @Test
    fun `reportCheat propagates guard rejection and sets no flag`() {
        whenever(gameStateGuard.ensureSenderIsActivePlayer(GAME, accuser))
            .thenThrow(CustomWebSocketException("NOT_YOUR_TURN", "It is not your turn"))

        assertThrows<CustomWebSocketException> { service.reportCheat(GAME, accuser) }
        verify(cheatFlagDao, never()).setOutstanding(any())
    }

    // ── accuse: caught ────────────────────────────────────────────────────────

    @Test
    fun `accuse caught deducts 2 from cheater and consumes the flag`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(1)

        val outcome = service.accuse(GAME, accuser, 20)

        assertTrue(outcome.caught)
        assertEquals(20, outcome.penalizedPlayerId)
        assertEquals(10, outcome.accuserPlayerId) // accuser's PlayerModel.id, not userId
        assertEquals(2, outcome.penaltyCoins)
        verify(cheatFlagDao).consume(20)
        verify(playerDao).updateCoins(20, 2) // 4 - 2
        verify(playerDao, never()).updateCoins(eq(10), any())
    }

    @Test
    fun `accuse caught clamps cheater coins at 0 and reports actual deduction`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 1))
        whenever(cheatFlagDao.consume(20)).thenReturn(1)

        val outcome = service.accuse(GAME, accuser, 20)

        assertTrue(outcome.caught)
        assertEquals(1, outcome.penaltyCoins) // only 1 coin existed to take
        verify(playerDao).updateCoins(20, 0) // max(0, 1 - 2)
    }

    // ── accuse: wrong ─────────────────────────────────────────────────────────

    @Test
    fun `accuse wrong deducts 1 from accuser and leaves the accused untouched`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        val outcome = service.accuse(GAME, accuser, 20)

        assertFalse(outcome.caught)
        assertEquals(10, outcome.penalizedPlayerId)
        assertEquals(1, outcome.penaltyCoins)
        verify(playerDao).updateCoins(10, 4) // 5 - 1
        verify(playerDao, never()).updateCoins(eq(20), any())
    }

    @Test
    fun `accuse wrong clamps accuser coins at 0 and reports zero deduction`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 0))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 3))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        val outcome = service.accuse(GAME, accuser, 20)

        assertEquals(0, outcome.penaltyCoins) // nothing left to take
        verify(playerDao).updateCoins(10, 0) // max(0, 0 - 1)
    }

    // ── accuse: one per accuser per turn ──────────────────────────────────────

    @Test
    fun `second accusation by the same accuser in the same turn is rejected`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        service.accuse(GAME, accuser, 20)
        val ex = assertThrows<CustomWebSocketException> { service.accuse(GAME, accuser, 20) }

        assertEquals("INVALID_ACCUSATION", ex.errorCode)
        // Only the first accusation adjudicated and penalized.
        verify(cheatFlagDao, times(1)).consume(any())
        verify(playerDao, times(1)).updateCoins(any(), any())
    }

    @Test
    fun `accusation is allowed again when the turn advances`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        service.accuse(GAME, accuser, 20)
        whenever(gameStateGuard.ensureGameIsRunning(GAME)).thenReturn(game(currentTurnIndex = 1))
        service.accuse(GAME, accuser, 20)

        verify(playerDao, times(2)).updateCoins(any(), any())
    }

    @Test
    fun `accusation is allowed again in a new round`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        service.accuse(GAME, accuser, 20)
        whenever(gameStateGuard.ensureGameIsRunning(GAME)).thenReturn(game(roundNumber = 2))
        service.accuse(GAME, accuser, 20)

        verify(playerDao, times(2)).updateCoins(any(), any())
    }

    @Test
    fun `different accusers may each accuse once in the same turn`() {
        val otherAccuser = UserPrincipal(userId = 3, username = "carol")
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findByGameIdAndUserId(GAME, 3)).thenReturn(player(30, 3, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        service.accuse(GAME, accuser, 20)
        service.accuse(GAME, otherAccuser, 20)

        verify(playerDao).updateCoins(10, 4)
        verify(playerDao).updateCoins(30, 4)
    }

    @Test
    fun `failed adjudication does not consume the turn slot`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)
        // First adjudication dies mid-transaction (e.g. transient DB failure);
        // the rolled-back attempt must not burn the turn's single slot.
        whenever(playerDao.updateCoins(10, 4))
            .thenThrow(RuntimeException("transient DB failure"))
            .then { }

        assertThrows<RuntimeException> { service.accuse(GAME, accuser, 20) }
        val outcome = service.accuse(GAME, accuser, 20) // same turn — must still be allowed

        assertFalse(outcome.caught)
        verify(playerDao, times(2)).updateCoins(10, 4)
    }

    @Test
    fun `rejected accusation does not consume the turn slot`() {
        val me = player(10, 1, 5)
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(me)
        whenever(playerDao.findById(10)).thenReturn(me)
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        // Self-accusation is rejected, then a valid accusation in the same turn
        // must still be allowed.
        assertThrows<CustomWebSocketException> { service.accuse(GAME, accuser, 10) }
        val outcome = service.accuse(GAME, accuser, 20)

        assertFalse(outcome.caught)
        verify(playerDao, times(1)).updateCoins(any(), any())
    }

    // ── accuse: validation ────────────────────────────────────────────────────

    @Test
    fun `accuse self is rejected with INVALID_ACCUSATION`() {
        val me = player(10, 1, 5)
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(me)
        whenever(playerDao.findById(10)).thenReturn(me)

        val ex = assertThrows<CustomWebSocketException> { service.accuse(GAME, accuser, 10) }

        assertEquals("INVALID_ACCUSATION", ex.errorCode)
        verify(cheatFlagDao, never()).consume(any())
        verify(playerDao, never()).updateCoins(any(), any())
    }

    @Test
    fun `accuse by a non-member is rejected`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(null)

        val ex = assertThrows<CustomWebSocketException> { service.accuse(GAME, accuser, 20) }

        assertEquals("INVALID_ACCUSATION", ex.errorCode)
        verify(playerDao, never()).updateCoins(any(), any())
    }

    @Test
    fun `accuse of a non-existent player is rejected`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(null)

        val ex = assertThrows<CustomWebSocketException> { service.accuse(GAME, accuser, 20) }

        assertEquals("INVALID_ACCUSATION", ex.errorCode)
    }

    @Test
    fun `accuse of a player in another game is rejected`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4, gameId = 999))

        val ex = assertThrows<CustomWebSocketException> { service.accuse(GAME, accuser, 20) }

        assertEquals("INVALID_ACCUSATION", ex.errorCode)
        verify(cheatFlagDao, never()).consume(any())
    }

    @Test
    fun `accuse rejects when the game is not running`() {
        whenever(gameStateGuard.ensureGameIsRunning(GAME))
            .thenThrow(CustomWebSocketException("GAME_FINISHED", "Game $GAME has already ended"))

        val ex = assertThrows<CustomWebSocketException> { service.accuse(GAME, accuser, 20) }

        assertEquals("GAME_FINISHED", ex.errorCode)
        verify(playerDao, never()).updateCoins(any(), any())
    }

    private companion object {
        const val GAME = 7
    }
}
