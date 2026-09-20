package com.pgdevhouse.diamondflow.storage

import android.content.Context
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.BaseRunningAction
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.GameEvent
import com.pgdevhouse.diamondflow.model.GameEventType
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Runner
import com.pgdevhouse.diamondflow.model.ScoredRun
import com.pgdevhouse.diamondflow.model.Team
import org.json.JSONArray
import org.json.JSONObject

class GameStorage(context: Context) {

    private val preferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun save(state: GameState) {
        preferences.edit()
            .putString(KEY_ACTIVE_GAME, encode(state))
            .apply()
    }

    fun load(): GameState? {
        val raw = preferences.getString(KEY_ACTIVE_GAME, null) ?: return null
        return decode(raw)
    }

    fun encode(state: GameState): String = state.toJson().toString()

    fun decode(raw: String): GameState? = runCatching { JSONObject(raw).toGameState() }.getOrNull()

    fun clear() {
        preferences.edit().remove(KEY_ACTIVE_GAME).apply()
    }

    private fun GameState.toJson(): JSONObject {
        return JSONObject().apply {
            put("version", STORAGE_VERSION)
            put("gameId", gameId)
            put("gameDate", gameDate)
            put("ballpark", ballpark)
            put("location", location)
            put("notes", notes)
            put("startedAtEpochMillis", startedAtEpochMillis)
            endedAtEpochMillis?.let { put("endedAtEpochMillis", it) }
            put("balls", balls)
            put("strikes", strikes)
            put("outs", outs)
            put("maxInnings", maxInnings)
            put("currentInning", currentInning)
            put("topOfInning", topOfInning)
            put("activeTeam", activeTeam.name)
            put("awayTeamName", awayTeamName)
            put("homeTeamName", homeTeamName)
            put("awayPitcherName", awayPitcherName)
            put("homePitcherName", homePitcherName)
            put("playerPositions", playerPositions.toPositionsJson())
            put("awayBatterIndex", awayBatterIndex)
            put("homeBatterIndex", homeBatterIndex)
            awayUnknownBatterId?.let { put("awayUnknownBatterId", it) }
            homeUnknownBatterId?.let { put("homeUnknownBatterId", it) }
            put("nextUnknownPlayerId", nextUnknownPlayerId)
            put("carryInRunsAway", carryInRuns[Team.AWAY] ?: 0)
            put("carryInRunsHome", carryInRuns[Team.HOME] ?: 0)
            put("trackingStartedMidGame", trackingStartedMidGame)
            put("gameOver", gameOver)
            put("gameEndedManually", gameEndedManually)
            put("events", events.toEventsJson())
            put("nextEventId", nextEventId)
            put("bases", bases.toJson())
            put("lineupAway", lineupAway.toPlayersJson())
            put("lineupHome", lineupHome.toPlayersJson())
            put("scoresAway", scores[Team.AWAY].orEmpty().toIntJson())
            put("scoresHome", scores[Team.HOME].orEmpty().toIntJson())
            put("hitsAway", hits[Team.AWAY] ?: 0)
            put("hitsHome", hits[Team.HOME] ?: 0)
            put("errorsAway", errors[Team.AWAY] ?: 0)
            put("errorsHome", errors[Team.HOME] ?: 0)
        }
    }

