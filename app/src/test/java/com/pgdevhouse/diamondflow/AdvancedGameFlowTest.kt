package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.engine.LineupEngine
import com.pgdevhouse.diamondflow.model.GameStats
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedGameFlowTest {

    @Test
    fun defaultBattingOrderUsesDhAndKeepsPitcherSeparate() {
        val state = GameController.defaultState()
        val positions = state.lineupAway.map { state.playerPosition(it.id) }
        assertEquals(listOf("C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH"), positions)
        assertFalse(state.playerPositions.values.contains("P"))
    }

    @Test
    fun midGameCanLeaveBattersUnknownUntilNeeded() {
        val controller = GameController(GameController.defaultState())
        controller.setGameSituation(
            inning = 5,
            topOfInning = true,
            balls = 1,
            strikes = 2,
            outs = 1,
            awayScores = listOf(0, 1, 0, 0, 0),
            homeScores = listOf(0, 0, 0, 0, 0),
            awayBatterId = null,
            homeBatterId = null,
            firstRunnerId = null,
            secondRunnerId = null,
            thirdRunnerId = null
        )
        val unknown = LineupEngine.currentBatter(controller.state)
        assertTrue(unknown != null && unknown.id < 0)
        val selected = controller.state.lineupAway[4]
        controller.setCurrentBatter(Team.AWAY, selected.id)
        assertEquals(selected.id, LineupEngine.currentBatter(controller.state)?.id)
    }

    @Test
    fun battingAndPitchingStatsTrackRecordedGameEvents() {
        val initial = GameController.newGame(
            awayTeamName = "Away",
            homeTeamName = "Home",
            maxInnings = 9,
            awayLineup = GameController.defaultLineup("Away", 1),
            homeLineup = GameController.defaultLineup("Home", 1001),
            awayPitcherName = "Away Pitcher",
            homePitcherName = "Home Pitcher"
        )
        val controller = GameController(initial)
        val firstBatter = controller.state.lineupAway.first()
        controller.single()
        repeat(4) { controller.ball() }

        val hitter = GameStats.batting(controller.state, Team.AWAY).first { it.playerId == firstBatter.id }
        assertEquals(1, hitter.hits)
        assertEquals(1, hitter.atBats)

        val pitcher = GameStats.pitching(controller.state, Team.HOME).first { it.pitcherName == "Home Pitcher" }
        assertEquals(2, pitcher.battersFaced)
        assertEquals(1, pitcher.hitsAllowed)
        assertEquals(1, pitcher.walks)
        assertEquals(4, pitcher.pitches)
    }
}
