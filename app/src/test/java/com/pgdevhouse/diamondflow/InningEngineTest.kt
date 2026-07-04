package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Runner
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InningEngineTest {

    @Test
    fun fewer_than_three_outs_does_not_change_inning() {
        val state = GameState(
            outs = 2,
            currentInning = 1,
            topOfInning = true,
            activeTeam = Team.AWAY
        )

        val result = InningEngine.apply(state)

        assertEquals(2, result.outs)
        assertEquals(1, result.currentInning)
        assertEquals(true, result.topOfInning)
        assertEquals(Team.AWAY, result.activeTeam)
    }

    @Test
    fun third_out_in_top_half_moves_to_bottom_half() {
        val state = GameState(
            balls = 2,
            strikes = 1,
            outs = 3,
            currentInning = 1,
            topOfInning = true,
            activeTeam = Team.AWAY,
            bases = Bases(
                first = Runner(
                    playerId = 1,
                    base = Base.FIRST
                )
            )
        )

        val result = InningEngine.apply(state)

        assertEquals(0, result.balls)
        assertEquals(0, result.strikes)
        assertEquals(0, result.outs)

        assertEquals(1, result.currentInning)
        assertEquals(false, result.topOfInning)
        assertEquals(Team.HOME, result.activeTeam)

        assertNull(result.bases.first)
        assertNull(result.bases.second)
        assertNull(result.bases.third)
    }

    @Test
    fun third_out_in_bottom_half_moves_to_next_inning() {
        val state = GameState(
            outs = 3,
            currentInning = 1,
            topOfInning = false,
            activeTeam = Team.HOME
        )

        val result = InningEngine.apply(state)

        assertEquals(0, result.outs)
        assertEquals(2, result.currentInning)
        assertEquals(true, result.topOfInning)
        assertEquals(Team.AWAY, result.activeTeam)
    }

    @Test
    fun inning_transition_clears_loaded_bases() {
        val state = GameState(
            outs = 3,
            bases = Bases(
                first = Runner(1, Base.FIRST),
                second = Runner(2, Base.SECOND),
                third = Runner(3, Base.THIRD)
            )
        )

        val result = InningEngine.apply(state)

        assertEquals(true, result.bases.isEmpty())
    }
}