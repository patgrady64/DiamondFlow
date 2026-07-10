package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Team

object LineupEngine {

    fun apply(state: GameState): GameState {
        return when (state.activeTeam) {
            Team.AWAY -> advanceAwayBatter(state)
            Team.HOME -> advanceHomeBatter(state)
        }
    }

    fun currentBatter(state: GameState): Player? {
        return when (state.activeTeam) {
            Team.AWAY -> batterFromLineup(
                lineup = state.lineupAway,
                index = state.awayBatterIndex
            )

            Team.HOME -> batterFromLineup(
                lineup = state.lineupHome,
                index = state.homeBatterIndex
            )
        }
    }

    private fun advanceAwayBatter(state: GameState): GameState {
        if (state.lineupAway.isEmpty()) {
            return state
        }

        return state.copy(
            awayBatterIndex = nextIndex(
                currentIndex = state.awayBatterIndex,
                lineupSize = state.lineupAway.size
            )
        )
    }

    private fun advanceHomeBatter(state: GameState): GameState {
        if (state.lineupHome.isEmpty()) {
            return state
        }

        return state.copy(
            homeBatterIndex = nextIndex(
                currentIndex = state.homeBatterIndex,
                lineupSize = state.lineupHome.size
            )
        )
    }

    private fun nextIndex(
        currentIndex: Int,
        lineupSize: Int
    ): Int {
        return (currentIndex + 1) % lineupSize
    }

    private fun batterFromLineup(
        lineup: List<Player>,
        index: Int
    ): Player? {
        if (lineup.isEmpty()) {
            return null
        }

        return lineup[index % lineup.size]
    }
}