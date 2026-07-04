package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Team

object FlowEngine {

    fun addBall(state: GameState): GameState {
        val balls = state.balls + 1

        return if (balls > 3) {
            state.copy(balls = 0)
        } else {
            state.copy(balls = balls)
        }
    }

    fun addStrike(state: GameState): GameState {
        val strikes = state.strikes + 1

        return if (strikes > 2) {
            state.copy(strikes = 0)
        } else {
            state.copy(strikes = strikes)
        }
    }

    fun resetCount(state: GameState): GameState {
        return state.copy(
            balls = 0,
            strikes = 0
        )
    }

    fun addOut(state: GameState): GameState {
        val outs = state.outs + 1

        return if (outs >= 3) {
            state.copy(
                outs = 0,
                balls = 0,
                strikes = 0
            )
        } else {
            state.copy(outs = outs)
        }
    }

    fun addRun(state: GameState, inning: Int, team: Team): GameState {
        val updatedScores = state.scores.toMutableMap()
        val teamScores = updatedScores[team]!!.toMutableList()

        teamScores[inning - 1] = teamScores[inning - 1] + 1
        updatedScores[team] = teamScores

        return state.copy(scores = updatedScores)
    }
}