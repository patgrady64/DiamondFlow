package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.PlayAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PitchEngineTest {

    @Test
    fun ball_increases_ball_count() {
        val result = PitchEngine.apply(
            state = GameState(balls = 1),
            pitch = PitchAction.BALL
        )

        assertEquals(2, result.state.balls)
        assertNull(result.completedPlayAction)
    }

    @Test
    fun fourth_ball_completes_walk() {
        val result = PitchEngine.apply(
            state = GameState(balls = 3),
            pitch = PitchAction.BALL
        )

        assertEquals(PlayAction.WALK, result.completedPlayAction)
    }

    @Test
    fun strike_increases_strike_count() {
        val result = PitchEngine.apply(
            state = GameState(strikes = 1),
            pitch = PitchAction.STRIKE
        )

        assertEquals(2, result.state.strikes)
        assertNull(result.completedPlayAction)
    }

    @Test
    fun third_strike_completes_strikeout() {
        val result = PitchEngine.apply(
            state = GameState(strikes = 2),
            pitch = PitchAction.STRIKE
        )

        assertEquals(
            PlayAction.STRIKEOUT,
            result.completedPlayAction
        )
    }

    @Test
    fun foul_with_less_than_two_strikes_adds_strike() {
        val result = PitchEngine.apply(
            state = GameState(strikes = 1),
            pitch = PitchAction.FOUL
        )

        assertEquals(2, result.state.strikes)
        assertNull(result.completedPlayAction)
    }

    @Test
    fun foul_with_two_strikes_does_not_add_strike() {
        val result = PitchEngine.apply(
            state = GameState(strikes = 2),
            pitch = PitchAction.FOUL
        )

        assertEquals(2, result.state.strikes)
        assertNull(result.completedPlayAction)
    }

    @Test
    fun hit_by_pitch_completes_plate_appearance() {
        val result = PitchEngine.apply(
            state = GameState(),
            pitch = PitchAction.HIT_BY_PITCH
        )

        assertEquals(
            PlayAction.HIT_BY_PITCH,
            result.completedPlayAction
        )
    }
}