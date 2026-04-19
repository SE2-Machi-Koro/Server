package org.machikoro.server.service

import org.machikoro.server.dao.CardDao
import org.machikoro.server.dao.PlayerCardDao
import org.machikoro.server.dao.PlayerDao
import org.springframework.stereotype.Service

interface EarnignsService {
    fun processEarnings(gameId: Int, diceRoll: Int)
}

@Service
class EarningsServiceImpl(
    private val playerDao: PlayerDao,
    private val playerCardDao: PlayerCardDao,
    private val cardDao: CardDao
) : EarnignsService {

    override fun processEarnings(gameId: Int, diceRoll: Int) {
        val allCards = cardDao.findAll().filter { it.diceMin <= diceRoll && it.diceMax >= diceRoll }
            .associateBy { it.cardType }

        val players = playerDao.findByGameId(gameId = gameId)

        players.forEach { player ->
            val playerCards = playerCardDao.findByPlayerId(player.id)
            var earned = 0
            for (playerCard in playerCards) {
                val card = allCards[playerCard.cardType]
                if (card != null) {
                    earned += playerCard.quantity * card.income
                }
            }
            if (earned > 0) {
                playerDao.updateCoins(player.id, player.coins + earned)
            }
        }
    }
}