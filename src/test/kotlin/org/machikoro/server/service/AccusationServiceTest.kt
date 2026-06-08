package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.dao.CheatFlagDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.AccusationOutcomeType
import org.machikoro.server.exception.CustomWebSocketException
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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

    private fun player(id: Int, userId: Int, coins: Int, gameId: Int = GAME) =
        PlayerModel(id = id, gameId = gameId, userId = userId, turnOrder = 0, coins = coins, lastSeenAt = null)

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

        assertEquals(AccusationOutcomeType.CAUGHT, outcome.outcome)
        assertEquals(20, outcome.penalizedPlayerId)
        assertEquals(10, outcome.accuserPlayerId) // accuser's PlayerModel.id, not userId
        verify(cheatFlagDao).consume(20)
        verify(playerDao).updateCoins(20, 2) // 4 - 2
        verify(playerDao, never()).updateCoins(eq(10), any())
    }

    @Test
    fun `accuse caught clamps cheater coins at 0`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 1))
        whenever(cheatFlagDao.consume(20)).thenReturn(1)

        val outcome = service.accuse(GAME, accuser, 20)

        assertEquals(AccusationOutcomeType.CAUGHT, outcome.outcome)
        verify(playerDao).updateCoins(20, 0) // max(0, 1 - 2)
    }

    // ── accuse: wrong ─────────────────────────────────────────────────────────

    @Test
    fun `accuse wrong deducts 1 from accuser and leaves the accused untouched`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 5))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 4))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        val outcome = service.accuse(GAME, accuser, 20)

        assertEquals(AccusationOutcomeType.WRONG, outcome.outcome)
        assertEquals(10, outcome.penalizedPlayerId)
        verify(playerDao).updateCoins(10, 4) // 5 - 1
        verify(playerDao, never()).updateCoins(eq(20), any())
    }

    @Test
    fun `accuse wrong clamps accuser coins at 0`() {
        whenever(playerDao.findByGameIdAndUserId(GAME, 1)).thenReturn(player(10, 1, 0))
        whenever(playerDao.findById(20)).thenReturn(player(20, 2, 3))
        whenever(cheatFlagDao.consume(20)).thenReturn(0)

        service.accuse(GAME, accuser, 20)

        verify(playerDao).updateCoins(10, 0) // max(0, 0 - 1)
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
