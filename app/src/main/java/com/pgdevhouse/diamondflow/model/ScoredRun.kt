package com.pgdevhouse.diamondflow.model

data class ScoredRun(
    val playerId: Int,
    val responsiblePitcherName: String? = null,
    val earned: Boolean = true
)
