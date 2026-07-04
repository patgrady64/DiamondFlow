package com.pgdevhouse.diamondflow.model

data class GameState(
    val balls: Int = 0,
    val strikes: Int = 0,
    val outs: Int = 0,

    val maxInnings: Int = 9,
    val currentInning: Int = 1,
    val topOfInning: Boolean = true,

    val activeTeam: Team = Team.AWAY,

    val bases: Bases = Bases(),

    val lineupAway: List<Player> = emptyList(),
    val lineupHome: List<Player> = emptyList(),

    val scores: Map<Team, List<Int>> = mapOf(
        Team.AWAY to List(9) { 0 },
        Team.HOME to List(9) { 0 }
    )
)