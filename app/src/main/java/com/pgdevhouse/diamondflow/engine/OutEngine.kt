package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction

object OutEngine {

    fun apply(
        state: GameState,
        play: Play
    ): GameState {

        val outsRecorded = when (play.action) {
            PlayAction.STRIKEOUT,
            PlayAction.GROUND_OUT,
            PlayAction.FLY_OUT,
            PlayAction.FIELDERS_CHOICE,
            PlayAction.SACRIFICE -> 1

            else -> 0
        }

        return state.copy(
            outs = state.outs + outsRecorded
        )
    }
}