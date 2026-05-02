package org.machikoro.server.controller

import org.machikoro.server.service.LobbyService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/lobby")
class LobbyController(private val lobbyService: LobbyService) {

    /**
     * HTTP convenience endpoint for starting a game.
     * The [requestingUserId] must match the game's hostUserId.
     * The primary start path is the WebSocket handler [GameController.startGame].
     */
    @PostMapping("/{gameId}/start")
    fun startGame(
        @PathVariable gameId: Int,
        @org.springframework.web.bind.annotation.RequestParam requestingUserId: Int,
    ): ResponseEntity<Any> {
        return try {
            val state = lobbyService.startGame(gameId, requestingUserId)
            ResponseEntity.ok(state)
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(e.message)
        }
    }
}