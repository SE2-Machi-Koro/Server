package org.machikoro.server.service.interfaces

interface EarningsService {
    /**
     * Resolves card effects for the current turn and advances to BUY_OR_BUILD.
     * Returns the per-player coin change as `playerId -> signed delta` (changed players only)
     * so the broadcast can carry sound-relevant metadata (#389).
     */
    fun resolveEffects(gameId: Int): Map<Int, Int>
}
