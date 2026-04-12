package org.machikoro.server.database.entities

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.machikoro.server.database.Cards
import org.machikoro.server.domain.models.CardModel

class CardEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<CardEntity>(Cards)

    var cardType by Cards.cardType
    var name by Cards.name
    var cost by Cards.cost
    var diceMin by Cards.diceMin
    var diceMax by Cards.diceMax
    var income by Cards.income

    fun toModel() = CardModel(
        id = id.value,
        cardType = cardType,
        name = name,
        cost = cost,
        diceMin = diceMin,
        diceMax = diceMax,
        income = income
    )
}