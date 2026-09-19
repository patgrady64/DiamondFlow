package com.pgdevhouse.diamondflow.model

data class SavedTeam(
    val name: String,
    val lineupNames: List<String>,
    val lineupPositions: List<String> = emptyList()
)
