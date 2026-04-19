package org.machikoro.server.service.interfaces

interface EarningsService {
    fun processEarnings(gameId: Int, diceRoll: Int)
    fun resolveEffects(gameId: Int)
}