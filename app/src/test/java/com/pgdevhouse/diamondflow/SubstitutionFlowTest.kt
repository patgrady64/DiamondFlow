package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.engine.LineupEngine
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubstitutionFlowTest {

    @Test
    fun editing_batter_name_keeps_same_player_identity() {
        val controller = GameController()
        val before = LineupEngine.currentBatter(controller.state)!!

        controller.renameCurrentBatter("Connor")

        val after = LineupEngine.currentBatter(controller.state)!!
        assertEquals(before.id, after.id)
        assertEquals("Connor", after.name)
    }

    @Test
    fun pinch_hitter_replaces_current_lineup_spot() {
        val controller = GameController()
        val before = LineupEngine.currentBatter(controller.state)!!

        controller.pinchHit("Max")

        val after = LineupEngine.currentBatter(controller.state)!!
        assertEquals("Max", after.name)
        assertNotEquals(before.id, after.id)
        assertEquals("Max", controller.state.lineupAway.first().name)
    }

    @Test
    fun pinch_runner_replaces_runner_and_lineup_spot() {
        val controller = GameController()
        controller.single()
        val oldRunnerId = controller.state.bases.first!!.playerId

        controller.pinchRun(Base.FIRST, "Mom")

        val newRunnerId = controller.state.bases.first!!.playerId
        assertNotEquals(oldRunnerId, newRunnerId)
        assertEquals("Mom", controller.state.playerName(newRunnerId))
        assertEquals("Mom", controller.state.lineupAway.first().name)
    }

    @Test
    fun pitching_change_updates_fielding_team_and_is_undoable() {
        val controller = GameController(
            GameController.newGame(
                awayTeamName = "Visitors",
                homeTeamName = "Hosts",
                maxInnings = 7,
                awayLineup = listOf(Player(1, "Away Batter")),
                homeLineup = listOf(Player(1001, "Home Batter"))
            )
        )

        controller.pitchingChange("Reliever")
        assertEquals("Reliever", controller.state.homePitcherName)
        assertTrue(controller.undo())
        assertEquals("Hosts Pitcher", controller.state.homePitcherName)
    }
}
