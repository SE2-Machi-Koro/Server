package org.machikoro.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.GameModel
import org.machikoro.server.domain.enums.GameStatus
import org.machikoro.server.exception.GameNotFoundException
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EarningsServiceImplTest {

    private val playerDao = mock<PlayerDao>()
    private val playerCardDao = mock<PlayerCardDao>()
    private val cardDao = mock<CardDao>()
    private val gameDao = mock<GameDao>()

    private val service = EarningsServiceImpl(
        playerDao = playerDao,
        playerCardDao = playerCardDao,
        cardDao = cardDao,
        gameDao = gameDao,
    )

    private fun game(phase: TurnPhase, lastDiceRoll: Int?) = GameModel(
        id = 1,
        status = GameStatus.IN_PROGRESS,
        hostUserId = 1,
        lobbyCode = "ABC123",
        maxPlayers = 4,
        currentTurnIndex = 0,
        turnPhase = phase,
        lastDiceRoll = lastDiceRoll,
        hasPurchasedThisTurn = false,
        roundNumber = 1,
    )

    // computeEarnings

    @Test
    fun `zero cards returns zero`() {
        assertEquals(0, service.computeEarnings(emptyList()))
    }

    @Test
    fun `single card returns quantity times income`() {
        assertEquals(6, service.computeEarnings(listOf(2 to 3)))
    }

    @Test
    fun `multiple card types sums correctly`() {
        assertEquals(11, service.computeEarnings(listOf(2 to 3, 1 to 5)))
    }

    @Test
    fun `multiple quantities sums correctly`() {
        assertEquals(20, service.computeEarnings(listOf(3 to 4, 2 to 4)))
    }

    // resolveEffects guard branches

    @Test
    fun `resolveEffects throws GameNotFoundException when game does not exist`() {
        whenever(gameDao.findById(99)).thenReturn(null)

        assertThrows<GameNotFoundException> {
            service.resolveEffects(99)
        }
    }

    @Test
    fun `resolveEffects throws IllegalStateException when phase is not RESOLVE_EFFECTS`() {
        whenever(gameDao.findById(1)).thenReturn(game(TurnPhase.BUY_OR_BUILD, lastDiceRoll = 3))

        assertThrows<IllegalStateException> {
            service.resolveEffects(1)
        }
    }

    @Test
    fun `resolveEffects throws IllegalStateException when lastDiceRoll is null`() {
        whenever(gameDao.findById(1)).thenReturn(game(TurnPhase.RESOLVE_EFFECTS, lastDiceRoll = null))

        assertThrows<IllegalStateException> {
            service.resolveEffects(1)
        }
    }
}