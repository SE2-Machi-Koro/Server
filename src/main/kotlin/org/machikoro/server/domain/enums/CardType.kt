package org.machikoro.server.domain.enums

enum class CardType(val color: CardColor, val establishmentType: EstablishmentType) {
    WHEAT_FIELD(CardColor.BLUE, EstablishmentType.AGRICULTURE),
    RANCH(CardColor.BLUE, EstablishmentType.LIVESTOCK),
    FOREST(CardColor.BLUE, EstablishmentType.RESOURCE),
    MINE(CardColor.BLUE, EstablishmentType.RESOURCE),
    APPLE_ORCHARD(CardColor.BLUE, EstablishmentType.AGRICULTURE),
    BAKERY(CardColor.GREEN, EstablishmentType.RETAIL),
    CONVENIENCE_STORE(CardColor.GREEN, EstablishmentType.RETAIL),
    CHEESE_FACTORY(CardColor.GREEN, EstablishmentType.INDUSTRY),
    FURNITURE_FACTORY(CardColor.GREEN, EstablishmentType.INDUSTRY),
    FRUIT_AND_VEGETABLE_MARKET(CardColor.GREEN, EstablishmentType.RETAIL),
    CAFE(CardColor.RED, EstablishmentType.HOSPITALITY),
    FAMILY_RESTAURANT(CardColor.RED, EstablishmentType.HOSPITALITY),
    STADIUM(CardColor.PURPLE, EstablishmentType.MAJOR),
    TV_STATION(CardColor.PURPLE, EstablishmentType.MAJOR),
    BUSINESS_CENTER(CardColor.PURPLE, EstablishmentType.MAJOR),

}