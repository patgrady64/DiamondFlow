package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play

object OutEngine {

    fun apply(
        state: GameState,
        play: Play
    ): GameState {
        return state.copy(
            outs = state.outs + play.outsRecorded
        )
    }
}