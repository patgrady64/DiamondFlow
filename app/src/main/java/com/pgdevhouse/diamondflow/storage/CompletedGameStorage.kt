package com.pgdevhouse.diamondflow.storage

import android.content.Context
import com.pgdevhouse.diamondflow.model.CompletedGame
import com.pgdevhouse.diamondflow.model.GameState
import java.io.File

class CompletedGameStorage(context: Context) {

    private val codec = GameStorage(context)
    private val directory = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    fun loadAll(): List<CompletedGame> {
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" }
            .mapNotNull { file ->
                runCatching {
                    val state = codec.decode(file.readText()) ?: return@runCatching null
                    val id = state.gameId.ifBlank { file.nameWithoutExtension }
                    CompletedGame(
                        gameId = id,
                        completedAtEpochMillis = state.endedAtEpochMillis
                            ?: file.lastModified().takeIf { it > 0L }
                            ?: state.startedAtEpochMillis,
                        state = state.copy(gameId = id)
                    )
                }.getOrNull()
            }
            .sortedByDescending(CompletedGame::completedAtEpochMillis)
            .toList()
    }

    fun save(state: GameState): List<CompletedGame> {
        if (!state.gameOver) return loadAll()
        val id = state.gameId.ifBlank { java.util.UUID.randomUUID().toString() }
        val completedAt = state.endedAtEpochMillis ?: System.currentTimeMillis()
        val normalized = state.copy(gameId = id, endedAtEpochMillis = completedAt)
        gameFile(id).writeText(codec.encode(normalized))
        return loadAll()
    }


    fun replaceAll(states: List<GameState>): List<CompletedGame> {
        directory.listFiles().orEmpty().forEach { file -> if (file.isFile) file.delete() }
        states.filter(GameState::gameOver).forEach { state -> save(state) }
        return loadAll()
    }

    fun clearAll() {
        directory.listFiles().orEmpty().forEach { file -> if (file.isFile) file.delete() }
    }

    fun delete(gameId: String): List<CompletedGame> {
        if (gameId.isNotBlank()) gameFile(gameId).delete()
        return loadAll()
    }

    private fun gameFile(gameId: String): File {
        val safeId = gameId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(directory, "$safeId.json")
    }

    companion object {
        private const val DIRECTORY_NAME = "completed_games"
    }
}
