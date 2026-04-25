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

    @PostMapping("/{gameId}/start")
    fun startGame(@PathVariable gameId: Int): ResponseEntity<Any> {
        return try {
            lobbyService.startGame(gameId)
            ResponseEntity.ok().build()
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(e.message)
        }
    }
}