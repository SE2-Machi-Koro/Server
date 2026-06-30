package org.machikoro.server.database

import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.machikoro.server.database.CardActivationNumbers
import org.machikoro.server.database.Cards
import org.machikoro.server.database.Landmarks
import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.PaymentSource

/**
 * Inserts all 15 Machi Koro establishment cards and all 4 landmarks into the DB.
 * Must be called inside an Exposed transaction.
 * Uses insertIgnore so it is safe to call multiple times per test class lifecycle.
 */
object TestDataSeeder {

    fun seedAllCards() {
        data class CardSeed(
            val type: CardType,
            val cost: Int,
            val income: Int,
            val color: CardColor,
            val estType: EstablishmentType,
            val payment: PaymentSource,
            val activation: List<Int>,
        )

        val all = listOf(
            CardSeed(
                CardType.WHEAT_FIELD,
                1,
                1,
                CardColor.BLUE,
                EstablishmentType.WHEAT,
                PaymentSource.BANK,
                listOf(1)
            ),
            CardSeed(CardType.RANCH, 1, 1, CardColor.BLUE, EstablishmentType.COW, PaymentSource.BANK, listOf(2)),
            CardSeed(CardType.FOREST, 3, 1, CardColor.BLUE, EstablishmentType.GEAR, PaymentSource.BANK, listOf(5)),
            CardSeed(CardType.MINE, 6, 5, CardColor.BLUE, EstablishmentType.GEAR, PaymentSource.BANK, listOf(9)),
            CardSeed(
                CardType.APPLE_ORCHARD,
                3,
                3,
                CardColor.BLUE,
                EstablishmentType.WHEAT,
                PaymentSource.BANK,
                listOf(10)
            ),
            CardSeed(CardType.BAKERY, 1, 1, CardColor.GREEN, EstablishmentType.BREAD, PaymentSource.BANK, listOf(2, 3)),
            CardSeed(
                CardType.CONVENIENCE_STORE,
                2,
                3,
                CardColor.GREEN,
                EstablishmentType.BREAD,
                PaymentSource.BANK,
                listOf(4)
            ),
            CardSeed(
                CardType.CHEESE_FACTORY,
                5,
                3,
                CardColor.GREEN,
                EstablishmentType.FACTORY,
                PaymentSource.BANK,
                listOf(7)
            ),
            CardSeed(
                CardType.FURNITURE_FACTORY,
                3,
                3,
                CardColor.GREEN,
                EstablishmentType.FACTORY,
                PaymentSource.BANK,
                listOf(8)
            ),
            CardSeed(
                CardType.FRUIT_AND_VEGETABLE_MARKET,
                2,
                2,
                CardColor.GREEN,
                EstablishmentType.FRUIT,
                PaymentSource.BANK,
                listOf(11, 12)
            ),
            CardSeed(CardType.CAFE, 2, 1, CardColor.RED, EstablishmentType.CUP, PaymentSource.ACTIVE_PLAYER, listOf(3)),
            CardSeed(
                CardType.FAMILY_RESTAURANT,
                3,
                2,
                CardColor.RED,
                EstablishmentType.CUP,
                PaymentSource.ACTIVE_PLAYER,
                listOf(9, 10)
            ),
            CardSeed(
                CardType.STADIUM,
                6,
                2,
                CardColor.PURPLE,
                EstablishmentType.MAJOR,
                PaymentSource.ALL_PLAYERS,
                listOf(6)
            ),
            CardSeed(
                CardType.TV_STATION,
                7,
                5,
                CardColor.PURPLE,
                EstablishmentType.MAJOR,
                PaymentSource.CHOSEN_PLAYER,
                listOf(6)
            ),
            CardSeed(
                CardType.BUSINESS_CENTER,
                8,
                0,
                CardColor.PURPLE,
                EstablishmentType.MAJOR,
                PaymentSource.NONE,
                listOf(6)
            ),
        )

        all.forEach { seed ->
            val insertedCardId = Cards.insertIgnore {
                it[cardType] = seed.type
                it[cost] = seed.cost
                it[income] = seed.income
                it[color] = seed.color
                it[establishmentType] = seed.estType
                it[paymentSource] = seed.payment
            } get Cards.id
            seed.activation.forEach { n ->
                CardActivationNumbers.insertIgnore {
                    it[cardId] = insertedCardId
                    it[number] = n
                }
            }
        }
    }

    fun seedAllLandmarks() {
        val costs = mapOf(
            LandmarkType.TRAIN_STATION to 4,
            LandmarkType.SHOPPING_MALL to 10,
            LandmarkType.AMUSEMENT_PARK to 16,
            LandmarkType.RADIO_TOWER to 22,
        )
        costs.forEach { (type, landmarkCost) ->
            Landmarks.insertIgnore {
                it[landmarkType] = type
                it[Landmarks.cost] = landmarkCost
            }
        }
    }
}
