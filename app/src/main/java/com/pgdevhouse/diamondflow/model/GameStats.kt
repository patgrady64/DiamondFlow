package com.pgdevhouse.diamondflow.model

data class BattingStats(
    val playerId: Int,
    val playerName: String,
    val plateAppearances: Int = 0,
    val atBats: Int = 0,
    val runs: Int = 0,
    val hits: Int = 0,
    val doubles: Int = 0,
    val triples: Int = 0,
    val homeRuns: Int = 0,
    val rbi: Int = 0,
    val walks: Int = 0,
    val intentionalWalks: Int = 0,
    val hitByPitch: Int = 0,
    val strikeouts: Int = 0,
    val sacrificeBunts: Int = 0,
    val sacrificeFlies: Int = 0,
    val fieldersChoices: Int = 0,
    val reachedOnError: Int = 0,
    val groundedIntoDoublePlay: Int = 0,
    val stolenBases: Int = 0,
    val caughtStealing: Int = 0,
    val pickedOff: Int = 0
) {
    val sacrifices: Int get() = sacrificeBunts + sacrificeFlies

    val average: Double?
        get() = if (atBats == 0) null else hits.toDouble() / atBats

    val onBasePercentage: Double?
        get() {
            val denominator = atBats + walks + hitByPitch + sacrificeFlies
            return if (denominator == 0) null else (hits + walks + hitByPitch).toDouble() / denominator
        }

    val totalBases: Int
        get() = (hits - doubles - triples - homeRuns) + doubles * 2 + triples * 3 + homeRuns * 4

    val slugging: Double?
        get() = if (atBats == 0) null else totalBases.toDouble() / atBats

    val ops: Double?
        get() = if (onBasePercentage == null || slugging == null) null else onBasePercentage!! + slugging!!
}

data class PitchingStats(
    val pitcherName: String,
    val team: Team,
    val battersFaced: Int = 0,
    val pitches: Int = 0,
    val balls: Int = 0,
    val strikes: Int = 0,
    val outsRecorded: Int = 0,
    val hitsAllowed: Int = 0,
    val runsAllowed: Int = 0,
    val earnedRuns: Int = 0,
    val walks: Int = 0,
    val intentionalWalks: Int = 0,
    val strikeouts: Int = 0,
    val hitBatters: Int = 0,
    val homeRunsAllowed: Int = 0,
    val wildPitches: Int = 0,
    val balks: Int = 0,
    val inheritedRunners: Int = 0,
    val inheritedRunnersScored: Int = 0
) {
    val inningsPitchedDisplay: String
        get() = "${outsRecorded / 3}.${outsRecorded % 3}"

    val whip: Double?
        get() {
            val innings = outsRecorded / 3.0
            return if (innings == 0.0) null else (walks + hitsAllowed) / innings
        }

    val era: Double?
        get() {
            val innings = outsRecorded / 3.0
            return if (innings == 0.0) null else earnedRuns * 9.0 / innings
        }

    val strikePercentage: Double?
        get() = if (pitches == 0) null else strikes.toDouble() / pitches
}

data class FieldingStats(
    val playerName: String,
    val team: Team,
    val putouts: Int = 0,
    val assists: Int = 0,
    val errors: Int = 0,
    val doublePlays: Int = 0,
    val triplePlays: Int = 0,
    val passedBalls: Int = 0
)

object GameStats {
    private val hitActions = setOf(
        PlayAction.SINGLE,
        PlayAction.DOUBLE,
        PlayAction.TRIPLE,
        PlayAction.HOME_RUN
    )

    private val atBatActions = setOf(
        PlayAction.SINGLE,
        PlayAction.DOUBLE,
        PlayAction.TRIPLE,
        PlayAction.HOME_RUN,
        PlayAction.STRIKEOUT,
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.FIELDERS_CHOICE,
        PlayAction.ERROR,
        PlayAction.DOUBLE_PLAY,
        PlayAction.TRIPLE_PLAY
    )

