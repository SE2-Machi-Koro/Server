package org.machikoro.server.service.interfaces

interface EarningsService {
    fun processEarnings(gameId: Int, diceRoll: Int, activePlayerId: Int)
    fun resolveEffects(gameId: Int)
}