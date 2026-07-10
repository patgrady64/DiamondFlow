package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.logic.GameEngine
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Runner
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameEngineTest {

    private val engine = GameEngine()

    @Test
    fun walk_followed_by_home_run_scores_two_runs() {
        val initialState = GameState()

        val afterWalk = engine.applyPlay(
            state = initialState,
            play = Play(
                action = PlayAction.WALK,
                batterId = 1
            )
        )

        assertEquals(1, afterWalk.bases.first?.playerId)

        val afterHomeRun = engine.applyPlay(
            state = afterWalk,
            play = Play(
                action = PlayAction.HOME_RUN,
                batterId = 2
            )
        )

        assertEquals(
            2,
            afterHomeRun.scores[Team.AWAY]?.get(0)
        )

        assertTrue(afterHomeRun.bases.isEmpty())
    }

    @Test
    fun third_out_moves_game_to_bottom_of_inning() {
        val state = GameState(
            outs = 2,
            currentInning = 1,
            topOfInning = true,
            activeTeam = Team.AWAY
        )

        val result = engine.applyPlay(
            state = state,
            play = Play(
                action = PlayAction.STRIKEOUT,
                batterId = 10
            )
        )

        assertEquals(0, result.outs)
        assertEquals(1, result.currentInning)
        assertEquals(false, result.topOfInning)
        assertEquals(Team.HOME, result.activeTeam)
        assertEquals(true, result.bases.isEmpty())
    }

    @Test
    fun fourth_ball_walks_batter_and_resets_count() {
        val state = GameState(
            balls = 3,
            strikes = 1
        )

        val result = engine.applyPitch(
            state = state,
            pitch = PitchAction.BALL,
            batterId = 10
        )

        assertEquals(0, result.balls)
        assertEquals(0, result.strikes)
        assertEquals(10, result.bases.first?.playerId)
        assertEquals(0, result.outs)
    }

    @Test
    fun third_strike_records_out_and_resets_count() {
        val state = GameState(
            balls = 2,
            strikes = 2,
            outs = 1
        )

        val result = engine.applyPitch(
            state = state,
            pitch = PitchAction.STRIKE,
            batterId = 10
        )

        assertEquals(0, result.balls)
        assertEquals(0, result.strikes)
        assertEquals(2, result.outs)
    }

    @Test
    fun foul_with_two_strikes_does_not_record_out() {
        val state = GameState(
            strikes = 2,
            outs = 1
        )

        val result = engine.applyPitch(
            state = state,
            pitch = PitchAction.FOUL,
            batterId = 10
        )

        assertEquals(2, result.strikes)
        assertEquals(1, result.outs)
    }

    @Test
    fun fourth_ball_with_loaded_bases_scores_one_run() {
        val state = GameState(
            balls = 3,
            bases = Bases(
                first = Runner(1, Base.FIRST),
                second = Runner(2, Base.SECOND),
                third = Runner(3, Base.THIRD)
            )
        )

        val result = engine.applyPitch(
            state = state,
            pitch = PitchAction.BALL,
            batterId = 10
        )

        assertEquals(10, result.bases.first?.playerId)
        assertEquals(1, result.bases.second?.playerId)
        assertEquals(2, result.bases.third?.playerId)

        assertEquals(
            1,
            result.scores[Team.AWAY]?.get(0)
        )
    }

    @Test
    fun completed_play_advances_away_batter_index() {
        val state = GameState(
            activeTeam = Team.AWAY,
            awayBatterIndex = 0,
            lineupAway = listOf(
                Player(id = 1, name = "Player 1"),
                Player(id = 2, name = "Player 2"),
                Player(id = 3, name = "Player 3")
            )
        )

        val result = engine.applyPlay(
            state = state,
            play = Play(
                action = PlayAction.SINGLE,
                batterId = 1
            )
        )

        assertEquals(1, result.awayBatterIndex)
    }

    @Test
    fun play_without_batter_id_uses_current_lineup_batter() {
        val state = GameState(
            activeTeam = Team.AWAY,
            awayBatterIndex = 0,
            lineupAway = listOf(
                Player(id = 1, name = "Player 1"),
                Player(id = 2, name = "Player 2"),
                Player(id = 3, name = "Player 3")
            )
        )

        val result = engine.applyPlay(
            state = state,
            play = Play(
                action = PlayAction.SINGLE
            )
        )

        assertEquals(1, result.bases.first?.playerId)
        assertEquals(1, result.awayBatterIndex)
    }
}