    fun batting(state: GameState, team: Team): List<BattingStats> {
        val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
        val playerNames = buildMap<Int, String> {
            lineup.forEach { put(it.id, it.name) }
            state.events.forEach { event ->
                event.actorPlayerId?.let { id -> event.actorName?.let { put(id, it) } }
                event.replacedPlayerId?.takeIf { it >= 0 }?.let { id ->
                    event.replacedPlayerName?.let { putIfAbsent(id, it) }
                }
                event.scoredRuns.forEach { run ->
                    if (run.playerId < 0) putIfAbsent(run.playerId, "Unknown")
                }
            }
        }
        val stats = playerNames.mapValues { (id, name) -> MutableBatting(id, name) }.toMutableMap()

        state.events.forEach { event ->
            if (event.battingTeam != team) return@forEach

            if (event.type == GameEventType.BASERUNNING) {
                val playerId = event.actorPlayerId ?: return@forEach
                val runner = stats.getOrPut(playerId) { MutableBatting(playerId, event.actorName ?: state.playerName(playerId) ?: "Unknown") }
                when (event.baseRunningAction) {
                    BaseRunningAction.STOLEN_BASE -> runner.sb++
                    BaseRunningAction.CAUGHT_STEALING -> runner.cs++
                    BaseRunningAction.PICKOFF -> runner.po++
                    else -> Unit
                }
                addScoredRuns(state, stats, playerNames, event)
                return@forEach
            }

            val action = event.playAction
            if (action != null && isPlateAppearanceEvent(event)) {
                val batterId = event.actorPlayerId ?: return@forEach
                val batter = stats.getOrPut(batterId) { MutableBatting(batterId, event.actorName ?: "Player") }
                batter.pa++
                if (action in atBatActions) batter.ab++
                if (action in hitActions) batter.h++
                when (action) {
                    PlayAction.DOUBLE -> batter.doubles++
                    PlayAction.TRIPLE -> batter.triples++
                    PlayAction.HOME_RUN -> batter.hr++
                    PlayAction.WALK -> batter.bb++
                    PlayAction.INTENTIONAL_WALK -> { batter.bb++; batter.ibb++ }
                    PlayAction.HIT_BY_PITCH -> batter.hbp++
                    PlayAction.STRIKEOUT -> batter.so++
                    PlayAction.SACRIFICE,
                    PlayAction.SACRIFICE_BUNT -> batter.sh++
                    PlayAction.SACRIFICE_FLY -> batter.sf++
                    PlayAction.FIELDERS_CHOICE -> batter.fc++
                    PlayAction.ERROR -> batter.roe++
                    PlayAction.DOUBLE_PLAY -> batter.gidp++
                    else -> Unit
                }
                if (action != PlayAction.ERROR) batter.rbi += event.runsScored
            }
            addScoredRuns(state, stats, playerNames, event)
        }

        val lineupIds = lineup.map(Player::id)
        return stats.values.sortedWith(compareBy<MutableBatting> {
            lineupIds.indexOf(it.playerId).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }.thenBy { it.playerName }).map(MutableBatting::freeze)
    }

    private fun addScoredRuns(
        state: GameState,
        stats: MutableMap<Int, MutableBatting>,
        playerNames: Map<Int, String>,
        event: GameEvent
    ) {
        val ids = if (event.scoredRuns.isNotEmpty()) event.scoredRuns.map(ScoredRun::playerId) else event.scoredPlayerIds
        ids.forEach { scorerId ->
            val scorer = stats.getOrPut(scorerId) {
                MutableBatting(scorerId, playerNames[scorerId] ?: state.playerName(scorerId) ?: "Unknown")
            }
            scorer.r++
        }
    }

