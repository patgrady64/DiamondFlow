package com.pgdevhouse.diamondflow.model

data class Runner(
    val playerId: Int,
    val base: Base,
    val responsiblePitcherName: String? = null,
    val earnedRunEligible: Boolean = true
)
