package org.machikoro.server.service

import org.machikoro.server.dto.MessageType
import org.machikoro.server.dto.WebSocketMessage
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service

/**
 * Builds and broadcasts the `GAME_END` WebSocket message to `/topic/game/{gameId}`.
 *
 * Extracted so both the production end-of-game path (`GamePhaseService.endTurn`
 * via `GameController.endTurn`) and the debug end-game endpoint
 * (`DebugService.endGame`) emit the identical wire shape — same `MessageType`,
 * same payload keys, same destination — so the client's winner-screen handler
 * sees one consistent contract regardless of which path produced the end.
 */
@Service
class GameEndBroadcaster(
    private val gameSyncService: GameSyncService,
    private val messagingTemplate: SimpMessagingTemplate,
) {
    fun broadcast(gameId: Int, winnerId: Int, roundsPlayed: Int) {
        val state = gameSyncService.buildSnapshot(gameId)
        messagingTemplate.convertAndSend(
            "/topic/game/$gameId",
            WebSocketMessage(
                type = MessageType.GAME_END,
                sender = "server",
                payload = mapOf(
                    "winnerId" to winnerId,
                    "roundsPlayed" to roundsPlayed,
                    "state" to state,
                ),
                gameId = gameId,
            ),
        )
    }
}
