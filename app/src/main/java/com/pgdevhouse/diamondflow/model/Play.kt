package com.pgdevhouse.diamondflow.model

data class Play(
    val action: PlayAction,
    val batterId: Int = -1,
    val fielding: FieldingPlay? = null,
    val manualRunnerDestinations: Map<Int, Base?> = emptyMap(),
    val outsRecorded: Int = action.defaultOuts
) {
    init {
        require(outsRecorded in 0..3) {
            "outsRecorded must be between 0 and 3."
        }
    }
}