    private fun JSONObject.toGameState(): GameState {
        val maxInnings = optInt("maxInnings", 9).coerceIn(1, 20)
        val awayTeamName = optString("awayTeamName", "Away")
        val homeTeamName = optString("homeTeamName", "Home")
        val awayLineup = optJSONArray("lineupAway")?.toPlayers().orEmpty()
        val homeLineup = optJSONArray("lineupHome")?.toPlayers().orEmpty()
        val savedPositions = optJSONObject("playerPositions")?.toPositions().orEmpty()
        return GameState(
            gameId = optString("gameId").takeIf(String::isNotBlank) ?: java.util.UUID.randomUUID().toString(),
            gameDate = optString("gameDate", ""),
            ballpark = optString("ballpark", ""),
            location = optString("location", ""),
            notes = optString("notes", ""),
            startedAtEpochMillis = optLong("startedAtEpochMillis", 0L).takeIf { it > 0L } ?: System.currentTimeMillis(),
            endedAtEpochMillis = if (has("endedAtEpochMillis")) optLong("endedAtEpochMillis") else null,
            balls = optInt("balls", 0),
            strikes = optInt("strikes", 0),
            outs = optInt("outs", 0),
            maxInnings = maxInnings,
            currentInning = optInt("currentInning", 1).coerceAtLeast(1),
            topOfInning = optBoolean("topOfInning", true),
            activeTeam = enumValueOrDefault(optString("activeTeam"), Team.AWAY),
            bases = optJSONObject("bases")?.toBases() ?: Bases(),
            awayTeamName = awayTeamName,
            homeTeamName = homeTeamName,
            awayPitcherName = optString("awayPitcherName", "$awayTeamName Pitcher"),
            homePitcherName = optString("homePitcherName", "$homeTeamName Pitcher"),
            playerPositions = normalizeBattingPositions(awayLineup, savedPositions) + normalizeBattingPositions(homeLineup, savedPositions),
            lineupAway = awayLineup,
            lineupHome = homeLineup,
            scores = mapOf(
                Team.AWAY to optJSONArray("scoresAway").toIntList(maxInnings),
                Team.HOME to optJSONArray("scoresHome").toIntList(maxInnings)
            ),
            hits = mapOf(
                Team.AWAY to optInt("hitsAway", 0),
                Team.HOME to optInt("hitsHome", 0)
            ),
            errors = mapOf(
                Team.AWAY to optInt("errorsAway", 0),
                Team.HOME to optInt("errorsHome", 0)
            ),
            awayBatterIndex = optInt("awayBatterIndex", 0),
            homeBatterIndex = optInt("homeBatterIndex", 0),
            awayUnknownBatterId = if (has("awayUnknownBatterId")) optInt("awayUnknownBatterId") else null,
            homeUnknownBatterId = if (has("homeUnknownBatterId")) optInt("homeUnknownBatterId") else null,
            nextUnknownPlayerId = optInt("nextUnknownPlayerId", -1).coerceAtMost(-1),
            carryInRuns = mapOf(
                Team.AWAY to optInt("carryInRunsAway", 0).coerceAtLeast(0),
                Team.HOME to optInt("carryInRunsHome", 0).coerceAtLeast(0)
            ),
            trackingStartedMidGame = optBoolean("trackingStartedMidGame", false),
            events = optJSONArray("events")?.toEvents().orEmpty(),
            nextEventId = optLong("nextEventId", 1L).coerceAtLeast(1L),
            gameOver = optBoolean("gameOver", false),
            gameEndedManually = optBoolean("gameEndedManually", false)
        )
    }

    private fun Bases.toJson(): JSONObject {
        return JSONObject().apply {
            first?.let { put("first", it.toJson()) }
            second?.let { put("second", it.toJson()) }
            third?.let { put("third", it.toJson()) }
        }
    }

    private fun JSONObject.toBases(): Bases {
        return Bases(
            first = optJSONObject("first")?.toRunner(),
            second = optJSONObject("second")?.toRunner(),
            third = optJSONObject("third")?.toRunner()
        )
    }

    private fun Runner.toJson(): JSONObject = JSONObject().apply {
        put("playerId", playerId)
        put("base", base.name)
        responsiblePitcherName?.let { put("responsiblePitcherName", it) }
        put("earnedRunEligible", earnedRunEligible)
    }

    private fun JSONObject.toRunner(): Runner {
        return Runner(
            playerId = optInt("playerId", -1),
            base = enumValueOrDefault(optString("base"), Base.FIRST),
            responsiblePitcherName = optString("responsiblePitcherName").takeIf(String::isNotBlank),
            earnedRunEligible = optBoolean("earnedRunEligible", true)
        )
    }

    private fun List<Player>.toPlayersJson(): JSONArray = JSONArray().also { array ->
        forEach { player ->
            array.put(JSONObject().apply {
                put("id", player.id)
                put("name", player.name)
                player.number?.let { put("number", it) }
            })
        }
    }

