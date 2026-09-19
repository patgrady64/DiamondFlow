package com.pgdevhouse.diamondflow.storage

import android.content.Context
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.SavedTeam
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(context: Context) {
    private val gameStorage = GameStorage(context)
    private val teamStorage = SavedTeamStorage(context)
    private val completedStorage = CompletedGameStorage(context)

    data class RestoreResult(
        val activeGame: GameState?,
        val teamCount: Int,
        val completedGameCount: Int
    )

    fun createBackup(): String {
        val root = JSONObject()
        root.put("format", "InningTrackBackup")
        root.put("version", 1)
        root.put("createdAtEpochMillis", System.currentTimeMillis())
        gameStorage.load()?.let { root.put("activeGame", JSONObject(gameStorage.encode(it))) }
        root.put("teams", JSONArray().apply {
            teamStorage.loadAll().forEach { team ->
                put(JSONObject().apply {
                    put("name", team.name)
                    put("lineupNames", JSONArray(team.lineupNames))
                    put("lineupPositions", JSONArray(team.lineupPositions))
                })
            }
        })
        root.put("completedGames", JSONArray().apply {
            completedStorage.loadAll().forEach { completed ->
                put(JSONObject(gameStorage.encode(completed.state)))
            }
        })
        return root.toString(2)
    }

    fun restoreBackup(raw: String): RestoreResult {
        val root = JSONObject(raw)
        val format = root.optString("format")
        require(format == "InningTrackBackup" || format == "DiamondFlowBackup") {
            "Not an InningTrack backup."
        }

        val teams = buildList {
            val array = root.optJSONArray("teams") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                add(
                    SavedTeam(
                        name = name,
                        lineupNames = item.optJSONArray("lineupNames").toStringList(),
                        lineupPositions = item.optJSONArray("lineupPositions").toStringList()
                    )
                )
            }
        }

        val completed = buildList {
            val array = root.optJSONArray("completedGames") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                gameStorage.decode(item.toString())?.takeIf(GameState::gameOver)?.let(::add)
            }
        }
        val active = root.optJSONObject("activeGame")?.let { gameStorage.decode(it.toString()) }?.takeUnless(GameState::gameOver)

        val savedTeams = teamStorage.replaceAll(teams)
        val savedGames = completedStorage.replaceAll(completed)
        if (active == null) gameStorage.clear() else gameStorage.save(active)
        return RestoreResult(active, savedTeams.size, savedGames.size)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) add(optString(index))
        }
    }
}
