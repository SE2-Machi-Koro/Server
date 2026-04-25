package org.machikoro.server.controller

import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.AdvancePhaseRequest
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.WebSocketMessage
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

@Controller
class GameController(
    private val gamePhaseService: GamePhaseService,
    private val purchaseService: PurchaseService,
    private val messagingTemplate: SimpMessagingTemplate,
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
        val newPhase = gamePhaseService.endTurn(request.gameId)
        logger.info("Ended turn for game ${request.gameId}, new phase $newPhase")
        broadcastPhase(newPhase)
    }

    @MessageMapping("/game.purchase")
    fun purchase(@Payload request: PurchaseRequest) {
        val result = purchaseService.purchase(
            gameId = request.gameId,
            purchaseType = request.purchaseType,
            cardType = request.cardType,
            landmarkType = request.landmarkType,
        )
        logger.info("Processed {} purchase for game {}", result.purchaseType, request.gameId)
        broadcastPurchase(result)
    }

    private fun broadcastPhase(newPhase: TurnPhase) {
        broadcastGameAction(mapOf("turnPhase" to newPhase.name))
    }

    private fun broadcastPurchase(result: PurchaseResult) {
        val payload = linkedMapOf<String, String>(
            "turnPhase" to result.turnPhase.name,
            "purchaseType" to result.purchaseType.name,
        )
        result.cardType?.let { payload["cardType"] = it.name }
        result.landmarkType?.let { payload["landmarkType"] = it.name }
        broadcastGameAction(payload)
    }

    private fun broadcastGameAction(payload: Any) {
        messagingTemplate.convertAndSend(
            "/topic/public",
            WebSocketMessage(
                type = MessageType.GAME_ACTION,
                sender = "server",
                payload = payload,
            ),
        )
    }
}
