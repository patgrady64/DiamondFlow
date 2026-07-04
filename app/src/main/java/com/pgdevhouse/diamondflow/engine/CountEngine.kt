package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play

object CountEngine {

    fun apply(
        state: GameState,
        play: Play
    ): GameState {
        return state.copy(
            balls = 0,
            strikes = 0
        )
    }
}