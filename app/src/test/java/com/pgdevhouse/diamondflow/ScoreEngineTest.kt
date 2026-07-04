package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoreEngineTest {

    @Test
    fun zero_runs_does_not_change_score() {
        val state = GameState()

        val result = ScoreEngine.apply(
            state = state,
            runsScored = 0
        )

        assertEquals(0, result.scores[Team.AWAY]?.get(0))
        assertEquals(0, result.scores[Team.HOME]?.get(0))
    }

    @Test
    fun away_team_runs_are_added_to_current_inning() {
        val state = GameState(
            currentInning = 1,
            activeTeam = Team.AWAY
        )

        val result = ScoreEngine.apply(
            state = state,
            runsScored = 2
        )

        assertEquals(2, result.scores[Team.AWAY]?.get(0))
        assertEquals(0, result.scores[Team.HOME]?.get(0))
    }

    @Test
    fun home_team_runs_are_added_to_current_inning() {
        val state = GameState(
            currentInning = 2,
            topOfInning = false,
            activeTeam = Team.HOME
        )

        val result = ScoreEngine.apply(
            state = state,
            runsScored = 3
        )

        assertEquals(3, result.scores[Team.HOME]?.get(1))
        assertEquals(0, result.scores[Team.AWAY]?.get(1))
    }

    @Test
    fun adding_runs_preserves_previous_inning_scores() {
        val initialScores = mapOf(
            Team.AWAY to listOf(2, 0, 0, 0, 0, 0, 0, 0, 0),
            Team.HOME to listOf(1, 0, 0, 0, 0, 0, 0, 0, 0)
        )

        val state = GameState(
            currentInning = 2,
            activeTeam = Team.AWAY,
            scores = initialScores
        )

        val result = ScoreEngine.apply(
            state = state,
            runsScored = 1
        )

        assertEquals(2, result.scores[Team.AWAY]?.get(0))
        assertEquals(1, result.scores[Team.AWAY]?.get(1))
        assertEquals(1, result.scores[Team.HOME]?.get(0))
    }

    @Test
    fun multiple_scoring_plays_accumulate() {
        val state = GameState(
            currentInning = 1,
            activeTeam = Team.AWAY
        )

        val afterFirstPlay = ScoreEngine.apply(
            state = state,
            runsScored = 2
        )

        val afterSecondPlay = ScoreEngine.apply(
            state = afterFirstPlay,
            runsScored = 1
        )

        assertEquals(3, afterSecondPlay.scores[Team.AWAY]?.get(0))
    }
}