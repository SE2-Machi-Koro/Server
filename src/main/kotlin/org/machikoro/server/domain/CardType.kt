package org.machikoro.server.domain

enum class CardType(val color: CardColor) {
    WHEAT_FIELD(CardColor.BLUE),
    RANCH(CardColor.BLUE),
    FOREST(CardColor.BLUE),
    MINE(CardColor.BLUE),
    APPLE_ORCHARD(CardColor.BLUE),
    BAKERY(CardColor.GREEN),
    CONVENIENCE_STORE(CardColor.GREEN),
    CHEESE_FACTORY(CardColor.GREEN),
    FURNITURE_FACTORY(CardColor.GREEN),
    FRUIT_AND_VEGETABLE_MARKET(CardColor.GREEN),
    CAFE(CardColor.RED),
    FAMILY_RESTAURANT(CardColor.RED),
    STADIUM(CardColor.PURPLE),
    TV_STATION(CardColor.PURPLE),
    BUSINESS_CENTER(CardColor.PURPLE),
}