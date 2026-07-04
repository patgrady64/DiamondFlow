package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState

object ScoreEngine {

    fun apply(
        state: GameState,
        runsScored: Int
    ): GameState {

        if (runsScored <= 0) {
            return state
        }

        val inningIndex = state.currentInning - 1

        val updatedScores = state.scores.toMutableMap()

        val teamScores = updatedScores[state.activeTeam]
            ?.toMutableList()
            ?: MutableList(state.maxInnings) { 0 }

        while (teamScores.size <= inningIndex) {
            teamScores.add(0)
        }

        teamScores[inningIndex] += runsScored
        updatedScores[state.activeTeam] = teamScores

        return state.copy(
            scores = updatedScores
        )
    }
}