package org.machikoro.server.controller

import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.domain.models.PlayerModel
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.WinConditionService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

@Controller
class GameController(
    private val gamePhaseService: GamePhaseService,
    private val messagingTemplate: SimpMessagingTemplate,
    private val winConditionService: WinConditionService
) {
    private val logger = LoggerFactory.getLogger(GameController::class.java)

    @MessageMapping("/game.advancePhase")
    fun advancePhase(@Payload request: AdvancePhaseRequest) {
        val newPhase = gamePhaseService.advancePhase(request.gameId)
        logger.info("Advanced game ${request.gameId} to phase $newPhase")
        broadcastPhase(newPhase)
    }

    @MessageMapping("/game.endTurn")
    fun endTurn(@Payload request: EndTurnRequest) {
        val winner = winConditionService.detectWinner(request.gameId)

        if (winner != null) {
            logger.info("Game ${request.gameId} has ended. Winner: ${winner.id}")

            broadcastWinner(winner)
            return
        }
        val newPhase = gamePhaseService.endTurn(request.gameId)
        logger.info("Ended turn for game ${request.gameId}, new phase $newPhase")
        broadcastPhase(newPhase)
    }

    private fun broadcastPhase(newPhase: TurnPhase) {
        messagingTemplate.convertAndSend(
            "/topic/public",
            WebSocketMessage(
                type = MessageType.GAME_ACTION,
                sender = "server",
                payload = mapOf("turnPhase" to newPhase.name),
            ),
        )
    }

    private fun broadcastWinner(winner: PlayerModel) {
        messagingTemplate.convertAndSend(
            "/topic/public",
            WebSocketMessage(
                type = MessageType.GAME_END,
                sender = "server",
                payload = mapOf("winnerId" to winner.id)
            )
        )
    }
}