    fun pitching(state: GameState, team: Team): List<PitchingStats> {
        fun starterFor(target: Team): String {
            val firstChange = state.events.firstOrNull {
                it.type == GameEventType.PITCHING_CHANGE && it.team == target
            }
            return firstChange?.replacedPlayerName?.takeIf(String::isNotBlank)
                ?: state.pitcherName(target)
        }

        val activePitcher = mutableMapOf(
            Team.AWAY to starterFor(Team.AWAY),
            Team.HOME to starterFor(Team.HOME)
        )
        val mutable = linkedMapOf<String, MutablePitching>()

        fun pitcher(name: String): MutablePitching = mutable.getOrPut(name) { MutablePitching(name, team) }

        state.events.forEach { event ->
            if (event.type == GameEventType.PITCHING_CHANGE && event.team != null) {
                event.actorName?.takeIf(String::isNotBlank)?.let { incoming ->
                    activePitcher[event.team] = incoming
                    if (event.team == team) pitcher(incoming).inherited += event.inheritedRunners
                }
                return@forEach
            }

            val fieldingTeam = if (event.battingTeam == Team.AWAY) Team.HOME else Team.AWAY
            if (fieldingTeam != team) return@forEach
            val currentName = event.pitcherName?.takeIf(String::isNotBlank)
                ?: activePitcher[fieldingTeam]?.takeIf(String::isNotBlank)
            val current = currentName?.let(::pitcher)

            if (event.type == GameEventType.PITCH && current != null) {
                current.pitches++
                when (event.pitchAction) {
                    PitchAction.BALL -> current.balls++
                    PitchAction.STRIKE,
                    PitchAction.FOUL -> current.strikes++
                    PitchAction.HIT_BY_PITCH -> current.balls++
                    null -> Unit
                }
            }

            val action = event.playAction
            if (action != null && isPlateAppearanceEvent(event) && current != null) {
                current.bf++
                current.outs += event.outsRecorded
                if (action in hitActions) current.hits++
                when (action) {
                    PlayAction.WALK -> current.bb++
                    PlayAction.INTENTIONAL_WALK -> { current.bb++; current.ibb++ }
                    PlayAction.STRIKEOUT -> current.so++
                    PlayAction.HIT_BY_PITCH -> current.hbp++
                    PlayAction.HOME_RUN -> current.hr++
                    else -> Unit
                }
            }

            if (event.type == GameEventType.BASERUNNING && current != null) {
                current.outs += event.outsRecorded
                when (event.baseRunningAction) {
                    BaseRunningAction.WILD_PITCH -> current.wp++
                    BaseRunningAction.BALK -> current.balks++
                    else -> Unit
                }
            }

            val credits = when {
                event.scoredRuns.isNotEmpty() -> event.scoredRuns
                event.runsScored > 0 && currentName != null -> List(event.runsScored) {
                    ScoredRun(playerId = event.scoredPlayerIds.getOrNull(it) ?: -1, responsiblePitcherName = currentName, earned = true)
                }
                else -> emptyList()
            }
            credits.forEach runLoop@ { run ->
                val responsibleName = run.responsiblePitcherName?.takeIf(String::isNotBlank) ?: currentName ?: return@runLoop
                val responsible = pitcher(responsibleName)
                responsible.runs++
                if (run.earned) responsible.er++
                if (currentName != null && responsibleName != currentName) current?.inheritedScored = (current?.inheritedScored ?: 0) + 1
            }
        }

        if (mutable.isEmpty()) {
            val name = starterFor(team)
            if (name.isNotBlank()) mutable[name] = MutablePitching(name, team)
        }
        return mutable.values.map(MutablePitching::freeze)
    }

    fun fielding(state: GameState, team: Team): List<FieldingStats> {
        val names = linkedSetOf<String>()
        val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
        lineup.forEach { names += it.name }
        state.pitcherName(team).takeIf(String::isNotBlank)?.let(names::add)
        val stats = linkedMapOf<String, MutableFielding>()
        fun fielder(name: String): MutableFielding = stats.getOrPut(name) { MutableFielding(name, team) }
        names.forEach(::fielder)

        state.events.forEach { event ->
            val fieldingTeam = if (event.battingTeam == Team.AWAY) Team.HOME else Team.AWAY
            if (fieldingTeam != team) return@forEach
            event.putoutPlayerName?.takeIf(String::isNotBlank)?.let { fielder(it).po++ }
            event.assistPlayerNames.filter(String::isNotBlank).distinct().forEach { fielder(it).a++ }
            if (event.playAction == PlayAction.ERROR || event.type == GameEventType.FIELDING_CREDIT) {
                event.errorPlayerName?.takeIf(String::isNotBlank)?.let { fielder(it).e++ }
            }
            if (event.playAction == PlayAction.DOUBLE_PLAY) {
                (event.assistPlayerNames + listOfNotNull(event.putoutPlayerName)).distinct().forEach { fielder(it).dp++ }
            }
            if (event.playAction == PlayAction.TRIPLE_PLAY) {
                (event.assistPlayerNames + listOfNotNull(event.putoutPlayerName)).distinct().forEach { fielder(it).tp++ }
            }
            if (event.type == GameEventType.BASERUNNING && event.baseRunningAction == BaseRunningAction.PASSED_BALL) {
                val catcher = event.errorPlayerName ?: lineup.firstOrNull { state.playerPosition(it.id) == "C" }?.name
                catcher?.let { fielder(it).pb++ }
            }
        }
        return stats.values.filter { it.po + it.a + it.e + it.dp + it.tp + it.pb > 0 }.map(MutableFielding::freeze)
    }

