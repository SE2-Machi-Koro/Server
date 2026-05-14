package org.machikoro.server.dto

import org.machikoro.server.domain.enums.CardColor
import org.machikoro.server.domain.enums.CardType
import org.machikoro.server.domain.enums.EstablishmentType
import org.machikoro.server.domain.enums.LandmarkType
import org.machikoro.server.domain.enums.PaymentSource
import org.machikoro.server.domain.models.CardModel
import org.machikoro.server.domain.models.LandmarkModel

data class CardDefinitionDto(
    val cardType: CardType,
    val cost: Int,
    val income: Int,
    val color: CardColor,
    val establishmentType: EstablishmentType,
    val paymentSource: PaymentSource,
    val activationNumbers: List<Int>,
)

data class LandmarkDefinitionDto(
    val landmarkType: LandmarkType,
    val cost: Int,
)

fun CardModel.toDefinitionDto() = CardDefinitionDto(
    cardType = cardType,
    cost = cost,
    income = income,
    color = color,
    establishmentType = establishmentType,
    paymentSource = paymentSource,
    activationNumbers = activationNumbers,
)

fun LandmarkModel.toDefinitionDto() = LandmarkDefinitionDto(
    landmarkType = landmarkType,
    cost = cost,
)
