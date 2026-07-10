package com.pgdevhouse.diamondflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameSnapshotTest {

    @Test
    fun top_half_inning_label_is_correct() {
        val state = GameState(
            currentInning = 3,
            topOfInning = true
        )

        val snapshot = GameSnapshot.from(state)

        assertEquals("Top 3", snapshot.inningLabel)
    }

    @Test
    fun bottom_half_inning_label_is_correct() {
        val state = GameState(
            currentInning = 5,
            topOfInning = false
        )

        val snapshot = GameSnapshot.from(state)

        assertEquals("Bottom 5", snapshot.inningLabel)
    }

    @Test
    fun score_label_sums_team_runs() {
        val state = GameState(
            scores = mapOf(
                Team.AWAY to listOf(1, 2, 0, 0, 0, 0, 0, 0, 0),
                Team.HOME to listOf(0, 3, 1, 0, 0, 0, 0, 0, 0)
            )
        )

        val snapshot = GameSnapshot.from(state)

        assertEquals("Away 3 - Home 4", snapshot.scoreLabel)
    }

    @Test
    fun count_label_is_balls_dash_strikes() {
        val state = GameState(
            balls = 2,
            strikes = 1
        )

        val snapshot = GameSnapshot.from(state)

        assertEquals("2-1", snapshot.countLabel)
    }

    @Test
    fun outs_label_uses_singular_for_one_out() {
        val state = GameState(
            outs = 1
        )

        val snapshot = GameSnapshot.from(state)

        assertEquals("1 out", snapshot.outsLabel)
    }

    @Test
    fun outs_label_uses_plural_for_zero_or_multiple_outs() {
        val zeroOutSnapshot = GameSnapshot.from(
            GameState(outs = 0)
        )

        val twoOutSnapshot = GameSnapshot.from(
            GameState(outs = 2)
        )

        assertEquals("0 outs", zeroOutSnapshot.outsLabel)
        assertEquals("2 outs", twoOutSnapshot.outsLabel)
    }

    @Test
    fun bases_label_shows_empty_bases() {
        val snapshot = GameSnapshot.from(
            GameState(
                bases = Bases()
            )
        )

        assertEquals("Bases empty", snapshot.basesLabel)
    }

    @Test
    fun bases_label_shows_occupied_bases() {
        val state = GameState(
            bases = Bases(
                first = Runner(1, Base.FIRST),
                third = Runner(3, Base.THIRD)
            )
        )

        val snapshot = GameSnapshot.from(state)

        assertEquals("1B, 3B", snapshot.basesLabel)
    }

    @Test
    fun current_batter_name_comes_from_active_lineup() {
        val state = GameState(
            activeTeam = Team.AWAY,
            awayBatterIndex = 1,
            lineupAway = listOf(
                Player(id = 1, name = "Alice"),
                Player(id = 2, name = "Bob")
            )
        )

        val snapshot = GameSnapshot.from(state)

        assertEquals("Bob", snapshot.currentBatterName)
    }

    @Test
    fun current_batter_name_is_null_when_lineup_is_empty() {
        val state = GameState(
            activeTeam = Team.AWAY,
            lineupAway = emptyList()
        )

        val snapshot = GameSnapshot.from(state)

        assertNull(snapshot.currentBatterName)
    }
}