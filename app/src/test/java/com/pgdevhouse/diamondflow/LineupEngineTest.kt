package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineupEngineTest {

    @Test
    fun empty_away_lineup_does_not_change_index() {
        val state = GameState(
            activeTeam = Team.AWAY,
            awayBatterIndex = 0,
            lineupAway = emptyList()
        )

        val result = LineupEngine.apply(state)

        assertEquals(0, result.awayBatterIndex)
    }

    @Test
    fun away_team_advances_away_batter_index() {
        val state = GameState(
            activeTeam = Team.AWAY,
            awayBatterIndex = 0,
            homeBatterIndex = 0,
            lineupAway = testLineup()
        )

        val result = LineupEngine.apply(state)

        assertEquals(1, result.awayBatterIndex)
        assertEquals(0, result.homeBatterIndex)
    }

    @Test
    fun home_team_advances_home_batter_index() {
        val state = GameState(
            activeTeam = Team.HOME,
            awayBatterIndex = 0,
            homeBatterIndex = 0,
            lineupHome = testLineup()
        )

        val result = LineupEngine.apply(state)

        assertEquals(0, result.awayBatterIndex)
        assertEquals(1, result.homeBatterIndex)
    }

    @Test
    fun batter_index_wraps_to_zero_after_last_batter() {
        val state = GameState(
            activeTeam = Team.AWAY,
            awayBatterIndex = 8,
            lineupAway = testLineup()
        )

        val result = LineupEngine.apply(state)

        assertEquals(0, result.awayBatterIndex)
    }

    @Test
    fun current_batter_returns_player_at_active_team_index() {
        val lineup = testLineup()

        val state = GameState(
            activeTeam = Team.AWAY,
            awayBatterIndex = 2,
            lineupAway = lineup
        )

        val batter = LineupEngine.currentBatter(state)

        assertEquals(3, batter?.id)
        assertEquals("Player 3", batter?.name)
    }

    @Test
    fun current_batter_returns_null_when_lineup_is_empty() {
        val state = GameState(
            activeTeam = Team.AWAY,
            lineupAway = emptyList()
        )

        val batter = LineupEngine.currentBatter(state)

        assertNull(batter)
    }

    private fun testLineup(): List<Player> {
        return listOf(
            Player(id = 1, name = "Player 1"),
            Player(id = 2, name = "Player 2"),
            Player(id = 3, name = "Player 3"),
            Player(id = 4, name = "Player 4"),
            Player(id = 5, name = "Player 5"),
            Player(id = 6, name = "Player 6"),
            Player(id = 7, name = "Player 7"),
            Player(id = 8, name = "Player 8"),
            Player(id = 9, name = "Player 9")
        )
    }
}