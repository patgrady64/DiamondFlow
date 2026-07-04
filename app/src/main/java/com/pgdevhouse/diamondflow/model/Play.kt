package com.pgdevhouse.diamondflow.model

data class Play(
    val action: PlayAction,
    val batterId: Int = -1,
    val fielding: FieldingPlay? = null,
    val manualRunnerDestinations: Map<Int, Base?> = emptyMap(),

    // The number of outs actually recorded during this play.
    val outsRecorded: Int = defaultOutsFor(action)
) {
    init {
        require(outsRecorded in 0..3) {
            "outsRecorded must be between 0 and 3."
        }
    }
}

private fun defaultOutsFor(action: PlayAction): Int {
    return when (action) {
        PlayAction.STRIKEOUT,
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.FIELDERS_CHOICE,
        PlayAction.SACRIFICE -> 1

        else -> 0
    }
}