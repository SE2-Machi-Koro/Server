package org.machikoro.server.controller

import org.junit.jupiter.api.Test
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.service.GamePhaseService
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class GameControllerTest {

    private val gamePhaseService = mock<GamePhaseService>()
    private val controller = GameController(gamePhaseService)

    @Test
    fun `advancePhase delegates to service with the requested game id`() {
        val gameId = 42
        whenever(gamePhaseService.advancePhase(gameId)).thenReturn(TurnPhase.RESOLVE_EFFECTS)

        controller.advancePhase(AdvancePhaseRequest(gameId))

        verify(gamePhaseService).advancePhase(gameId)
    }
}
