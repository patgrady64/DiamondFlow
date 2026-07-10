package com.pgdevhouse.diamondflow.controller

import com.pgdevhouse.diamondflow.model.Base
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameControllerTest {

    @Test
    fun new_controller_has_default_lineups() {
        val controller = GameController()

        assertEquals(9, controller.state.lineupAway.size)
        assertEquals(9, controller.state.lineupHome.size)
    }

    @Test
    fun ball_updates_count() {
        val controller = GameController()

        controller.ball()

        assertEquals(1, controller.state.balls)
        assertEquals(0, controller.state.strikes)
    }

    @Test
    fun strike_updates_count() {
        val controller = GameController()

        controller.strike()

        assertEquals(0, controller.state.balls)
        assertEquals(1, controller.state.strikes)
    }

    @Test
    fun fourth_ball_walks_current_batter() {
        val controller = GameController()

        controller.ball()
        controller.ball()
        controller.ball()
        controller.ball()

        assertEquals(0, controller.state.balls)
        assertEquals(0, controller.state.strikes)
        assertEquals(1, controller.state.bases.first?.playerId)
        assertEquals(1, controller.state.awayBatterIndex)
    }

    @Test
    fun single_places_current_batter_on_first() {
        val controller = GameController()

        controller.single()

        assertEquals(1, controller.state.bases.first?.playerId)
        assertEquals(1, controller.state.awayBatterIndex)
    }

    @Test
    fun three_ground_outs_move_to_bottom_half() {
        val controller = GameController()

        controller.groundOut()
        controller.groundOut()
        controller.groundOut()

        assertEquals(0, controller.state.outs)
        assertEquals(false, controller.state.topOfInning)
        assertEquals("Home Player 1", controller.snapshot.currentBatterName)
    }

    @Test
    fun load_bases_for_testing_fills_all_bases() {
        val controller = GameController()

        controller.loadBasesForTesting()

        assertEquals(Base.FIRST, controller.state.bases.first?.base)
        assertEquals(Base.SECOND, controller.state.bases.second?.base)
        assertEquals(Base.THIRD, controller.state.bases.third?.base)
    }

    @Test
    fun reset_game_clears_bases_and_count() {
        val controller = GameController()

        controller.ball()
        controller.single()
        controller.loadBasesForTesting()

        controller.resetGame()

        assertEquals(0, controller.state.balls)
        assertEquals(0, controller.state.strikes)
        assertTrue(controller.state.bases.isEmpty())
        assertEquals(0, controller.state.awayBatterIndex)
    }
}