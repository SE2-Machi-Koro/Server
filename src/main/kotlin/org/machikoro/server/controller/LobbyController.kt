package org.machikoro.server.controller

import org.machikoro.server.auth.UserPrincipal
import org.machikoro.server.service.LobbyService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/lobby")
class LobbyController(private val lobbyService: LobbyService) {

    /**
     * HTTP convenience endpoint for starting a game.
     * Caller must be the lobby host — identity is taken from the Bearer token, not a param.
     * The primary start path is the WebSocket handler [GameController.startGame].
     */
    @PostMapping("/{gameId}/start")
    fun startGame(
        @PathVariable gameId: Int,
        @AuthenticationPrincipal principal: UserPrincipal,
    ): ResponseEntity<Any> {
        val state = lobbyService.startGame(gameId, principal.userId)
        return ResponseEntity.ok(state)
    }
}
