package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.engine.InningEngine
import com.pgdevhouse.diamondflow.engine.OutEngine
import com.pgdevhouse.diamondflow.logic.GameEngine
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MvpGameFlowTest {

    @Test
    fun home_run_in_bottom_of_final_inning_is_walkoff() {
        val engine = GameEngine()
        val state = GameState(
            maxInnings = 1,
            currentInning = 1,
            topOfInning = false,
            activeTeam = Team.HOME,
            lineupHome = listOf(Player(1001, "Home Batter")),
            scores = mapOf(
                Team.AWAY to listOf(0),
                Team.HOME to listOf(0)
            )
        )

        val result = engine.applyPlay(
            state,
            Play(PlayAction.HOME_RUN, batterId = 1001)
        )

        assertTrue(result.gameOver)
        assertEquals(1, result.totalRuns(Team.HOME))
    }

    @Test
    fun tied_game_moves_to_extra_inning() {
        val state = GameState(
            maxInnings = 1,
            currentInning = 1,
            topOfInning = false,
            activeTeam = Team.HOME,
            outs = 3,
            scores = mapOf(
                Team.AWAY to listOf(2),
                Team.HOME to listOf(2)
            )
        )

        val result = InningEngine.apply(state)

        assertFalse(result.gameOver)
        assertEquals(2, result.currentInning)
        assertTrue(result.topOfInning)
        assertEquals(Team.AWAY, result.activeTeam)
    }

    @Test
    fun undo_restores_entire_previous_state() {
        val controller = GameController(
            GameController.newGame(
                awayTeamName = "Away",
                homeTeamName = "Home",
                maxInnings = 7,
                awayLineup = listOf(Player(1, "A")),
                homeLineup = listOf(Player(1001, "H"))
            )
        )

        controller.single()
        assertEquals(1, controller.state.hits[Team.AWAY])
        assertTrue(controller.canUndo)

        assertTrue(controller.undo())
        assertEquals(0, controller.state.hits[Team.AWAY])
        assertTrue(controller.state.bases.isEmpty())
        assertEquals(0, controller.state.awayBatterIndex)
    }

    @Test
    fun errors_are_charged_to_fielding_team() {
        val engine = GameEngine()
        val result = engine.applyPlay(
            GameState(activeTeam = Team.AWAY),
            Play(PlayAction.ERROR, batterId = 1)
        )

        assertEquals(1, result.errors[Team.HOME])
        assertEquals(0, result.errors[Team.AWAY])
    }

    @Test
    fun outs_never_exceed_three() {
        val result = OutEngine.apply(
            GameState(outs = 2),
            Play(PlayAction.GROUND_OUT, outsRecorded = 3)
        )

        assertEquals(3, result.outs)
    }
}
