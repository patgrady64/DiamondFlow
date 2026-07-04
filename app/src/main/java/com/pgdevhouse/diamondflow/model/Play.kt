package com.pgdevhouse.diamondflow.model

data class Play(
    val action: PlayAction,

    // The player currently batting.
    val batterId: Int = -1,

    val fielding: FieldingPlay? = null,

    /*
     * Optional corrections to automatic runner movement.
     *
     * Key: playerId
     * Value:
     *   FIRST, SECOND, or THIRD = runner ends on that base
     *   HOME = runner scores
     *   null = runner is removed, such as being put out
     */
    val manualRunnerDestinations: Map<Int, Base?> = emptyMap()
)