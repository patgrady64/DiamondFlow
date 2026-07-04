package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutEngineTest {

    @Test
    fun single_records_no_outs() {
        val state = GameState(outs = 1)

        val result = OutEngine.apply(
            state = state,
            play = Play(
                action = PlayAction.SINGLE,
                batterId = 10
            )
        )

        assertEquals(1, result.outs)
    }

    @Test
    fun strikeout_records_one_out() {
        val state = GameState(outs = 1)

        val result = OutEngine.apply(
            state = state,
            play = Play(
                action = PlayAction.STRIKEOUT,
                batterId = 10
            )
        )

        assertEquals(2, result.outs)
    }

    @Test
    fun play_can_record_two_outs() {
        val state = GameState(outs = 0)

        val result = OutEngine.apply(
            state = state,
            play = Play(
                action = PlayAction.GROUND_OUT,
                batterId = 10,
                outsRecorded = 2
            )
        )

        assertEquals(2, result.outs)
    }

    @Test
    fun fielders_choice_can_be_overridden_to_zero_outs() {
        val state = GameState(outs = 1)

        val result = OutEngine.apply(
            state = state,
            play = Play(
                action = PlayAction.FIELDERS_CHOICE,
                batterId = 10,
                outsRecorded = 0
            )
        )

        assertEquals(1, result.outs)
    }

    @Test
    fun more_than_three_outs_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Play(
                action = PlayAction.GROUND_OUT,
                batterId = 10,
                outsRecorded = 4
            )
        }
    }
}