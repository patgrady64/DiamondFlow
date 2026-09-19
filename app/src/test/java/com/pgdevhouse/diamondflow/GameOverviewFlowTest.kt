package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.GameEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOverviewFlowTest {

    @Test
    fun pitches_plays_and_substitutions_are_logged_in_order() {
        val controller = GameController()

        controller.ball()
        controller.strike()
        controller.single()
        controller.pinchRun(Base.FIRST, "Runner Two")

        assertEquals(4, controller.state.events.size)
        assertEquals(GameEventType.PITCH, controller.state.events[0].type)
        assertEquals(GameEventType.PLAY, controller.state.events[2].type)
        assertEquals(GameEventType.PINCH_RUNNER, controller.state.events[3].type)
    }

    @Test
    fun undo_removes_last_logged_event_with_state() {
        val controller = GameController()
        controller.ball()
        assertEquals(1, controller.state.events.size)

        assertTrue(controller.undo())
        assertTrue(controller.state.events.isEmpty())
        assertEquals(0, controller.state.balls)
    }

    @Test
    fun position_change_updates_current_position_and_can_be_corrected() {
        val controller = GameController()
        val playerId = controller.state.lineupHome.first().id

        controller.positionChange(playerId, "SS")
        val event = controller.state.events.last()
        assertEquals("SS", controller.state.playerPosition(playerId))
        assertEquals(GameEventType.POSITION_CHANGE, event.type)

        controller.editPersonnelEvent(event.id, "3B")
        assertEquals("3B", controller.state.playerPosition(playerId))
        assertEquals("3B", controller.state.events.last().position)
    }

    @Test
    fun historical_pitcher_correction_updates_current_pitcher_when_it_is_latest() {
        val controller = GameController()
        controller.pitchingChange("Reliever A")
        val event = controller.state.events.last()

        controller.editPersonnelEvent(event.id, "Reliever B")

        assertEquals("Reliever B", controller.state.homePitcherName)
        assertEquals("Reliever B", controller.state.events.last().actorName)
    }
}
