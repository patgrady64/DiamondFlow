package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PlayAction

data class PitchResolution(
    val state: GameState,
    val completedPlayAction: PlayAction? = null
)