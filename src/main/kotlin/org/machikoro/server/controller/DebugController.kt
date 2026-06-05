package org.machikoro.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.machikoro.server.dto.DebugSeedResponse
import org.machikoro.server.dto.EndGameRequest
import org.machikoro.server.dto.EndTurnOutcome
import org.machikoro.server.dto.FillLobbyRequest
import org.machikoro.server.dto.LoginResponse
import org.machikoro.server.service.DebugService
import org.machikoro.server.service.GameEndBroadcaster
import org.machikoro.server.service.GamePhaseService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Only active when debug.enabled=true — omit from production deployments
@RestController
@RequestMapping("/debug")
@Tag(name = "Debug", description = "Development-only helpers for seeding test data")
@ConditionalOnProperty(name = ["debug.enabled"], havingValue = "true")
class DebugController(
    private val debugService: DebugService,
    private val gameEndBroadcaster: GameEndBroadcaster,
    private val gamePhaseService: GamePhaseService,
) {
    private val logger = LoggerFactory.getLogger(DebugController::class.java)

    @PostMapping("/seed")
    @Operation(summary = "Seed a ready-to-play debug game with four dummy players and return their session tokens")
    fun seed(@RequestHeader("Authorization", required = false) authHeader: String?): ResponseEntity<DebugSeedResponse> {
        debugService.validateAdmin(authHeader)
        val result = debugService.seed()
        logger.info("Debug seed completed for game {}", result.gameState.game.id)
        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/purge")
    @Operation(summary = "Delete all games and players from the database — requires admin session token")
    fun purge(@RequestHeader("Authorization", required = false) authHeader: String?): ResponseEntity<Map<String, Int>> {
        debugService.validateAdmin(authHeader)
        val deleted = debugService.purgeGames()
        logger.info("Debug purge deleted {} games", deleted)
        return ResponseEntity.ok(mapOf("deletedGames" to deleted))
    }

    @PostMapping("/fill-lobby")
    @Operation(summary = "Fill an existing lobby with dummy players (up to 3) and broadcast LOBBY_JOINED events")
    fun fillLobby(
        @RequestHeader("Authorization", required = false) authHeader: String?,
        @RequestBody request: FillLobbyRequest,
    ): ResponseEntity<List<LoginResponse>> {
        debugService.validateAdmin(authHeader)
        val added = debugService.fillLobby(request.lobbyCode)
        logger.info("Filled lobby '{}' with {} dummy players", request.lobbyCode, added.size)
        return ResponseEntity.ok(added)
    }

    @PostMapping("/reset-lobby")
    @Operation(summary = "Remove all dummy players from a lobby and broadcast LOBBY_LEFT events")
    fun resetLobby(
        @RequestHeader("Authorization", required = false) authHeader: String?,
        @RequestBody request: FillLobbyRequest,
    ): ResponseEntity<Map<String, Int>> {
        debugService.validateAdmin(authHeader)
        val removed = debugService.resetLobby(request.lobbyCode)
        logger.info("Reset lobby '{}': removed {} dummy players", request.lobbyCode, removed)
        return ResponseEntity.ok(mapOf("removedPlayers" to removed))
    }

    @PostMapping("/end-game")
    @Operation(summary = "Force the current game to end with the caller as winner — admin only")
    fun endGame(
        @RequestHeader("Authorization", required = false) authHeader: String?,
        @RequestBody request: EndGameRequest,
    ): ResponseEntity<EndTurnOutcome.Won> {
        val caller = debugService.validateAdmin(authHeader)
        val outcome = debugService.endGame(request.gameId, caller)
        // Broadcast GAME_END then clean up post-commit, mirroring GameController.endTurn's win handling.
        gameEndBroadcaster.broadcast(request.gameId, outcome.winnerId, outcome.roundsPlayed)
        gamePhaseService.cleanupFinishedGameData(request.gameId)
        logger.info("Debug end-game succeeded for game {}, winner player {}", request.gameId, outcome.winnerId)
        return ResponseEntity.ok(outcome)
    }
}
