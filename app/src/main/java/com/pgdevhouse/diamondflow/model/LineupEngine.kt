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
        return batterForTeam(state, state.activeTeam)
    }

    fun batterForTeam(state: GameState, team: Team): Player? {
        return when (team) {
            Team.AWAY -> batterFromLineup(state.lineupAway, state.awayBatterIndex)
                ?: state.awayUnknownBatterId?.let { Player(id = it, name = "Unknown") }
            Team.HOME -> batterFromLineup(state.lineupHome, state.homeBatterIndex)
                ?: state.homeUnknownBatterId?.let { Player(id = it, name = "Unknown") }
        }
    }

    private fun advanceAwayBatter(state: GameState): GameState {
        if (state.lineupAway.isEmpty()) return state
        if (state.awayBatterIndex >= 0) {
            return state.copy(
                awayBatterIndex = nextIndex(state.awayBatterIndex, state.lineupAway.size),
                awayUnknownBatterId = null
            )
        }
        if (state.awayUnknownBatterId != null) {
            return state.copy(
                awayUnknownBatterId = state.nextUnknownPlayerId,
                nextUnknownPlayerId = state.nextUnknownPlayerId - 1
            )
        }
        return state
    }

    private fun advanceHomeBatter(state: GameState): GameState {
        if (state.lineupHome.isEmpty()) return state
        if (state.homeBatterIndex >= 0) {
            return state.copy(
                homeBatterIndex = nextIndex(state.homeBatterIndex, state.lineupHome.size),
                homeUnknownBatterId = null
            )
        }
        if (state.homeUnknownBatterId != null) {
            return state.copy(
                homeUnknownBatterId = state.nextUnknownPlayerId,
                nextUnknownPlayerId = state.nextUnknownPlayerId - 1
            )
        }
        return state
    }

    private fun nextIndex(currentIndex: Int, lineupSize: Int): Int = (currentIndex + 1) % lineupSize

    private fun batterFromLineup(lineup: List<Player>, index: Int): Player? {
        if (lineup.isEmpty() || index < 0) return null
        return lineup[index % lineup.size]
    }
}
