package org.machikoro.server.controller

import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.service.GamePhaseService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Controller

@Controller
class GameController(
    private val gamePhaseService: GamePhaseService,
) {
    private val logger = LoggerFactory.getLogger(GameController::class.java)

    @MessageMapping("/game.advancePhase")
    fun advancePhase(@Payload request: AdvancePhaseRequest) {
        val newPhase = gamePhaseService.advancePhase(request.gameId)
        logger.info("Advanced game ${request.gameId} to phase $newPhase")
    }
}
