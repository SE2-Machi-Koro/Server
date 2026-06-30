package org.machikoro.server.service.interfaces

import org.machikoro.server.domain.enums.CardType

interface EarningsService {
    /**
     * Applies the dice-roll earnings and returns the per-player coin change as
     * `playerId -> signed delta`, including only players whose balance changed.
     * Clients use these deltas to drive coin sound effects (issue #389).
     */
    fun processEarnings(gameId: Int, diceRoll: Int, activePlayerId: Int): Map<Int, Int>

    /**
     * Resolves card effects for the current turn and advances to BUY_OR_BUILD.
     * Returns the per-player coin change as `playerId -> signed delta` (changed
     * players only) so the broadcast can carry sound-relevant metadata (#389).
     */
    fun resolveEffects(gameId: Int): Map<Int, Int>

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
