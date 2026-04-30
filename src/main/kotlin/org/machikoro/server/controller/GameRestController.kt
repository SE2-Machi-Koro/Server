package org.machikoro.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.dto.EndTurnRequest
import org.machikoro.server.dto.LeaveFinishedGameRequest
import org.machikoro.server.dto.PurchaseRequest
import org.machikoro.server.dto.ResolveEffectsRequest
import org.machikoro.server.dto.RollDiceRequest
import org.machikoro.server.dto.RollDiceResponse
import org.machikoro.server.service.DiceService
import org.machikoro.server.service.GamePhaseService
import org.machikoro.server.service.LeaveFinishedGameService
import org.machikoro.server.service.PurchaseResult
import org.machikoro.server.service.PurchaseService
import org.machikoro.server.service.WinConditionService
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HTTP companion for the WebSocket game actions exposed by [GameController] and
 * [EarningsController]. Each endpoint delegates to the same service method as its
 * STOMP counterpart so behaviour stays identical; broadcasting over
 * [org.springframework.messaging.simp.SimpMessagingTemplate] is intentionally
 * omitted because there is no subscribed STOMP session in an HTTP request.
 *
 * Intended for Swagger UI / curl / Postman exercising during development.
 * Disabled in the `prod` profile.
 *
 * If you add a new `@MessageMapping` to [GameController] or [EarningsController],
 * mirror it here so the dev surface stays in sync.
 */
@RestController
@RequestMapping("/api/dev/game")
@Tag(
    name = "WebSocket Companion (dev)",
    description = "HTTP mirror of /app/game.* STOMP actions for Swagger testing. Not for production use."
)
@Profile("!prod")
class GameRestController(
    private val purchaseService: PurchaseService,
    private val gamePhaseService: GamePhaseService,
    private val winConditionService: WinConditionService,
    private val leaveFinishedGameService: LeaveFinishedGameService,
    private val diceService: DiceService,
    private val earningsService: EarningsService,
) {

    @PostMapping("/purchase")
    @Operation(summary = "Mirror of /app/game.purchase")
    fun purchase(@RequestBody request: PurchaseRequest): ResponseEntity<Any> = runCatching {
        purchaseService.purchase(
            gameId = request.gameId,
            purchaseType = request.purchaseType,
            cardType = request.cardType,
            landmarkType = request.landmarkType,
        )
    }.fold(
        onSuccess = { ResponseEntity.ok<Any>(it) },
        onFailure = { ResponseEntity.badRequest().body(it.message) },
    )

    @PostMapping("/endTurn")
    @Operation(summary = "Mirror of /app/game.endTurn")
    fun endTurn(@RequestBody request: EndTurnRequest): ResponseEntity<Any> = runCatching {
        val winner = winConditionService.detectWinner(request.gameId)
        if (winner != null) {
            gamePhaseService.finishGame(request.gameId)
            EndTurnResponse(turnPhase = null, winnerId = winner.id)
        } else {
            val newPhase = gamePhaseService.endTurn(request.gameId)
            EndTurnResponse(turnPhase = newPhase, winnerId = null)
        }
    }.fold(
        onSuccess = { ResponseEntity.ok<Any>(it) },
        onFailure = { ResponseEntity.badRequest().body(it.message) },
    )

    @PostMapping("/leave")
    @Operation(summary = "Mirror of /app/game.leave")
    fun leave(@RequestBody request: LeaveFinishedGameRequest): ResponseEntity<Any> = runCatching {
        leaveFinishedGameService.leaveFinishedGame(request.gameId, request.playerId)
    }.fold(
        onSuccess = { ResponseEntity.ok().build<Any>() },
        onFailure = { ResponseEntity.badRequest().body(it.message) },
    )

    @PostMapping("/rollDice")
    @Operation(summary = "Mirror of /app/game.rollDice")
    fun rollDice(@RequestBody request: RollDiceRequest): ResponseEntity<Any> = runCatching {
        diceService.rollDice(request)
    }.fold(
        onSuccess = { ResponseEntity.ok<Any>(it) },
        onFailure = { ResponseEntity.badRequest().body(it.message) },
    )

    @PostMapping("/resolveEffects")
    @Operation(summary = "Mirror of /app/game.resolveEffects")
    fun resolveEffects(@RequestBody request: ResolveEffectsRequest): ResponseEntity<Any> = runCatching {
        earningsService.resolveEffects(request.gameId)
    }.fold(
        onSuccess = { ResponseEntity.ok().build<Any>() },
        onFailure = { ResponseEntity.badRequest().body(it.message) },
    )
}

data class EndTurnResponse(
    val turnPhase: TurnPhase?,
    val winnerId: Int?,
)
