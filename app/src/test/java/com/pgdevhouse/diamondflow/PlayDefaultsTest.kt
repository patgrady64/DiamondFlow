package com.pgdevhouse.diamondflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayDefaultsTest {

    @Test
    fun strikeout_defaults_to_one_out() {
        val play = Play(
            action = PlayAction.STRIKEOUT
        )

        assertEquals(1, play.outsRecorded)
    }

    @Test
    fun single_defaults_to_zero_outs() {
        val play = Play(
            action = PlayAction.SINGLE
        )

        assertEquals(0, play.outsRecorded)
    }

    @Test
    fun outs_can_be_overridden_for_double_play() {
        val play = Play(
            action = PlayAction.GROUND_OUT,
            outsRecorded = 2
        )

        assertEquals(2, play.outsRecorded)
    }

    @Test
    fun invalid_out_count_is_rejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Play(
                action = PlayAction.GROUND_OUT,
                outsRecorded = 4
            )
        }
    }
}