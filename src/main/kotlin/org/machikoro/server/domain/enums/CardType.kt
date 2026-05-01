package org.machikoro.server.domain.enums

enum class CardType(val color: CardColor, val cost: Int) {
    WHEAT_FIELD(CardColor.BLUE, 1),
    RANCH(CardColor.BLUE, 1),
    FOREST(CardColor.BLUE, 3),
    MINE(CardColor.BLUE, 6),
    APPLE_ORCHARD(CardColor.BLUE, 3),
    BAKERY(CardColor.GREEN, 1),
    CONVENIENCE_STORE(CardColor.GREEN, 2),
    CHEESE_FACTORY(CardColor.GREEN, 5),
    FURNITURE_FACTORY(CardColor.GREEN, 3),
    FRUIT_AND_VEGETABLE_MARKET(CardColor.GREEN, 2),
    CAFE(CardColor.RED, 2),
    FAMILY_RESTAURANT(CardColor.RED, 3),
    STADIUM(CardColor.PURPLE, 6),
    TV_STATION(CardColor.PURPLE, 7),
    BUSINESS_CENTER(CardColor.PURPLE, 8),
}