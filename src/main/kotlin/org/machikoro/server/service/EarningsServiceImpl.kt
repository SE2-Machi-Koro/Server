package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.GameDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.machikoro.server.domain.enums.TurnPhase
import org.machikoro.server.exception.GameNotFoundException
import org.machikoro.server.service.interfaces.EarningsService
import org.springframework.stereotype.Service

@Service
class EarningsServiceImpl(
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val cardDao: CardDao,
    private val gameDao: GameDao
) : EarningsService {

    fun computeEarnings(pairs: List<Pair<Int, Int>>): Int =
        pairs.sumOf { (quantity, income) -> quantity * income }

    override fun processEarnings(gameId: Int, diceRoll: Int) {
        transaction {
            val allCards = cardDao.findAll()
                .filter { it.diceMin <= diceRoll && it.diceMax >= diceRoll }
                .associateBy { it.cardType }

            val players = playerDao.findByGameId(gameId)

            players.forEach { player ->
                val earned = playerCardDao.findByPlayerId(player.id)
                    .mapNotNull { playerCard -> allCards[playerCard.cardType]?.let { playerCard.quantity to it.income } }
                    .let { computeEarnings(it) }

                if (earned > 0) {
                    playerDao.updateCoins(player.id, player.coins + earned)
                }
            }
        }
    }

    override fun resolveEffects(gameId: Int) {
        val game = gameDao.findById(gameId)
            ?: throw GameNotFoundException("Game $gameId not found")

        check(game.turnPhase == TurnPhase.RESOLVE_EFFECTS) {
            "Game is not in RESOLVE_EFFECTS phase"
        }

        val diceRoll = checkNotNull(game.lastDiceRoll) { "Dice roll not set" }

        processEarnings(gameId, diceRoll)
        gameDao.updateTurnPhase(gameId, TurnPhase.BUY_OR_BUILD)
    }
}