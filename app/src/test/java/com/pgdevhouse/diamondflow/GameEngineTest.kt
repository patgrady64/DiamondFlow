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
}