package com.pgdevhouse.diamondflow.storage

import android.content.Context
import com.pgdevhouse.diamondflow.model.SavedTeam
import org.json.JSONArray
import org.json.JSONObject

class SavedTeamStorage(context: Context) {

    private val preferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun loadAll(): List<SavedTeam> {
        val raw = preferences.getString(KEY_TEAMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isEmpty()) continue
                    val lineupArray = item.optJSONArray("lineup")
                    val lineup = buildList {
                        if (lineupArray != null) {
                            for (playerIndex in 0 until lineupArray.length()) {
                                val playerName = lineupArray.optString(playerIndex).trim()
                                if (playerName.isNotEmpty()) add(playerName)
                            }
                        }
                    }
                    val positionsArray = item.optJSONArray("positions")
                    val positions = buildList {
                        if (positionsArray != null) {
                            for (playerIndex in 0 until positionsArray.length()) {
                                add(positionsArray.optString(playerIndex).trim().uppercase())
                            }
                        }
                    }
                    add(SavedTeam(name = name, lineupNames = lineup, lineupPositions = positions))
                }
            }.sortedBy { it.name.lowercase() }
        }.getOrElse { emptyList() }
    }

    fun save(team: SavedTeam): List<SavedTeam> {
        val cleanedName = team.name.trim()
        if (cleanedName.isEmpty()) return loadAll()

        val cleanedRows = team.lineupNames.mapIndexedNotNull { index, rawName ->
            val playerName = rawName.trim()
            if (playerName.isEmpty()) null
            else playerName to team.lineupPositions.getOrNull(index).orEmpty().trim().uppercase()
        }
        val cleanedTeam = team.copy(
            name = cleanedName,
            lineupNames = cleanedRows.map { it.first },
            lineupPositions = cleanedRows.map { it.second }
        )
        val updated = loadAll()
            .filterNot { it.name.equals(cleanedName, ignoreCase = true) }
            .plus(cleanedTeam)
            .sortedBy { it.name.lowercase() }
        persist(updated)
        return updated
    }


    fun replaceAll(teams: List<SavedTeam>): List<SavedTeam> {
        val normalized = teams
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.trim().lowercase() }
            .sortedBy { it.name.lowercase() }
        persist(normalized)
        return loadAll()
    }

    fun delete(teamName: String): List<SavedTeam> {
        val updated = loadAll()
            .filterNot { it.name.equals(teamName.trim(), ignoreCase = true) }
        persist(updated)
        return updated
    }

    private fun persist(teams: List<SavedTeam>) {
        val array = JSONArray()
        teams.forEach { team ->
            array.put(JSONObject().apply {
                put("name", team.name)
                put("lineup", JSONArray().apply {
                    team.lineupNames.forEach(::put)
                })
                put("positions", JSONArray().apply {
                    team.lineupPositions.forEach(::put)
                })
            })
        }
        preferences.edit().putString(KEY_TEAMS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "diamond_flow_saved_teams"
        private const val KEY_TEAMS = "teams"
    }
}
