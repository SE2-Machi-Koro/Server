package org.machikoro.server.dto

/** Result of adjudicating a cheating accusation (issue #361). */
enum class AccusationOutcomeType {
    /** The accused had an outstanding cheat — the cheater is penalized. */
    CAUGHT,

    /** The accused had no outstanding cheat — the accuser loses their bet. */
    WRONG,
}

/**
 * Outcome of an accusation, returned by `AccusationService.accuse` and used to
 * build the broadcast `ACCUSATION_RESULT` payload. All IDs are `PlayerModel.id`
 * (player IDs), not user IDs.
 */
data class AccusationOutcome(
    val outcome: AccusationOutcomeType,
    val accuserPlayerId: Int,
    val accusedPlayerId: Int,
    val penalizedPlayerId: Int,
)
