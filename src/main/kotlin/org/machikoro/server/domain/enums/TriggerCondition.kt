package org.machikoro.server.domain.enums

enum class TriggerCondition {
    OWN_TURN, // Own Turn
    ANY_TURN, // Blue Cards - Trigger on anyone's turn
    OTHER_TURN, // Red Cards - Trigger when someone else has a turn
    NONE
}