package com.pgdevhouse.diamondflow.model

data class GameState(
    val gameId: String = "",
    val gameDate: String = "",
    val ballpark: String = "",
    val location: String = "",
    val notes: String = "",
    val startedAtEpochMillis: Long = 0L,
    val endedAtEpochMillis: Long? = null,

    val balls: Int = 0,
    val strikes: Int = 0,
    val outs: Int = 0,

    val maxInnings: Int = 9,
    val currentInning: Int = 1,
    val topOfInning: Boolean = true,

    val activeTeam: Team = Team.AWAY,

    val bases: Bases = Bases(),

    val awayTeamName: String = "Away",
    val homeTeamName: String = "Home",

    val awayPitcherName: String = "Away Pitcher",
    val homePitcherName: String = "Home Pitcher",

    val playerPositions: Map<Int, String> = emptyMap(),

    val lineupAway: List<Player> = emptyList(),
    val lineupHome: List<Player> = emptyList(),

    val scores: Map<Team, List<Int>> = mapOf(
        Team.AWAY to List(9) { 0 },
        Team.HOME to List(9) { 0 }
    ),

    val hits: Map<Team, Int> = mapOf(
        Team.AWAY to 0,
        Team.HOME to 0
    ),

    val errors: Map<Team, Int> = mapOf(
        Team.AWAY to 0,
        Team.HOME to 0
    ),

    val awayBatterIndex: Int = 0,
    val homeBatterIndex: Int = 0,

    // When a mid-game start has unknown identities, InningTrack gives the active
    // unknown batter/runner a synthetic negative player ID. Those IDs let events
    // and stats be reassigned later when the real player becomes known.
    val awayUnknownBatterId: Int? = null,
    val homeUnknownBatterId: Int? = null,
    val nextUnknownPlayerId: Int = -1,

    // Runs that were already on the board before InningTrack began tracking.
    // They count in the game total without pretending we know which inning scored them.
    val carryInRuns: Map<Team, Int> = mapOf(
        Team.AWAY to 0,
        Team.HOME to 0
    ),
    val trackingStartedMidGame: Boolean = false,

    val events: List<GameEvent> = emptyList(),
    val nextEventId: Long = 1L,

    val gameOver: Boolean = false,
    val gameEndedManually: Boolean = false
) {
    fun totalRuns(team: Team): Int = (carryInRuns[team] ?: 0) + (scores[team]?.sum() ?: 0)

    fun teamName(team: Team): String = when (team) {
        Team.AWAY -> awayTeamName
        Team.HOME -> homeTeamName
    }

    fun pitcherName(team: Team): String = when (team) {
        Team.AWAY -> awayPitcherName
        Team.HOME -> homePitcherName
    }

    fun playerName(playerId: Int): String? {
        if (playerId < 0) return "Unknown"
        return (lineupAway + lineupHome)
            .firstOrNull { it.id == playerId }
            ?.name
    }

    fun playerPosition(playerId: Int): String? = playerPositions[playerId]
}
