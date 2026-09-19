package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.engine.LineupEngine
import com.pgdevhouse.diamondflow.model.GameStats
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidGameRecoveryTest {

    @Test
    fun score_before_tracking_is_kept_separate_from_inning_scores() {
        val controller = GameController(GameController.defaultState())
        controller.setGameSituation(
            inning = 5,
            topOfInning = true,
            balls = 0,
            strikes = 0,
            outs = 1,
            awayScore = 5,
            homeScore = 3,
            awayBatterId = null,
            homeBatterId = null,
            firstRunnerId = null,
            secondRunnerId = null,
            thirdRunnerId = null
        )

        assertEquals(5, controller.state.totalRuns(Team.AWAY))
        assertEquals(3, controller.state.totalRuns(Team.HOME))
        assertEquals(5, controller.state.carryInRuns[Team.AWAY])
        assertEquals(0, controller.state.scores[Team.AWAY]?.sum())
    }

    @Test
    fun unknown_batter_stats_can_be_reassigned_to_real_player() {
        val controller = GameController(GameController.defaultState())
        controller.setGameSituation(
            inning = 4,
            topOfInning = true,
            balls = 0,
            strikes = 0,
            outs = 0,
            awayScore = 2,
            homeScore = 1,
            awayBatterId = null,
            homeBatterId = null,
            firstRunnerId = null,
            secondRunnerId = null,
            thirdRunnerId = null
        )
        val unknown = LineupEngine.currentBatter(controller.state)!!
        assertTrue(unknown.id < 0)

        controller.single()
        assertEquals(1, GameStats.batting(controller.state, Team.AWAY).first { it.playerId == unknown.id }.hits)

        val real = controller.state.lineupAway[3]
        controller.resolveUnknownPlayer(Team.AWAY, unknown.id, real.id)

        assertEquals(1, GameStats.batting(controller.state, Team.AWAY).first { it.playerId == real.id }.hits)
        assertTrue(GameStats.batting(controller.state, Team.AWAY).none { it.playerId == unknown.id })
    }

    @Test
    fun unknown_runner_can_be_identified_later() {
        val controller = GameController(GameController.defaultState())
        controller.setGameSituation(
            inning = 6,
            topOfInning = false,
            balls = 1,
            strikes = 1,
            outs = 1,
            awayScore = 4,
            homeScore = 4,
            awayBatterId = null,
            homeBatterId = null,
            firstRunnerId = GameController.UNKNOWN_PLAYER_SELECTION,
            secondRunnerId = null,
            thirdRunnerId = null
        )
        assertTrue(controller.state.bases.first!!.playerId < 0)

        val runner = controller.state.lineupHome[6]
        controller.setGameSituation(
            inning = 6,
            topOfInning = false,
            balls = 1,
            strikes = 1,
            outs = 1,
            awayScore = 4,
            homeScore = 4,
            awayBatterId = null,
            homeBatterId = null,
            firstRunnerId = runner.id,
            secondRunnerId = null,
            thirdRunnerId = null
        )

        assertEquals(runner.id, controller.state.bases.first?.playerId)
    }
}
