package org.machikoro.server.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.machikoro.server.dto.DebugSeedResponse
import org.machikoro.server.dto.FillLobbyRequest
import org.machikoro.server.dto.LoginResponse
import org.machikoro.server.service.DebugService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Only active when debug.enabled=true — omit from production deployments
@RestController
@RequestMapping("/debug")
@Tag(name = "Debug", description = "Development-only helpers for seeding test data")
@ConditionalOnProperty(name = ["debug.enabled"], havingValue = "true")
class DebugController(private val debugService: DebugService) {
    private val logger = LoggerFactory.getLogger(DebugController::class.java)

    @PostMapping("/seed")
    @Operation(summary = "Seed a ready-to-play debug game with four dummy players and return their session tokens")
    fun seed(): ResponseEntity<DebugSeedResponse> {
        return try {
            val result = debugService.seed()
            logger.info("Debug seed completed for game {}", result.gameState.game.id)
            ResponseEntity.ok(result)
        } catch (e: Exception) {
            logger.error("Debug seed failed: {}", e.message)
            ResponseEntity.internalServerError().build()
        }
    }

    @DeleteMapping("/purge")
    @Operation(summary = "Delete all games and players from the database")
    fun purge(): ResponseEntity<Map<String, Int>> {
        return try {
            val deleted = debugService.purgeGames()
            logger.info("Debug purge deleted {} games", deleted)
            ResponseEntity.ok(mapOf("deletedGames" to deleted))
        } catch (e: Exception) {
            logger.error("Debug purge failed: {}", e.message)
            ResponseEntity.internalServerError().build()
        }
    }

    @PostMapping("/fill-lobby")
    @Operation(summary = "Fill an existing lobby with dummy players (up to 3) and broadcast LOBBY_JOINED events")
    fun fillLobby(@RequestBody request: FillLobbyRequest): ResponseEntity<List<LoginResponse>> {
        return try {
            val added = debugService.fillLobby(request.lobbyCode)
            logger.info("Filled lobby '{}' with {} dummy players", request.lobbyCode, added.size)
            ResponseEntity.ok(added)
        } catch (e: Exception) {
            logger.error("Fill lobby failed for '{}': {}", request.lobbyCode, e.message)
            ResponseEntity.internalServerError().build()
        }
    }
}