package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.BaseRunningAction
import com.pgdevhouse.diamondflow.model.CompletedGame
import com.pgdevhouse.diamondflow.model.FieldingPlay
import com.pgdevhouse.diamondflow.model.GameStats
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V1HardeningTest {

    @Test
    fun intentional_walk_counts_as_walk_but_not_at_bat() {
        val c = GameController()
        c.play(PlayAction.INTENTIONAL_WALK)
        val s = GameStats.batting(c.state, Team.AWAY).first()
        assertEquals(1, s.plateAppearances)
        assertEquals(0, s.atBats)
        assertEquals(1, s.walks)
        assertEquals(1, s.intentionalWalks)
    }

    @Test
    fun stolen_base_does_not_advance_batting_order() {
        val c = GameController()
        c.play(PlayAction.SINGLE)
        val batterIndex = c.state.awayBatterIndex
        val runner = c.state.bases.first!!.playerId
        c.recordBaseRunningEvent(BaseRunningAction.STOLEN_BASE, runner, Base.SECOND)
        assertEquals(batterIndex, c.state.awayBatterIndex)
        assertEquals(runner, c.state.bases.second!!.playerId)
        val first = GameStats.batting(c.state, Team.AWAY).first()
        assertEquals(1, first.stolenBases)
    }

    @Test
    fun caught_stealing_records_defensive_out() {
        val c = GameController()
        c.play(PlayAction.SINGLE)
        val runner = c.state.bases.first!!.playerId
        c.recordBaseRunningEvent(BaseRunningAction.CAUGHT_STEALING, runner, null, "Catcher")
        assertEquals(1, c.state.outs)
        assertTrue(c.state.bases.isEmpty())
        val pitcher = GameStats.pitching(c.state, Team.HOME).first()
        assertEquals(1, pitcher.outsRecorded)
    }

    @Test
    fun inherited_runner_is_charged_to_pitcher_who_allowed_him() {
        val c = GameController()
        c.pitchingChange(Team.HOME, "Starter")
        c.play(PlayAction.SINGLE)
        c.pitchingChange(Team.HOME, "Reliever")
        val runner = c.state.bases.first!!.playerId
        c.play(
            action = PlayAction.DOUBLE,
            manualRunnerDestinations = mapOf(runner to Base.HOME)
        )
        val stats = GameStats.pitching(c.state, Team.HOME).associateBy { it.pitcherName }
        assertEquals(1, stats.getValue("Starter").runsAllowed)
        assertEquals(1, stats.getValue("Starter").earnedRuns)
        assertEquals(1, stats.getValue("Reliever").inheritedRunners)
        assertEquals(1, stats.getValue("Reliever").inheritedRunnersScored)
    }

    @Test
    fun runner_reaching_on_error_defaults_to_unearned_if_he_scores() {
        val c = GameController()
        c.pitchingChange(Team.HOME, "Pitcher")
        c.play(PlayAction.ERROR)
        val runner = c.state.bases.first!!.playerId
        c.play(PlayAction.DOUBLE, manualRunnerDestinations = mapOf(runner to Base.HOME))
        val p = GameStats.pitching(c.state, Team.HOME).first { it.pitcherName == "Pitcher" }
        assertEquals(1, p.runsAllowed)
        assertEquals(0, p.earnedRuns)
    }

    @Test
    fun fielding_credit_builds_putout_assist_and_error_stats() {
        val c = GameController()
        c.play(
            action = PlayAction.GROUND_OUT,
            fielding = FieldingPlay(putoutPlayerName = "First", assistPlayerNames = listOf("Short"))
        )
        c.play(
            action = PlayAction.ERROR,
            fielding = FieldingPlay(errorPlayerName = "Third")
        )
        val stats = GameStats.fielding(c.state, Team.HOME).associateBy { it.playerName }
        assertEquals(1, stats.getValue("First").putouts)
        assertEquals(1, stats.getValue("Short").assists)
        assertEquals(1, stats.getValue("Third").errors)
    }

    @Test
    fun redo_restores_undone_state() {
        val c = GameController()
        c.ball()
        assertEquals(1, c.state.balls)
        assertTrue(c.undo())
        assertEquals(0, c.state.balls)
        assertTrue(c.canRedo)
        assertTrue(c.redo())
        assertEquals(1, c.state.balls)
        assertFalse(c.canRedo)
    }

    @Test
    fun double_play_defaults_to_two_outs() {
        val c = GameController()
        c.play(PlayAction.DOUBLE_PLAY)
        assertEquals(2, c.state.outs)
    }

    @Test
    fun postgame_scoring_correction_recalculates_hit_and_error_totals() {
        val c = GameController()
        c.play(PlayAction.SINGLE)
        c.endGame()
        val event = c.state.events.first { it.playAction == PlayAction.SINGLE }
        c.correctRecordedEvent(event.id, playAction = PlayAction.ERROR)
        assertEquals(0, c.state.hits[Team.AWAY])
        assertEquals(1, c.state.errors[Team.HOME])
    }
}
