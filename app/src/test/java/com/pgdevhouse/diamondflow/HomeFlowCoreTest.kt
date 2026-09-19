package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.model.GameEventType
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFlowCoreTest {

    @Test
    fun new_game_preserves_wizard_metadata_and_pitchers() {
        val state = GameController.newGame(
            awayTeamName = "Visitors",
            homeTeamName = "Home Club",
            maxInnings = 7,
            awayLineup = listOf(Player(1, "A")),
            homeLineup = listOf(Player(1001, "H")),
            gameDate = "Sep 19, 2026",
            ballpark = "Memorial Field",
            location = "Baltimore",
            notes = "Tournament game",
            awayPitcherName = "Alex",
            homePitcherName = "Jordan"
        )

        assertTrue(state.gameId.isNotBlank())
        assertEquals("Sep 19, 2026", state.gameDate)
        assertEquals("Memorial Field", state.ballpark)
        assertEquals("Baltimore", state.location)
        assertEquals("Tournament game", state.notes)
        assertEquals("Alex", state.awayPitcherName)
        assertEquals("Jordan", state.homePitcherName)
    }

    @Test
    fun ending_game_records_completion_time() {
        val controller = GameController()
        controller.endGame()

        assertTrue(controller.state.gameOver)
        assertNotNull(controller.state.endedAtEpochMillis)
    }

    @Test
    fun lineup_reorder_keeps_current_batter_identity() {
        val controller = GameController()
        val current = controller.state.lineupAway[controller.state.awayBatterIndex]

        controller.reorderLineup(Team.AWAY, 0, 4)

        assertEquals(current.id, controller.state.lineupAway[controller.state.awayBatterIndex].id)
        assertEquals(GameEventType.LINEUP_REORDER, controller.state.events.last().type)
    }
}
