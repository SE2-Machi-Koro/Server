package org.machikoro.server.service

import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.springframework.stereotype.Service

interface EarningsService {
    fun processEarnings(gameId: Int, diceRoll: Int)
}

@Service
class EarningsServiceImpl(
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val cardDao: CardDao
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
}