    private fun JSONArray.toPlayers(): List<Player> {
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    Player(
                        id = item.optInt("id", index + 1),
                        name = item.optString("name", "Player ${index + 1}"),
                        number = if (item.has("number")) item.optInt("number") else null
                    )
                )
            }
        }
    }

    private fun List<Int>.toIntJson(): JSONArray = JSONArray().also { array ->
        forEach(array::put)
    }

    private fun Map<Int, String>.toPositionsJson(): JSONObject = JSONObject().apply {
        forEach { (playerId, position) -> put(playerId.toString(), position) }
    }

    private fun JSONObject.toPositions(): Map<Int, String> {
        return buildMap {
            keys().forEach { key ->
                val playerId = key.toIntOrNull() ?: return@forEach
                val position = optString(key).trim()
                if (position.isNotEmpty()) put(playerId, position)
            }
        }
    }

    private fun defaultPositions(lineup: List<Player>): Map<Int, String> {
        val labels = listOf("C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH")
        return lineup.mapIndexedNotNull { index, player ->
            labels.getOrNull(index)?.let { player.id to it }
        }.toMap()
    }

    private fun normalizeBattingPositions(
        lineup: List<Player>,
        savedPositions: Map<Int, String>
    ): Map<Int, String> {
        val allowed = listOf("C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH")
        val used = mutableSetOf<String>()
        return buildMap {
            lineup.take(9).forEachIndexed { index, player ->
                val raw = savedPositions[player.id]?.trim()?.uppercase().orEmpty()
                val migrated = if (raw == "P") "DH" else raw
                val selected = migrated.takeIf { it in allowed && it !in used }
                    ?: allowed.getOrNull(index)?.takeIf { it !in used }
                    ?: allowed.firstOrNull { it !in used }
                if (selected != null) {
                    used += selected
                    put(player.id, selected)
                }
            }
        }
    }

    private fun List<GameEvent>.toEventsJson(): JSONArray = JSONArray().also { array ->
        forEach { event ->
            array.put(JSONObject().apply {
                put("id", event.id)
                put("inning", event.inning)
                put("topOfInning", event.topOfInning)
                put("battingTeam", event.battingTeam.name)
                put("type", event.type.name)
                put("title", event.title)
                put("detail", event.detail)
                event.actorPlayerId?.let { put("actorPlayerId", it) }
                event.actorName?.let { put("actorName", it) }
                event.replacedPlayerId?.let { put("replacedPlayerId", it) }
                event.replacedPlayerName?.let { put("replacedPlayerName", it) }
                event.team?.let { put("team", it.name) }
                event.base?.let { put("base", it.name) }
                event.position?.let { put("position", it) }
                event.pitcherName?.let { put("pitcherName", it) }
                event.playAction?.let { put("playAction", it.name) }
                event.pitchAction?.let { put("pitchAction", it.name) }
                event.baseRunningAction?.let { put("baseRunningAction", it.name) }
                event.fromBase?.let { put("fromBase", it.name) }
                event.toBase?.let { put("toBase", it.name) }
                put("outsRecorded", event.outsRecorded)
                put("runsScored", event.runsScored)
                put("scoredPlayerIds", JSONArray().apply { event.scoredPlayerIds.forEach(::put) })
                put("scoredRuns", JSONArray().apply {
                    event.scoredRuns.forEach { run ->
                        put(JSONObject().apply {
                            put("playerId", run.playerId)
                            run.responsiblePitcherName?.let { put("responsiblePitcherName", it) }
                            put("earned", run.earned)
                        })
                    }
                })
                event.putoutPlayerName?.let { put("putoutPlayerName", it) }
                put("putoutPlayerNames", JSONArray().apply { event.putoutPlayerNames.forEach(::put) })
                put("assistPlayerNames", JSONArray().apply { event.assistPlayerNames.forEach(::put) })
                event.errorPlayerName?.let { put("errorPlayerName", it) }
                event.fieldingNotation?.let { put("fieldingNotation", it) }
                put("inheritedRunners", event.inheritedRunners)
                put("pitchCountAdjustment", event.pitchCountAdjustment)
            })
        }
    }

    private fun JSONArray.toEvents(): List<GameEvent> {
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val id = item.optLong("id", index + 1L)
                add(
                    GameEvent(
                        id = id,
                        inning = item.optInt("inning", 1).coerceAtLeast(1),
                        topOfInning = item.optBoolean("topOfInning", true),
                        battingTeam = enumValueOrDefault(item.optString("battingTeam"), Team.AWAY),
                        type = enumValueOrDefault(item.optString("type"), GameEventType.PLAY),
                        title = item.optString("title", "Game event"),
                        detail = item.optString("detail", ""),
                        actorPlayerId = if (item.has("actorPlayerId")) item.optInt("actorPlayerId") else null,
                        actorName = item.optString("actorName").takeIf(String::isNotBlank),
                        replacedPlayerId = if (item.has("replacedPlayerId")) item.optInt("replacedPlayerId") else null,
                        replacedPlayerName = item.optString("replacedPlayerName").takeIf(String::isNotBlank),
                        team = item.optString("team").takeIf(String::isNotBlank)?.let {
                            enumValueOrDefault(it, Team.AWAY)
                        },
                        base = item.optString("base").takeIf(String::isNotBlank)?.let {
                            enumValueOrDefault(it, Base.FIRST)
                        },
                        position = item.optString("position").takeIf(String::isNotBlank),
                        pitcherName = item.optString("pitcherName").takeIf(String::isNotBlank),
                        playAction = decodePlayAction(item),
                        pitchAction = item.optString("pitchAction").takeIf(String::isNotBlank)?.let {
                            enumValueOrDefault(it, com.pgdevhouse.diamondflow.model.PitchAction.BALL)
                        },
                        baseRunningAction = item.optString("baseRunningAction").takeIf(String::isNotBlank)?.let {
                            enumValueOrDefault(it, BaseRunningAction.RUNNER_ADVANCE)
                        },
                        fromBase = item.optString("fromBase").takeIf(String::isNotBlank)?.let {
                            enumValueOrDefault(it, Base.FIRST)
                        },
                        toBase = item.optString("toBase").takeIf(String::isNotBlank)?.let {
                            enumValueOrDefault(it, Base.FIRST)
                        },
                        outsRecorded = if (item.has("outsRecorded")) item.optInt("outsRecorded", 0).coerceIn(0, 3) else legacyOuts(item.optString("detail")),
                        runsScored = if (item.has("runsScored")) item.optInt("runsScored", 0).coerceAtLeast(0) else legacyRuns(item.optString("detail")),
                        scoredPlayerIds = item.optJSONArray("scoredPlayerIds").toIntValues(),
                        scoredRuns = item.optJSONArray("scoredRuns").toScoredRuns(),
                        putoutPlayerName = item.optString("putoutPlayerName").takeIf(String::isNotBlank),
                        putoutPlayerNames = item.optJSONArray("putoutPlayerNames").toStringValues(),
                        assistPlayerNames = item.optJSONArray("assistPlayerNames").toStringValues(),
                        errorPlayerName = item.optString("errorPlayerName").takeIf(String::isNotBlank),
                        fieldingNotation = item.optString("fieldingNotation").takeIf(String::isNotBlank),
                        inheritedRunners = item.optInt("inheritedRunners", 0).coerceAtLeast(0),
                        pitchCountAdjustment = item.optInt("pitchCountAdjustment", 0)
                    )
                )
            }
        }
    }

    private fun decodePlayAction(item: JSONObject): com.pgdevhouse.diamondflow.model.PlayAction? {
        val stored = item.optString("playAction").takeIf(String::isNotBlank)
        if (stored != null) {
            return enumValueOrDefault(stored, com.pgdevhouse.diamondflow.model.PlayAction.SINGLE)
        }
        return when (item.optString("title").lowercase()) {
            "single" -> com.pgdevhouse.diamondflow.model.PlayAction.SINGLE
            "double" -> com.pgdevhouse.diamondflow.model.PlayAction.DOUBLE
            "triple" -> com.pgdevhouse.diamondflow.model.PlayAction.TRIPLE
            "home run" -> com.pgdevhouse.diamondflow.model.PlayAction.HOME_RUN
            "ball 4 — walk" -> com.pgdevhouse.diamondflow.model.PlayAction.WALK
            "intentional walk" -> com.pgdevhouse.diamondflow.model.PlayAction.INTENTIONAL_WALK
            "strikeout", "strike 3 — strikeout" -> com.pgdevhouse.diamondflow.model.PlayAction.STRIKEOUT
            "ground out" -> com.pgdevhouse.diamondflow.model.PlayAction.GROUND_OUT
            "fly out" -> com.pgdevhouse.diamondflow.model.PlayAction.FLY_OUT
            "fielder's choice" -> com.pgdevhouse.diamondflow.model.PlayAction.FIELDERS_CHOICE
            "sacrifice" -> com.pgdevhouse.diamondflow.model.PlayAction.SACRIFICE
            "sacrifice bunt" -> com.pgdevhouse.diamondflow.model.PlayAction.SACRIFICE_BUNT
            "sacrifice fly" -> com.pgdevhouse.diamondflow.model.PlayAction.SACRIFICE_FLY
            "double play" -> com.pgdevhouse.diamondflow.model.PlayAction.DOUBLE_PLAY
            "triple play" -> com.pgdevhouse.diamondflow.model.PlayAction.TRIPLE_PLAY
            "error" -> com.pgdevhouse.diamondflow.model.PlayAction.ERROR
            "hit by pitch" -> com.pgdevhouse.diamondflow.model.PlayAction.HIT_BY_PITCH
            else -> null
        }
    }

    private fun legacyRuns(detail: String): Int = Regex("(\\d+) run").find(detail)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    private fun legacyOuts(detail: String): Int = Regex("(\\d+) out").find(detail)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 3) ?: 0

    private fun JSONArray?.toStringValues(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun JSONArray?.toScoredRuns(): List<ScoredRun> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                add(
                    ScoredRun(
                        playerId = item.optInt("playerId", -1),
                        responsiblePitcherName = item.optString("responsiblePitcherName").takeIf(String::isNotBlank),
                        earned = item.optBoolean("earned", true)
                    )
                )
            }
        }
    }

    private fun JSONArray?.toIntValues(): List<Int> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) add(optInt(index))
        }
    }

    private fun JSONArray?.toIntList(minSize: Int): List<Int> {
        val values = mutableListOf<Int>()
        if (this != null) {
            for (index in 0 until length()) {
                values += optInt(index, 0)
            }
        }
        while (values.size < minSize) values += 0
        return values
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(
        value: String,
        default: T
    ): T {
        return enumValues<T>().firstOrNull { it.name == value } ?: default
    }

    companion object {
        private const val PREFS_NAME = "diamond_flow_game"
        private const val KEY_ACTIVE_GAME = "active_game"
        private const val STORAGE_VERSION = 7
    }
}
