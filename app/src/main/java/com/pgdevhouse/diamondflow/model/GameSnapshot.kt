package com.pgdevhouse.diamondflow.model

import com.pgdevhouse.diamondflow.engine.LineupEngine

data class GameSnapshot(
    val inningLabel: String,
    val scoreLabel: String,
    val countLabel: String,
    val outsLabel: String,
    val basesLabel: String,
    val currentBatterName: String?
) {
    companion object {

        fun from(state: GameState): GameSnapshot {
            val awayRuns = state.totalRuns(Team.AWAY)
            val homeRuns = state.totalRuns(Team.HOME)

            val inningHalf = if (state.topOfInning) "Top" else "Bottom"
            val batter = LineupEngine.currentBatter(state)

            return GameSnapshot(
                inningLabel = "$inningHalf ${state.currentInning}",
                scoreLabel = "${state.awayTeamName} $awayRuns - ${state.homeTeamName} $homeRuns",
                countLabel = "${state.balls}-${state.strikes}",
                outsLabel = "${state.outs} ${outWord(state.outs)}",
                basesLabel = basesLabel(state.bases),
                currentBatterName = batter?.name
            )
        }

        private fun outWord(outs: Int): String = if (outs == 1) "out" else "outs"

        private fun basesLabel(bases: Bases): String {
            val occupied = mutableListOf<String>()
            if (bases.first != null) occupied.add("1B")
            if (bases.second != null) occupied.add("2B")
            if (bases.third != null) occupied.add("3B")
            return if (occupied.isEmpty()) "Bases empty" else occupied.joinToString(", ")
        }
    }
}
