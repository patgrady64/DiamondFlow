package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.logic.GameEngine
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
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
}