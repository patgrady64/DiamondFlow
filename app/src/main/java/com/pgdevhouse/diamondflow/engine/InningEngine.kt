package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Team

object InningEngine {

    fun apply(state: GameState): GameState {
        if (state.gameOver) {
            return state
        }

        val awayRuns = state.totalRuns(Team.AWAY)
        val homeRuns = state.totalRuns(Team.HOME)
        val regulationOrLater = state.currentInning >= state.maxInnings

        // A home-team lead in the bottom of the final scheduled inning or later
        // is immediately a walk-off.
        if (regulationOrLater && !state.topOfInning && homeRuns > awayRuns) {
            return state.copy(gameOver = true)
        }

        if (state.outs < 3) {
            return state
        }

        if (state.topOfInning) {
            // If the home team already leads after the visitors make their third
            // out in the final scheduled inning (or later), no bottom half is needed.
            if (regulationOrLater && homeRuns > awayRuns) {
                return state.copy(gameOver = true)
            }
            return moveToBottomHalf(state)
        }

        // Bottom half is complete. A non-tie ends the game; a tie creates
        // another inning automatically.
        if (regulationOrLater && homeRuns != awayRuns) {
            return state.copy(gameOver = true)
        }

        return moveToNextInning(state)
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
