package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import org.junit.Assert.assertEquals
import org.junit.Test

class CountEngineTest {

    @Test
    fun completed_play_resets_balls_and_strikes() {
        val state = GameState(
            balls = 3,
            strikes = 2,
            outs = 1
        )

        val result = CountEngine.apply(
            state = state,
            play = Play(
                action = PlayAction.SINGLE,
                batterId = 10
            )
        )

        assertEquals(0, result.balls)
        assertEquals(0, result.strikes)
    }

    @Test
    fun resetting_count_does_not_change_outs() {
        val state = GameState(
            balls = 2,
            strikes = 1,
            outs = 2
        )

        val result = CountEngine.apply(
            state = state,
            play = Play(
                action = PlayAction.WALK,
                batterId = 10
            )
        )

        assertEquals(2, result.outs)
    }

    @Test
    fun resetting_count_does_not_change_inning() {
        val state = GameState(
            balls = 1,
            strikes = 2,
            currentInning = 4,
            topOfInning = false
        )

        val result = CountEngine.apply(
            state = state,
            play = Play(
                action = PlayAction.STRIKEOUT,
                batterId = 10
            )
        )

        assertEquals(4, result.currentInning)
        assertEquals(false, result.topOfInning)
    }
}