package com.pgdevhouse.diamondflow.model

enum class GameEventType {
    PITCH,
    PLAY,
    BASERUNNING,
    FIELDING_CREDIT,
    BATTER_EDIT,
    PLAYER_EDIT,
    PINCH_HITTER,
    PINCH_RUNNER,
    PITCHING_CHANGE,
    POSITION_CHANGE,
    TEAM_EDIT,
    LINEUP_REORDER,
    GAME_STATE_EDIT,
    GAME_CORRECTION,
    GAME_END
}

data class GameEvent(
    val id: Long,
    val inning: Int,
    val topOfInning: Boolean,
    val battingTeam: Team,
    val type: GameEventType,
    val title: String,
    val detail: String = "",
    val actorPlayerId: Int? = null,
    val actorName: String? = null,
    val replacedPlayerId: Int? = null,
    val replacedPlayerName: String? = null,
    val team: Team? = null,
    val base: Base? = null,
    val position: String? = null,
    val pitcherName: String? = null,
    val playAction: PlayAction? = null,
    val pitchAction: PitchAction? = null,
    val baseRunningAction: BaseRunningAction? = null,
    val fromBase: Base? = null,
    val toBase: Base? = null,
    val outsRecorded: Int = 0,
    val runsScored: Int = 0,
    val scoredPlayerIds: List<Int> = emptyList(),
    val scoredRuns: List<ScoredRun> = emptyList(),
    val putoutPlayerName: String? = null,
    val assistPlayerNames: List<String> = emptyList(),
    val errorPlayerName: String? = null,
    val inheritedRunners: Int = 0
) {
    val isPersonnelEvent: Boolean
        get() = type == GameEventType.BATTER_EDIT ||
            type == GameEventType.PLAYER_EDIT ||
            type == GameEventType.PINCH_HITTER ||
            type == GameEventType.PINCH_RUNNER ||
            type == GameEventType.PITCHING_CHANGE ||
            type == GameEventType.POSITION_CHANGE ||
            type == GameEventType.TEAM_EDIT
}
