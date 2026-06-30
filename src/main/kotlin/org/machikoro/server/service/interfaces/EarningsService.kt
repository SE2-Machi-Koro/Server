package org.machikoro.server.service.interfaces

import org.machikoro.server.domain.enums.CardType

interface EarningsService {
    /**
     * Resolves card effects for the current turn. Advances to BUY_OR_BUILD, or to
     * AWAIT_TV_TARGET when the active player must still pick a TV Station victim
     * ([resolveTvStationTarget]). Returns the per-player coin change as
     * `playerId -> signed delta` (changed players only) so the broadcast can carry
     * sound-relevant metadata (#389).
     */
    fun resolveEffects(gameId: Int): Map<Int, Int>

    /**
     * Completes a pending TV Station steal: the active player takes coins from
     * [targetPlayerId] (capped at that player's balance) and the turn advances to
     * BUY_OR_BUILD. Only valid while the game is in AWAIT_TV_TARGET. Returns the
     * per-player coin change as `playerId -> signed delta` (changed players only).
     * See issue #433.
     */
    fun resolveTvStationTarget(gameId: Int, targetPlayerId: Int): Map<Int, Int>

    /**
     * Applies the Business Center card-swap action for the active player.
     */
    fun swapBusinessCenterCard(
        gameId: Int,
        activePlayerId: Int,
        targetPlayerId: Int,
        offeredCardType: CardType,
        requestedCardType: CardType,
    )
}
