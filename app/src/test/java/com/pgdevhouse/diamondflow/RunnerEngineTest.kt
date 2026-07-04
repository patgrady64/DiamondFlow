package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Runner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunnerEngineTest {

    @Test
    fun single_with_empty_bases_puts_batter_on_first() {
        val play = Play(
            action = PlayAction.SINGLE,
            batterId = 10
        )

        val result = RunnerEngine.apply(
            bases = Bases(),
            play = play
        )

        assertEquals(10, result.bases.first?.playerId)
        assertNull(result.bases.second)
        assertNull(result.bases.third)
        assertEquals(0, result.runsScored)
    }

    @Test
    fun single_with_runner_on_first_moves_runner_to_second() {
        val play = Play(
            action = PlayAction.SINGLE,
            batterId = 10
        )

        val bases = Bases(
            first = Runner(
                playerId = 1,
                base = Base.FIRST
            )
        )

        val result = RunnerEngine.apply(
            bases = bases,
            play = play
        )

        assertEquals(10, result.bases.first?.playerId)
        assertEquals(1, result.bases.second?.playerId)
        assertNull(result.bases.third)
        assertEquals(0, result.runsScored)
    }

    @Test
    fun single_with_loaded_bases_scores_runner_from_third() {
        val play = Play(
            action = PlayAction.SINGLE,
            batterId = 10
        )

        val bases = Bases(
            first = Runner(
                playerId = 1,
                base = Base.FIRST
            ),
            second = Runner(
                playerId = 2,
                base = Base.SECOND
            ),
            third = Runner(
                playerId = 3,
                base = Base.THIRD
            )
        )

        val result = RunnerEngine.apply(
            bases = bases,
            play = play
        )

        assertEquals(10, result.bases.first?.playerId)
        assertEquals(1, result.bases.second?.playerId)
        assertEquals(2, result.bases.third?.playerId)
        assertEquals(1, result.runsScored)
    }

    @Test
    fun manual_override_can_move_runner_first_to_third_on_single() {
        val play = Play(
            action = PlayAction.SINGLE,
            batterId = 10,
            manualRunnerDestinations = mapOf(
                1 to Base.THIRD
            )
        )

        val bases = Bases(
            first = Runner(
                playerId = 1,
                base = Base.FIRST
            )
        )

        val result = RunnerEngine.apply(
            bases = bases,
            play = play
        )

        assertEquals(10, result.bases.first?.playerId)
        assertNull(result.bases.second)
        assertEquals(1, result.bases.third?.playerId)
        assertEquals(0, result.runsScored)
    }
}