    private fun isPlateAppearanceEvent(event: GameEvent): Boolean {
        if (event.playAction == null) return false
        return event.type == GameEventType.PLAY ||
            (event.type == GameEventType.PITCH && event.playAction in setOf(
                PlayAction.WALK,
                PlayAction.INTENTIONAL_WALK,
                PlayAction.STRIKEOUT,
                PlayAction.HIT_BY_PITCH
            ))
    }

    private data class MutableBatting(
        val playerId: Int,
        var playerName: String,
        var pa: Int = 0,
        var ab: Int = 0,
        var r: Int = 0,
        var h: Int = 0,
        var doubles: Int = 0,
        var triples: Int = 0,
        var hr: Int = 0,
        var rbi: Int = 0,
        var bb: Int = 0,
        var ibb: Int = 0,
        var hbp: Int = 0,
        var so: Int = 0,
        var sh: Int = 0,
        var sf: Int = 0,
        var fc: Int = 0,
        var roe: Int = 0,
        var gidp: Int = 0,
        var sb: Int = 0,
        var cs: Int = 0,
        var po: Int = 0
    ) {
        fun freeze() = BattingStats(
            playerId = playerId,
            playerName = playerName,
            plateAppearances = pa,
            atBats = ab,
            runs = r,
            hits = h,
            doubles = doubles,
            triples = triples,
            homeRuns = hr,
            rbi = rbi,
            walks = bb,
            intentionalWalks = ibb,
            hitByPitch = hbp,
            strikeouts = so,
            sacrificeBunts = sh,
            sacrificeFlies = sf,
            fieldersChoices = fc,
            reachedOnError = roe,
            groundedIntoDoublePlay = gidp,
            stolenBases = sb,
            caughtStealing = cs,
            pickedOff = po
        )
    }

    private data class MutablePitching(
        val name: String,
        val team: Team,
        var bf: Int = 0,
        var pitches: Int = 0,
        var balls: Int = 0,
        var strikes: Int = 0,
        var outs: Int = 0,
        var hits: Int = 0,
        var runs: Int = 0,
        var er: Int = 0,
        var bb: Int = 0,
        var ibb: Int = 0,
        var so: Int = 0,
        var hbp: Int = 0,
        var hr: Int = 0,
        var wp: Int = 0,
        var balks: Int = 0,
        var inherited: Int = 0,
        var inheritedScored: Int = 0
    ) {
        fun freeze() = PitchingStats(
            pitcherName = name,
            team = team,
            battersFaced = bf,
            pitches = pitches,
            balls = balls,
            strikes = strikes,
            outsRecorded = outs,
            hitsAllowed = hits,
            runsAllowed = runs,
            earnedRuns = er,
            walks = bb,
            intentionalWalks = ibb,
            strikeouts = so,
            hitBatters = hbp,
            homeRunsAllowed = hr,
            wildPitches = wp,
            balks = balks,
            inheritedRunners = inherited,
            inheritedRunnersScored = inheritedScored
        )
    }

    private data class MutableFielding(
        val name: String,
        val team: Team,
        var po: Int = 0,
        var a: Int = 0,
        var e: Int = 0,
        var dp: Int = 0,
        var tp: Int = 0,
        var pb: Int = 0
    ) {
        fun freeze() = FieldingStats(name, team, po, a, e, dp, tp, pb)
    }
}
