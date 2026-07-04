package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Team

object InningEngine {

    fun apply(state: GameState): GameState {
        if (state.outs < 3) {
            return state
        }

        return if (state.topOfInning) {
            moveToBottomHalf(state)
        } else {
            moveToNextInning(state)
        }
    }

    private fun moveToBottomHalf(state: GameState): GameState {
        return state.copy(
            balls = 0,
            strikes = 0,
            outs = 0,
            topOfInning = false,
            activeTeam = Team.HOME,
            bases = Bases()
        )
    }

    private fun moveToNextInning(state: GameState): GameState {
        return state.copy(
            balls = 0,
            strikes = 0,
            outs = 0,
            currentInning = state.currentInning + 1,
            topOfInning = true,
            activeTeam = Team.AWAY,
            bases = Bases()
        )
    }
}