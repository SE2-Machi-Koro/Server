package org.machikoro.server.dto

/**
 * Outcome of an accusation, returned by `AccusationService.accuse` and used to
 * build the broadcast `ACCUSATION_RESULT` payload. The field names form the
 * client/server wire contract agreed in issue #361 / Client#280 — keep them in
 * lockstep with the client parser. All IDs are `PlayerModel.id` (player IDs),
 * not user IDs.
 *
 * [penaltyCoins] is the number of coins actually deducted from
 * [penalizedPlayerId] — it may be less than the nominal penalty when the
 * balance is clamped at 0.
 */
data class AccusationOutcome(
    val accuserPlayerId: Int,
    val accusedPlayerId: Int,
    val caught: Boolean,
    val penalizedPlayerId: Int,
    val penaltyCoins: Int,
)
