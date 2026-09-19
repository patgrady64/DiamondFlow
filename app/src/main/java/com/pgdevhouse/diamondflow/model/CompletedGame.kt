package com.pgdevhouse.diamondflow.model

data class CompletedGame(
    val gameId: String,
    val completedAtEpochMillis: Long,
    val state: GameState
)
