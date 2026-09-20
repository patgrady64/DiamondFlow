package com.pgdevhouse.diamondflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.engine.LineupEngine
import com.pgdevhouse.diamondflow.engine.RunnerEngine
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.BaseRunningAction
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.CompletedGame
import com.pgdevhouse.diamondflow.model.GameEvent
import com.pgdevhouse.diamondflow.model.GameEventType
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.GameStats
import com.pgdevhouse.diamondflow.model.BattingStats
import com.pgdevhouse.diamondflow.model.FieldingPlay
import com.pgdevhouse.diamondflow.model.FieldingStats
import com.pgdevhouse.diamondflow.model.PitchingStats
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Runner
import com.pgdevhouse.diamondflow.model.SavedTeam
import com.pgdevhouse.diamondflow.model.Team
import com.pgdevhouse.diamondflow.storage.BackupManager
import com.pgdevhouse.diamondflow.storage.CompletedGameStorage
import com.pgdevhouse.diamondflow.storage.GameExport
import com.pgdevhouse.diamondflow.storage.GameStorage
import com.pgdevhouse.diamondflow.storage.SavedTeamStorage
import com.pgdevhouse.diamondflow.ui.theme.InningTrackTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            InningTrackTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InningTrackApp()
                }
            }
        }
    }
}

private enum class AppScreen {
    HOME,
    SETUP,
    GAME,
    TEAMS,
    HISTORY_SCORECARD
}

private enum class SetupStep {
    AWAY_TEAM,
    HOME_TEAM,
    GAME_INFO,
    PITCHERS,
    LINEUPS,
    STARTING_POINT
}

private enum class GameView {
    SCOREKEEPING,
    OVERVIEW,
    SCORECARD,
    STATS
}

private val DEFENSIVE_POSITIONS = listOf("C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH")

private data class SmartActionWarning(
    val title: String,
    val message: String,
    val confirmText: String
)

private data class MidGameSetup(
    val enabled: Boolean = false,
    val inning: Int = 1,
    val topOfInning: Boolean = true,
    val balls: Int = 0,
    val strikes: Int = 0,
    val outs: Int = 0,
    val awayScore: Int = 0,
    val homeScore: Int = 0,
    val awayBatterIndex: Int = -1,
    val homeBatterIndex: Int = -1,
    val firstRunnerId: Int? = null,
    val secondRunnerId: Int? = null,
    val thirdRunnerId: Int? = null
)

private data class GameSituationUpdate(
    val inning: Int,
    val topOfInning: Boolean,
    val balls: Int,
    val strikes: Int,
    val outs: Int,
    val awayScore: Int,
    val homeScore: Int,
    val awayBatterId: Int?,
    val homeBatterId: Int?,
    val firstRunnerId: Int?,
    val secondRunnerId: Int?,
    val thirdRunnerId: Int?
)

private fun savedTeamFromState(state: GameState, team: Team): SavedTeam {
    val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
    return SavedTeam(
        name = state.teamName(team),
        lineupNames = lineup.map(Player::name),
        lineupPositions = lineup.map { state.playerPosition(it.id).orEmpty() }
    )
}

private fun defaultWizardPositions(size: Int): List<String> {
    val defaults = listOf("C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH")
    return List(size.coerceAtLeast(9)) { index -> defaults.getOrNull(index).orEmpty() }
}

private fun normalizedSavedPositions(team: SavedTeam): List<String> {
    val count = team.lineupNames.size.coerceAtLeast(9)
    val defaults = defaultWizardPositions(count)
    val used = mutableSetOf<String>()
    return List(count) { index ->
        val rawSaved = team.lineupPositions.getOrNull(index)?.trim()?.uppercase().orEmpty()
        val saved = if (rawSaved == "P") "DH" else rawSaved
        val preferred = saved.takeIf { it in DEFENSIVE_POSITIONS && it !in used }
            ?: defaults.getOrNull(index)?.takeIf { it.isNotBlank() && it !in used }
            ?: DEFENSIVE_POSITIONS.firstOrNull { it !in used }
            .orEmpty()
        if (preferred.isNotBlank()) used += preferred
        preferred
    }
}


@Composable
private fun InningTrackApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val storage = remember { GameStorage(context) }
    val savedTeamStorage = remember { SavedTeamStorage(context) }
    val completedGameStorage = remember { CompletedGameStorage(context) }
    val backupManager = remember { BackupManager(context) }

    val initialActive = remember {
        storage.load()?.let { loaded ->
            if (loaded.gameOver) {
                completedGameStorage.save(loaded)
                savedTeamStorage.save(savedTeamFromState(loaded, Team.AWAY))
                savedTeamStorage.save(savedTeamFromState(loaded, Team.HOME))
                storage.clear()
                null
            } else {
                loaded
            }
        }
    }

    var savedTeams by remember { mutableStateOf(savedTeamStorage.loadAll()) }
    var completedGames by remember { mutableStateOf(completedGameStorage.loadAll()) }
    var controller by remember {
        mutableStateOf(GameController(initialActive ?: GameController.defaultState()))
    }
    var gameState by remember { mutableStateOf(controller.state) }
    var activeGamePresent by remember { mutableStateOf(initialActive != null) }
    var selectedHistoricalGame by remember { mutableStateOf<CompletedGame?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var screen by remember { mutableStateOf(AppScreen.HOME) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(backupManager.createBackup().toByteArray())
                } ?: error("Could not open backup destination.")
            }.onSuccess { backupMessage = "Backup exported." }
                .onFailure { backupMessage = "Backup export failed: ${it.message ?: "unknown error"}" }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read backup.")
                backupManager.restoreBackup(raw)
            }.onSuccess { restored ->
                savedTeams = savedTeamStorage.loadAll()
                completedGames = completedGameStorage.loadAll()
                val active = restored.activeGame
                controller = GameController(active ?: GameController.defaultState())
                gameState = controller.state
                activeGamePresent = active != null
                selectedHistoricalGame = null
                backupMessage = "Backup restored: ${restored.teamCount} teams, ${restored.completedGameCount} completed games."
                screen = AppScreen.HOME
            }.onFailure {
                backupMessage = "Backup restore failed: ${it.message ?: "invalid backup"}"
            }
        }
    }

    fun saveFinalTeamRosters(state: GameState) {
        savedTeams = savedTeamStorage.save(savedTeamFromState(state, Team.AWAY))
        savedTeams = savedTeamStorage.save(savedTeamFromState(state, Team.HOME))
    }

    fun syncAndSave() {
        gameState = controller.state
        if (gameState.gameOver) {
            completedGames = completedGameStorage.save(gameState)
            saveFinalTeamRosters(gameState)
            storage.clear()
            activeGamePresent = false
        } else {
            completedGames = completedGameStorage.delete(gameState.gameId)
            storage.save(gameState)
            activeGamePresent = true
        }
    }

    fun perform(action: GameController.() -> Unit) {
        controller.action()
        syncAndSave()
    }

    BackHandler(enabled = screen != AppScreen.HOME) {
        selectedHistoricalGame = null
        screen = AppScreen.HOME
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            activeGame = gameState.takeIf { activeGamePresent && !it.gameOver },
            completedGames = completedGames,
            savedTeams = savedTeams,
            onStartGame = { screen = AppScreen.SETUP },
            onContinueGame = { screen = AppScreen.GAME },
            onDiscardActiveGame = {
                storage.clear()
                completedGames = completedGameStorage.delete(gameState.gameId)
                controller = GameController()
                gameState = controller.state
                activeGamePresent = false
                screen = AppScreen.HOME
            },
            onManageTeams = { screen = AppScreen.TEAMS },
            backupMessage = backupMessage,
            onExportBackup = { exportBackupLauncher.launch("InningTrack-backup.json") },
            onImportBackup = { importBackupLauncher.launch(arrayOf("application/json", "text/json", "*/*")) },
            onOpenCompletedGame = { completed ->
                selectedHistoricalGame = completed
                screen = AppScreen.HISTORY_SCORECARD
            }
        )

        AppScreen.SETUP -> GameSetupScreen(
            savedTeams = savedTeams,
            onCancel = { screen = AppScreen.HOME },
            onSaveTeam = { team -> savedTeams = savedTeamStorage.save(team) },
            onDeleteTeam = { teamName -> savedTeams = savedTeamStorage.delete(teamName) },
            onStartGame = { newState ->
                // The completed wizard is the authoritative starting roster for this game.
                // Save it automatically so the team/player names are available next time.
                savedTeams = saveWizardTeamRoster(savedTeamStorage, savedTeams, newState, Team.AWAY)
                savedTeams = saveWizardTeamRoster(savedTeamStorage, savedTeams, newState, Team.HOME)
                controller = GameController(newState)
                gameState = newState
                activeGamePresent = true
                storage.save(newState)
                screen = AppScreen.GAME
            }
        )

        AppScreen.TEAMS -> TeamManagementScreen(
            savedTeams = savedTeams,
            onBack = { screen = AppScreen.HOME },
            onSaveTeam = { originalName, team ->
                var updated = savedTeams
                if (originalName.isNotBlank() && !originalName.equals(team.name, ignoreCase = true)) {
                    updated = savedTeamStorage.delete(originalName)
                }
                updated = savedTeamStorage.save(team)
                savedTeams = updated
            },
            onDeleteTeam = { teamName -> savedTeams = savedTeamStorage.delete(teamName) }
        )

        AppScreen.GAME -> GameScreen(
            state = gameState,
            canUndo = controller.canUndo,
            canRedo = controller.canRedo,
            onBall = { perform { ball() } },
            onStrike = { perform { strike() } },
            onRemoveBall = { perform { removeCurrentBall() } },
            onRemoveStrike = { perform { removeCurrentStrike() } },
            onFoul = { perform { foul() } },
            onHitByPitch = { perform { hitByPitch() } },
            onRemoveRecordedPitch = { eventId -> perform { removeRecordedPitch(eventId) } },
            onAddRecordedPitch = { endEventId, action -> perform { addPitchToRecordedAtBat(endEventId, action) } },
            onRenameCurrentBatter = { name -> perform { renameCurrentBatter(name) } },
            onRenameTeam = { team, name -> perform { renameTeam(team, name) } },
            onRenamePlayer = { playerId, name -> perform { renamePlayer(playerId, name) } },
            onSaveCurrentTeam = { team ->
                val lineup = if (team == Team.AWAY) gameState.lineupAway else gameState.lineupHome
                savedTeams = savedTeamStorage.save(
                    SavedTeam(
                        name = gameState.teamName(team),
                        lineupNames = lineup.map(Player::name),
                        lineupPositions = lineup.map { gameState.playerPosition(it.id).orEmpty() }
                    )
                )
            },
            onPinchHit = { name -> perform { pinchHit(name) } },
            onPinchRun = { base, name -> perform { pinchRun(base, name) } },
            onPitchingChange = { name -> perform { pitchingChange(name) } },
            onPitchingChangeForTeam = { team, name -> perform { pitchingChange(team, name) } },
            onSetPitchCount = { team, pitcher, count -> perform { setPitchCount(team, pitcher, count) } },
            onPositionChange = { playerId, position -> perform { positionChange(playerId, position) } },
            onReorderLineup = { team, fromIndex, toIndex -> perform { reorderLineup(team, fromIndex, toIndex) } },
            onSetCurrentBatter = { team, playerId -> perform { setCurrentBatter(team, playerId) } },
            onResolveUnknownPlayer = { team, unknownId, playerId -> perform { resolveUnknownPlayer(team, unknownId, playerId) } },
            onEditGameSituation = { update ->
                perform {
                    setGameSituation(
                        inning = update.inning,
                        topOfInning = update.topOfInning,
                        balls = update.balls,
                        strikes = update.strikes,
                        outs = update.outs,
                        awayScore = update.awayScore,
                        homeScore = update.homeScore,
                        awayBatterId = update.awayBatterId,
                        homeBatterId = update.homeBatterId,
                        firstRunnerId = update.firstRunnerId,
                        secondRunnerId = update.secondRunnerId,
                        thirdRunnerId = update.thirdRunnerId
                    )
                }
            },
            onEditPersonnelEvent = { eventId, value -> perform { editPersonnelEvent(eventId, value) } },
            onEditRecordedAtBat = { eventId, pitches, action, batterId, pitcher, notation ->
                perform { editRecordedAtBat(eventId, pitches, action, batterId, pitcher, notation) }
            },
            onPlay = { action, destinations, outs, fielding ->
                controller.play(
                    action = action,
                    manualRunnerDestinations = destinations,
                    outsRecorded = outs,
                    fielding = fielding
                )
                syncAndSave()
            },
            onBaseRunning = { action, runnerId, destination, fielder ->
                controller.recordBaseRunningEvent(action, runnerId, destination, fielder)
                syncAndSave()
            },
            onUndo = {
                if (controller.undo()) syncAndSave()
            },
            onRedo = {
                if (controller.redo()) syncAndSave()
            },
            onEndGame = {
                controller.endGame()
                syncAndSave()
            },
            onDiscardGame = {
                storage.clear()
                completedGames = completedGameStorage.delete(gameState.gameId)
                controller = GameController()
                gameState = controller.state
                activeGamePresent = false
                screen = AppScreen.HOME
            },
            onHome = { screen = AppScreen.HOME }
        )

        AppScreen.HISTORY_SCORECARD -> {
            val completed = selectedHistoricalGame
            if (completed == null) {
                screen = AppScreen.HOME
            } else {
                HistoricalScorecardScreen(
                    completedGame = completed,
                    onBack = { screen = AppScreen.HOME },
                    onCorrectFinalScore = { away, home ->
                        val correction = GameController((selectedHistoricalGame ?: completed).state)
                        correction.correctFinalScore(away, home)
                        completedGames = completedGameStorage.save(correction.state)
                        selectedHistoricalGame = completedGames.firstOrNull { it.gameId == completed.gameId }
                    },
                    onCorrectEvent = { eventId, batterId, pitcher, action ->
                        val correction = GameController((selectedHistoricalGame ?: completed).state)
                        correction.correctRecordedEvent(eventId, batterId, pitcher, action)
                        completedGames = completedGameStorage.save(correction.state)
                        selectedHistoricalGame = completedGames.firstOrNull { it.gameId == completed.gameId }
                    },
                    onEditAtBat = { eventId, pitches, action, batterId, pitcher, notation ->
                        val correction = GameController((selectedHistoricalGame ?: completed).state)
                        correction.editRecordedAtBat(eventId, pitches, action, batterId, pitcher, notation)
                        completedGames = completedGameStorage.save(correction.state)
                        selectedHistoricalGame = completedGames.firstOrNull { it.gameId == completed.gameId }
                    },
                    onDeleteEvent = { eventId ->
                        val correction = GameController((selectedHistoricalGame ?: completed).state)
                        correction.deleteRecordedEvent(eventId)
                        completedGames = completedGameStorage.save(correction.state)
                        selectedHistoricalGame = completedGames.firstOrNull { it.gameId == completed.gameId }
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    activeGame: GameState?,
    completedGames: List<CompletedGame>,
    savedTeams: List<SavedTeam>,
    onStartGame: () -> Unit,
    onContinueGame: () -> Unit,
    onDiscardActiveGame: () -> Unit,
    onManageTeams: () -> Unit,
    backupMessage: String?,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onOpenCompletedGame: (CompletedGame) -> Unit
) {
    var showReplaceConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val activeSummary = activeGame?.let { game ->
        "${if (game.topOfInning) "Top" else "Bottom"} ${game.currentInning} • ${game.outs} out${if (game.outs == 1) "" else "s"}"
    } ?: "No game in progress"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("InningTrack", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                        Text(
                            "Baseball scorekeeping",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Start games fast, keep score live, and review full scorecards in one place.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        OutlinedButton(onClick = { settingsExpanded = true }) {
                            Text("Settings")
                        }
                        DropdownMenu(
                            expanded = settingsExpanded,
                            onDismissRequest = { settingsExpanded = false }
                        ) {
                            Text(
                                "Settings",
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                fontWeight = FontWeight.Bold
                            )
                            HorizontalDivider()
                            Text(
                                "Backup & Restore",
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            DropdownMenuItem(
                                text = { Text("Export Backup") },
                                onClick = {
                                    settingsExpanded = false
                                    onExportBackup()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Restore Backup") },
                                onClick = {
                                    settingsExpanded = false
                                    showImportConfirm = true
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashboardMetricCard(
                        label = "Active Game",
                        value = if (activeGame == null) "None" else "Live",
                        supporting = activeSummary,
                        modifier = Modifier.weight(1f)
                    )
                    DashboardMetricCard(
                        label = "Teams",
                        value = savedTeams.size.toString(),
                        supporting = "Saved rosters",
                        modifier = Modifier.weight(1f)
                    )
                    DashboardMetricCard(
                        label = "History",
                        value = completedGames.size.toString(),
                        supporting = "Finished games",
                        modifier = Modifier.weight(1f)
                    )
                }

                if (activeGame == null) {
                    Button(
                        onClick = onStartGame,
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Start a Game", fontSize = 18.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onContinueGame,
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("Continue Game", fontSize = 17.sp, textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = { showDiscardConfirm = true },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("Discard Game", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (activeGame == null) onStartGame() else showReplaceConfirm = true
                        },
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(if (activeGame == null) "Start from Setup" else "Start New Game", textAlign = TextAlign.Center)
                    }
                    OutlinedButton(
                        onClick = onManageTeams,
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text("Teams & Players", textAlign = TextAlign.Center)
                    }
                }

                backupMessage?.let {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Text(
                            it,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }

        SectionHeader(
            title = "Previous Games",
            subtitle = "Finished games and full scorecards"
        )
        if (completedGames.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Finished games will appear here with their complete scorecards.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            completedGames.forEach { completed ->
                val game = completed.state
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${game.awayTeamName}  ${game.totalRuns(Team.AWAY)} – ${game.totalRuns(Team.HOME)}  ${game.homeTeamName}",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    completedGameSubtitle(completed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AssistChip(onClick = { }, enabled = false, label = { Text("FINAL") })
                        }
                        OutlinedButton(
                            onClick = { onOpenCompletedGame(completed) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Full Scorecard")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text("Start another game?") },
            text = { Text("You already have a game in progress. Starting a new game will replace that unfinished game only after you finish the setup wizard.") },
            confirmButton = {
                Button(onClick = {
                    showReplaceConfirm = false
                    onStartGame()
                }) { Text("Start New Game") }
            },
            dismissButton = {
                TextButton(onClick = { showReplaceConfirm = false }) { Text("Keep Current Game") }
            }
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard this game permanently?") },
            text = {
                Text(
                    "This permanently deletes the current game's score, inning data, stats, pitches, plays, substitutions, notes, and event history. " +
                        "It will NOT be saved in Previous Games. Saved team rosters will remain. This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirm = false
                        onDiscardActiveGame()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete Game Permanently") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text("Restore an InningTrack backup?") },
            text = { Text("Restoring replaces the saved teams, completed-game history, and active game currently stored on this device. Export a backup first if you may want the current data later.") },
            confirmButton = {
                Button(onClick = {
                    showImportConfirm = false
                    onImportBackup()
                }) { Text("Choose Backup File") }
            },
            dismissButton = { TextButton(onClick = { showImportConfirm = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DashboardMetricCard(
    label: String,
    value: String,
    supporting: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun saveWizardTeamRoster(
    storage: SavedTeamStorage,
    currentTeams: List<SavedTeam>,
    state: GameState,
    team: Team
): List<SavedTeam> {
    val starting = savedTeamFromState(state, team)
    val existing = currentTeams.firstOrNull { it.name.equals(starting.name, ignoreCase = true) }
    if (existing == null) return storage.save(starting)

    val startingNames = starting.lineupNames.map { it.trim().lowercase() }.toSet()
    val extraRows = existing.lineupNames.mapIndexedNotNull { index, name ->
        val cleaned = name.trim()
        if (cleaned.isBlank() || cleaned.lowercase() in startingNames) null
        else cleaned to existing.lineupPositions.getOrNull(index).orEmpty()
    }
    return storage.save(
        starting.copy(
            lineupNames = starting.lineupNames + extraRows.map { it.first },
            lineupPositions = starting.lineupPositions + extraRows.map { it.second }
        )
    )
}

@Composable
private fun TeamManagementScreen(
    savedTeams: List<SavedTeam>,
    onBack: () -> Unit,
    onSaveTeam: (originalName: String, team: SavedTeam) -> Unit,
    onDeleteTeam: (String) -> Unit
) {
    var editing by remember { mutableStateOf<SavedTeam?>(null) }
    var originalName by remember { mutableStateOf("") }
    var deleteCandidate by remember { mutableStateOf<SavedTeam?>(null) }

    BackHandler(enabled = editing == null) {
        onBack()
    }

    if (editing != null) {
        TeamRosterEditorScreen(
            initial = editing!!,
            onCancel = { editing = null },
            onSave = { team ->
                onSaveTeam(originalName, team)
                editing = null
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Home") }
            Text(
                "Teams & Players",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.width(52.dp))
        }
        Text(
            "Manage the reusable team roster here. Saved positions are only defaults — you can change positions for every individual game in the setup wizard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = {
                originalName = ""
                editing = SavedTeam("", emptyList(), emptyList())
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add Team") }

        if (savedTeams.isEmpty()) {
            Text("No saved teams yet. Teams are also saved automatically when you finish the new-game wizard.")
        } else {
            savedTeams.forEach { team ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(team.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${team.lineupNames.size} player${if (team.lineupNames.size == 1) "" else "s"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                originalName = team.name
                                editing = team
                            }) { Text("Edit") }
                            TextButton(onClick = { deleteCandidate = team }) { Text("Delete") }
                        }
                        if (team.lineupNames.isNotEmpty()) {
                            Text(
                                team.lineupNames.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    deleteCandidate?.let { team ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete ${team.name}?") },
            text = { Text("This removes the saved team/roster. Games already saved in history are not changed.") },
            confirmButton = {
                Button(onClick = {
                    onDeleteTeam(team.name)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun TeamRosterEditorScreen(
    initial: SavedTeam,
    onCancel: () -> Unit,
    onSave: (SavedTeam) -> Unit
) {
    var teamName by remember(initial) { mutableStateOf(initial.name) }
    var playerNames by remember(initial) { mutableStateOf(initial.lineupNames.ifEmpty { listOf("") }) }
    var playerPositions by remember(initial) {
        mutableStateOf(List(initial.lineupNames.ifEmpty { listOf("") }.size) { index -> initial.lineupPositions.getOrNull(index).orEmpty() })
    }
    var showDiscardChanges by remember(initial) { mutableStateOf(false) }
    val initialNamesForEditor = remember(initial) { initial.lineupNames.ifEmpty { listOf("") } }
    val initialPositionsForEditor = remember(initial) {
        List(initialNamesForEditor.size) { index -> initial.lineupPositions.getOrNull(index).orEmpty() }
    }
    val hasUnsavedChanges = teamName != initial.name || playerNames != initialNamesForEditor || playerPositions != initialPositionsForEditor

    fun resizePositions(size: Int, values: List<String>): List<String> = List(size) { index -> values.getOrNull(index).orEmpty() }
    fun requestCancel() {
        if (hasUnsavedChanges) showDiscardChanges = true else onCancel()
    }

    BackHandler { requestCancel() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { requestCancel() }) { Text("Cancel") }
            Text(
                if (initial.name.isBlank()) "Add Team" else "Edit Team",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            TextButton(
                onClick = {
                    val rows = playerNames.mapIndexedNotNull { index, raw ->
                        raw.trim().takeIf(String::isNotBlank)?.let { it to playerPositions.getOrNull(index).orEmpty() }
                    }
                    onSave(
                        SavedTeam(
                            name = teamName.trim(),
                            lineupNames = rows.map { it.first },
                            lineupPositions = rows.map { it.second }
                        )
                    )
                },
                enabled = teamName.isNotBlank()
            ) { Text("Save") }
        }

        OutlinedTextField(
            value = teamName,
            onValueChange = { teamName = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Team name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
        )
        Text(
            "Players saved here form your reusable roster. A position is optional and is only a default for future setup; it can be changed every game.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        playerNames.forEachIndexed { index, name ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { changed ->
                        playerNames = playerNames.toMutableList().apply { this[index] = changed }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Player ${index + 1}") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                RosterPositionDropdown(
                    position = playerPositions.getOrNull(index).orEmpty(),
                    onSelect = { selected ->
                        playerPositions = playerPositions.toMutableList().apply {
                            while (size <= index) add("")
                            this[index] = selected
                        }
                    }
                )
                IconButton(
                    onClick = {
                        if (playerNames.size == 1) {
                            playerNames = listOf("")
                            playerPositions = listOf("")
                        } else {
                            playerNames = playerNames.toMutableList().apply { removeAt(index) }
                            playerPositions = playerPositions.toMutableList().apply { if (index in indices) removeAt(index) }
                        }
                    }
                ) { Text("✕") }
            }
        }

        OutlinedButton(
            onClick = {
                playerNames = playerNames + ""
                playerPositions = resizePositions(playerNames.size, playerPositions)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add Player") }
        Spacer(Modifier.height(24.dp))
    }

    if (showDiscardChanges) {
        UnsavedChangesDialog(
            title = "Discard team changes?",
            message = "You have unsaved changes to this team or roster. Going back now will discard those changes.",
            onKeepEditing = { showDiscardChanges = false },
            onDiscard = {
                showDiscardChanges = false
                onCancel()
            }
        )
    }
}

@Composable
private fun RosterPositionDropdown(
    position: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.width(92.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
        ) { Text(position.ifBlank { "—" }) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("No default") }, onClick = {
                expanded = false
                onSelect("")
            })
            DEFENSIVE_POSITIONS.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}

private fun completedGameSubtitle(completed: CompletedGame): String {
    val game = completed.state
    val date = game.gameDate.ifBlank {
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(completed.completedAtEpochMillis))
    }
    val place = listOf(game.ballpark, game.location).filter(String::isNotBlank).joinToString(" • ")
    return if (place.isBlank()) date else "$date • $place"
}

@Composable
private fun GameSetupScreen(
    savedTeams: List<SavedTeam>,
    onCancel: () -> Unit,
    onSaveTeam: (SavedTeam) -> Unit,
    onDeleteTeam: (String) -> Unit,
    onStartGame: (GameState) -> Unit
) {
    var awayName by remember { mutableStateOf("Away") }
    var homeName by remember { mutableStateOf("Home") }
    var inningsText by remember { mutableStateOf("9") }
    var awayLineupText by remember { mutableStateOf("") }
    var homeLineupText by remember { mutableStateOf("") }
    var awayPositions by remember { mutableStateOf(defaultWizardPositions(9)) }
    var homePositions by remember { mutableStateOf(defaultWizardPositions(9)) }
    var gameDate by remember { mutableStateOf("") }
    var ballpark by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var awayPitcher by remember { mutableStateOf("") }
    var homePitcher by remember { mutableStateOf("") }

    var startInMiddle by remember { mutableStateOf(false) }
    var startInningText by remember { mutableStateOf("1") }
    var topOfInning by remember { mutableStateOf(true) }
    var ballsText by remember { mutableStateOf("0") }
    var strikesText by remember { mutableStateOf("0") }
    var outsText by remember { mutableStateOf("0") }
    var awayScoresText by remember { mutableStateOf("") }
    var homeScoresText by remember { mutableStateOf("") }
    var awayBatterText by remember { mutableStateOf("") }
    var homeBatterText by remember { mutableStateOf("") }
    var firstRunnerId by remember { mutableStateOf<Int?>(null) }
    var secondRunnerId by remember { mutableStateOf<Int?>(null) }
    var thirdRunnerId by remember { mutableStateOf<Int?>(null) }

    var stepIndex by remember { mutableStateOf(0) }
    val steps = remember(startInMiddle) {
        buildList {
            add(SetupStep.AWAY_TEAM)
            add(SetupStep.HOME_TEAM)
            add(SetupStep.GAME_INFO)
            add(SetupStep.PITCHERS)
            add(SetupStep.LINEUPS)
            if (startInMiddle) add(SetupStep.STARTING_POINT)
        }
    }
    if (stepIndex > steps.lastIndex) stepIndex = steps.lastIndex
    val step = steps[stepIndex]
    val innings = inningsText.toIntOrNull()?.takeIf { it in 1..20 }
    val awayPlayers = parseLineup(awayLineupText, awayName.ifBlank { "Away" }, 1)
    val homePlayers = parseLineup(homeLineupText, homeName.ifBlank { "Home" }, 1001)

    fun loadSavedTeam(saved: SavedTeam, away: Boolean) {
        if (away) {
            awayName = saved.name
            awayLineupText = saved.lineupNames.joinToString("\n")
            awayPositions = normalizedSavedPositions(saved)
        } else {
            homeName = saved.name
            homeLineupText = saved.lineupNames.joinToString("\n")
            homePositions = normalizedSavedPositions(saved)
        }
    }

    val canAdvance = when (step) {
        SetupStep.AWAY_TEAM -> awayName.isNotBlank()
        SetupStep.HOME_TEAM -> homeName.isNotBlank()
        SetupStep.GAME_INFO -> innings != null
        else -> true
    }
    var showDiscardSetup by remember { mutableStateOf(false) }
    val hasSetupChanges = stepIndex > 0 ||
        awayName != "Away" || homeName != "Home" || inningsText != "9" ||
        awayLineupText.isNotBlank() || homeLineupText.isNotBlank() ||
        awayPositions != defaultWizardPositions(9) || homePositions != defaultWizardPositions(9) ||
        gameDate.isNotBlank() || ballpark.isNotBlank() || location.isNotBlank() || notes.isNotBlank() ||
        awayPitcher.isNotBlank() || homePitcher.isNotBlank() || startInMiddle ||
        startInningText != "1" || !topOfInning || ballsText != "0" || strikesText != "0" || outsText != "0" ||
        awayScoresText.isNotBlank() || homeScoresText.isNotBlank() || awayBatterText.isNotBlank() || homeBatterText.isNotBlank() ||
        firstRunnerId != null || secondRunnerId != null || thirdRunnerId != null

    fun requestExitSetup() {
        if (hasSetupChanges) showDiscardSetup = true else onCancel()
    }

    BackHandler {
        if (stepIndex > 0) stepIndex-- else requestExitSetup()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { requestExitSetup() }) { Text("Cancel") }
            Text(
                "New Game",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Text("${stepIndex + 1}/${steps.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LinearProgressIndicator(
            progress = { (stepIndex + 1).toFloat() / steps.size.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )

        when (step) {
            SetupStep.AWAY_TEAM -> TeamWizardStep(
                title = "Away Team",
                teamName = awayName,
                onTeamNameChange = { awayName = it },
                savedTeams = savedTeams,
                onLoadSavedTeam = { loadSavedTeam(it, true) }
            )

            SetupStep.HOME_TEAM -> TeamWizardStep(
                title = "Home Team",
                teamName = homeName,
                onTeamNameChange = { homeName = it },
                savedTeams = savedTeams,
                onLoadSavedTeam = { loadSavedTeam(it, false) }
            )

            SetupStep.GAME_INFO -> {
                WizardHeader("Game Information", "Everything here except scheduled innings is optional.")
                OutlinedTextField(
                    value = inningsText,
                    onValueChange = { inningsText = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Scheduled innings") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = innings == null
                )
                OutlinedTextField(value = gameDate, onValueChange = { gameDate = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Date") }, singleLine = true)
                OutlinedTextField(value = ballpark, onValueChange = { ballpark = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ballpark") }, singleLine = true)
                OutlinedTextField(value = location, onValueChange = { location = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Location") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 3)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Switch(checked = startInMiddle, onCheckedChange = { startInMiddle = it })
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Game already in progress", fontWeight = FontWeight.SemiBold)
                            Text("Turn this on to enter the current inning, score, count, batter and baserunners.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            SetupStep.PITCHERS -> {
                WizardHeader("Starting Pitchers", "Enter the pitchers you know now. You can change them during the game.")
                OutlinedTextField(
                    value = awayPitcher,
                    onValueChange = { awayPitcher = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${awayName.ifBlank { "Away" }} pitcher") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                OutlinedTextField(
                    value = homePitcher,
                    onValueChange = { homePitcher = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("${homeName.ifBlank { "Home" }} pitcher") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
            }

            SetupStep.LINEUPS -> {
                WizardHeader("Full Lineups", "Load a saved roster from a previous game or edit every batting-order spot here.")
                LineupWizardTeamEditor(
                    sideLabel = "Away",
                    teamName = awayName,
                    lineupText = awayLineupText,
                    onLineupTextChange = { awayLineupText = it },
                    positions = awayPositions,
                    onPositionsChange = { awayPositions = it },
                    savedTeams = savedTeams,
                    onLoadSavedTeam = { loadSavedTeam(it, true) },
                    onSaveTeam = {
                        val names = parseLineup(awayLineupText, awayName.ifBlank { "Away" }, 1).map(Player::name)
                        onSaveTeam(SavedTeam(awayName.ifBlank { "Away" }, names, awayPositions.take(names.size)))
                    },
                    onDeleteTeam = onDeleteTeam
                )
                HorizontalDivider()
                LineupWizardTeamEditor(
                    sideLabel = "Home",
                    teamName = homeName,
                    lineupText = homeLineupText,
                    onLineupTextChange = { homeLineupText = it },
                    positions = homePositions,
                    onPositionsChange = { homePositions = it },
                    savedTeams = savedTeams,
                    onLoadSavedTeam = { loadSavedTeam(it, false) },
                    onSaveTeam = {
                        val names = parseLineup(homeLineupText, homeName.ifBlank { "Home" }, 1001).map(Player::name)
                        onSaveTeam(SavedTeam(homeName.ifBlank { "Home" }, names, homePositions.take(names.size)))
                    },
                    onDeleteTeam = onDeleteTeam
                )
            }

            SetupStep.STARTING_POINT -> {
                WizardHeader("Current Game State", "Enter where the game is right now, then InningTrack will continue from there.")
                MidGameSetupCard(
                    awayPlayers = awayPlayers,
                    homePlayers = homePlayers,
                    startInningText = startInningText,
                    onStartInningTextChange = { startInningText = it },
                    topOfInning = topOfInning,
                    onTopOfInningChange = { topOfInning = it },
                    ballsText = ballsText,
                    onBallsTextChange = { ballsText = it },
                    strikesText = strikesText,
                    onStrikesTextChange = { strikesText = it },
                    outsText = outsText,
                    onOutsTextChange = { outsText = it },
                    awayScoresText = awayScoresText,
                    onAwayScoresTextChange = { awayScoresText = it },
                    homeScoresText = homeScoresText,
                    onHomeScoresTextChange = { homeScoresText = it },
                    awayBatterText = awayBatterText,
                    onAwayBatterTextChange = { awayBatterText = it },
                    homeBatterText = homeBatterText,
                    onHomeBatterTextChange = { homeBatterText = it },
                    firstRunnerId = firstRunnerId,
                    onFirstRunnerIdChange = { firstRunnerId = it },
                    secondRunnerId = secondRunnerId,
                    onSecondRunnerIdChange = { secondRunnerId = it },
                    thirdRunnerId = thirdRunnerId,
                    onThirdRunnerIdChange = { thirdRunnerId = it }
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { if (stepIndex > 0) stepIndex-- else requestExitSetup() },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (stepIndex == 0) "Cancel" else "Back")
            }
            Button(
                onClick = {
                    if (stepIndex < steps.lastIndex) {
                        stepIndex++
                    } else {
                        val resolvedInnings = innings ?: return@Button
                        val midSetup = MidGameSetup(
                            enabled = startInMiddle,
                            inning = startInningText.toIntOrNull()?.coerceIn(1, 20) ?: 1,
                            topOfInning = topOfInning,
                            balls = ballsText.toIntOrNull()?.coerceIn(0, 3) ?: 0,
                            strikes = strikesText.toIntOrNull()?.coerceIn(0, 2) ?: 0,
                            outs = outsText.toIntOrNull()?.coerceIn(0, 2) ?: 0,
                            awayScore = awayScoresText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            homeScore = homeScoresText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            awayBatterIndex = awayBatterText.toIntOrNull()?.let { (it - 1).coerceAtLeast(0) } ?: -1,
                            homeBatterIndex = homeBatterText.toIntOrNull()?.let { (it - 1).coerceAtLeast(0) } ?: -1,
                            firstRunnerId = firstRunnerId,
                            secondRunnerId = secondRunnerId,
                            thirdRunnerId = thirdRunnerId
                        )
                        onStartGame(
                            buildGameStateFromSetup(
                                awayName = awayName.ifBlank { "Away" },
                                homeName = homeName.ifBlank { "Home" },
                                innings = resolvedInnings,
                                awayPlayers = awayPlayers,
                                homePlayers = homePlayers,
                                midGameSetup = midSetup,
                                gameDate = gameDate,
                                ballpark = ballpark,
                                location = location,
                                notes = notes,
                                awayPitcher = awayPitcher,
                                homePitcher = homePitcher,
                                awayPositions = awayPositions,
                                homePositions = homePositions
                            )
                        )
                    }
                },
                enabled = canAdvance,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (stepIndex == steps.lastIndex) "Start Game" else "Next")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showDiscardSetup) {
        UnsavedChangesDialog(
            title = "Discard game setup?",
            message = "You have information in this new-game setup that has not been started as a game. Going back to Home will discard it.",
            onKeepEditing = { showDiscardSetup = false },
            onDiscard = {
                showDiscardSetup = false
                onCancel()
            }
        )
    }
}

@Composable
private fun WizardHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TeamWizardStep(
    title: String,
    teamName: String,
    onTeamNameChange: (String) -> Unit,
    savedTeams: List<SavedTeam>,
    onLoadSavedTeam: (SavedTeam) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    WizardHeader(title, "Name the team or load a team you have used before.")
    if (savedTeams.isNotEmpty()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Load Saved Team")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                savedTeams.forEach { saved ->
                    DropdownMenuItem(
                        text = { Text("${saved.name} (${saved.lineupNames.size} players)") },
                        onClick = {
                            expanded = false
                            onLoadSavedTeam(saved)
                        }
                    )
                }
            }
        }
    }
    OutlinedTextField(
        value = teamName,
        onValueChange = onTeamNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Team name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
    )
}

@Composable
private fun LineupWizardTeamEditor(
    sideLabel: String,
    teamName: String,
    lineupText: String,
    onLineupTextChange: (String) -> Unit,
    positions: List<String>,
    onPositionsChange: (List<String>) -> Unit,
    savedTeams: List<SavedTeam>,
    onLoadSavedTeam: (SavedTeam) -> Unit,
    onSaveTeam: () -> Unit,
    onDeleteTeam: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rows = lineupRows(lineupText)
    val resolvedPositions = List(rows.size) { index -> positions.getOrNull(index).orEmpty() }
    val matching = savedTeams.firstOrNull { it.name.equals(teamName.trim(), ignoreCase = true) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$sideLabel — ${teamName.ifBlank { sideLabel }}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = savedTeams.isNotEmpty()) {
                    Text("Use Saved Lineup")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    savedTeams.forEach { saved ->
                        DropdownMenuItem(text = { Text(saved.name) }, onClick = {
                            expanded = false
                            onLoadSavedTeam(saved)
                        })
                    }
                }
            }
            OutlinedButton(onClick = onSaveTeam) { Text(if (matching == null) "Save" else "Update") }
        }
        Text(
            "The pitcher is separate and does not bat. These nine batting spots use C, 1B, 2B, 3B, SS, LF, CF, RF and DH. Positions are for this game and can be changed; choosing an occupied position swaps assignments.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        rows.forEachIndexed { index, value ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { changed ->
                        val updated = rows.toMutableList()
                        updated[index] = changed
                        onLineupTextChange(updated.joinToString("\n"))
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("${index + 1}. Batter") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                WizardPositionDropdown(
                    position = resolvedPositions[index],
                    occupiedPositions = resolvedPositions,
                    rowIndex = index,
                    onSelect = { selected ->
                        val updated = resolvedPositions.toMutableList()
                        val old = updated[index]
                        if (selected.isBlank()) {
                            updated[index] = ""
                        } else {
                            val occupant = updated.indexOfFirst { it == selected }
                            updated[index] = selected
                            if (occupant >= 0 && occupant != index) updated[occupant] = old
                        }
                        onPositionsChange(updated)
                    }
                )
            }
        }
        matching?.let { saved ->
            TextButton(onClick = { onDeleteTeam(saved.name) }) { Text("Delete saved ${saved.name}") }
        }
    }
}

@Composable
private fun WizardPositionDropdown(
    position: String,
    occupiedPositions: List<String>,
    rowIndex: Int,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.width(92.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Text(position.ifBlank { "—" })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("—") }, onClick = {
                expanded = false
                onSelect("")
            })
            DEFENSIVE_POSITIONS.forEach { option ->
                val occupant = occupiedPositions.indexOfFirst { it == option }.takeIf { it >= 0 && it != rowIndex }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option)
                            if (occupant != null) {
                                Text("Swap with #${occupant + 1}", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

private fun lineupRows(text: String): List<String> {
    val rows = text.split("\n").take(9).toMutableList()
    if (rows.size == 1 && rows.first().isBlank()) rows.clear()
    while (rows.size < 9) rows += ""
    return rows
}

@Composable
private fun TeamSetupEditor(
    sideLabel: String,
    teamName: String,
    onTeamNameChange: (String) -> Unit,
    lineupText: String,
    onLineupTextChange: (String) -> Unit,
    savedTeams: List<SavedTeam>,
    onLoadTeam: (SavedTeam) -> Unit,
    onSaveTeam: () -> Unit,
    onDeleteTeam: (String) -> Unit
) {
    var savedTeamsExpanded by remember { mutableStateOf(false) }
    val matchingSavedTeam = savedTeams.firstOrNull { it.name.equals(teamName.trim(), ignoreCase = true) }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            text = "$sideLabel Team",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        if (savedTeams.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { savedTeamsExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Load Saved Team")
                }
                DropdownMenu(
                    expanded = savedTeamsExpanded,
                    onDismissRequest = { savedTeamsExpanded = false }
                ) {
                    savedTeams.forEach { savedTeam ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(savedTeam.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${savedTeam.lineupNames.size} players",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                savedTeamsExpanded = false
                                onLoadTeam(savedTeam)
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = teamName,
            onValueChange = onTeamNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("$sideLabel team name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
        )

        OutlinedTextField(
            value = lineupText,
            onValueChange = onLineupTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("${teamName.ifBlank { sideLabel }} lineup") },
            supportingText = { Text("One player per line. Leave blank for nine placeholder players.") },
            minLines = 4,
            maxLines = 9,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onSaveTeam,
                modifier = Modifier.weight(1f),
                enabled = teamName.isNotBlank()
            ) {
                Text(if (matchingSavedTeam == null) "Save Team" else "Update Saved Team")
            }
            if (matchingSavedTeam != null) {
                TextButton(onClick = { onDeleteTeam(matchingSavedTeam.name) }) {
                    Text("Delete Saved")
                }
            }
        }
    }
}

private fun lineupNamesFromText(text: String): List<String> = text.lines()
    .map(String::trim)
    .filter(String::isNotEmpty)

private fun parseLineup(
    text: String,
    fallbackPrefix: String,
    idStart: Int
): List<Player> {
    val names = text.lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(9)

    if (names.isEmpty()) {
        return GameController.defaultLineup(fallbackPrefix, idStart)
    }

    return List(9) { index ->
        Player(
            id = idStart + index,
            name = names.getOrNull(index)?.takeIf(String::isNotBlank) ?: "$fallbackPrefix Player ${index + 1}"
        )
    }
}

@Composable
private fun GameScreen(
    state: GameState,
    canUndo: Boolean,
    canRedo: Boolean,
    onBall: () -> Unit,
    onStrike: () -> Unit,
    onRemoveBall: () -> Unit,
    onRemoveStrike: () -> Unit,
    onFoul: () -> Unit,
    onHitByPitch: () -> Unit,
    onRemoveRecordedPitch: (Long) -> Unit,
    onAddRecordedPitch: (Long, PitchAction) -> Unit,
    onRenameCurrentBatter: (String) -> Unit,
    onRenameTeam: (Team, String) -> Unit,
    onRenamePlayer: (Int, String) -> Unit,
    onSaveCurrentTeam: (Team) -> Unit,
    onPinchHit: (String) -> Unit,
    onPinchRun: (Base, String) -> Unit,
    onPitchingChange: (String) -> Unit,
    onPitchingChangeForTeam: (Team, String) -> Unit,
    onSetPitchCount: (Team, String, Int) -> Unit,
    onPositionChange: (Int, String) -> Unit,
    onReorderLineup: (Team, Int, Int) -> Unit,
    onSetCurrentBatter: (Team, Int?) -> Unit,
    onResolveUnknownPlayer: (Team, Int, Int) -> Unit,
    onEditGameSituation: (GameSituationUpdate) -> Unit,
    onEditPersonnelEvent: (Long, String) -> Unit,
    onEditRecordedAtBat: (Long, List<PitchAction>, PlayAction, Int?, String?, String?) -> Unit,
    onPlay: (PlayAction, Map<Int, Base?>, Int, FieldingPlay?) -> Unit,
    onBaseRunning: (BaseRunningAction, Int?, Base?, String?) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onEndGame: () -> Unit,
    onDiscardGame: () -> Unit,
    onHome: () -> Unit
) {
    var pendingPlay by remember { mutableStateOf<PlayAction?>(null) }
    var warningPlay by remember { mutableStateOf<PlayAction?>(null) }
    var showEndConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var showBaseRunningDialog by remember { mutableStateOf(false) }
    var gameView by remember { mutableStateOf(GameView.SCOREKEEPING) }

    BackHandler {
        if (gameView != GameView.SCOREKEEPING) gameView = GameView.SCOREKEEPING else onHome()
    }

    val activePlayWarning = warningPlay?.let { smartWarningForPlay(state, it) }

    fun continuePlay(action: PlayAction) {
        if (shouldResolveRunners(state, action)) {
            pendingPlay = action
        } else {
            onPlay(action, emptyMap(), action.defaultOuts, null)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "InningTrack",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${state.awayTeamName} at ${state.homeTeamName}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${if (state.topOfInning) "Top" else "Bottom"} ${state.currentInning} • ${state.outs} out${if (state.outs == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onHome) {
                        Text("Home")
                    }
                }

                GameViewSwitcher(
                    selected = gameView,
                    onSelected = { gameView = it }
                )
            }
        }

        when (gameView) {
            GameView.SCOREKEEPING -> {
                ScoreboardCard(state)

                if (state.gameOver) {
                    FinalGameCard(state)
                    Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
                        Text("Saved — Return Home")
                    }
                    OutlinedButton(
                        onClick = onUndo,
                        enabled = canUndo,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Undo Last Play")
                    }
                } else {
                    GameStatusCard(
                        state = state,
                        onBall = onBall,
                        onStrike = onStrike,
                        onFoul = onFoul,
                        onRemoveBall = onRemoveBall,
                        onRemoveStrike = onRemoveStrike,
                        onRemoveRecordedPitch = onRemoveRecordedPitch,
                        onAddRecordedPitch = onAddRecordedPitch,
                        onRenameCurrentBatter = onRenameCurrentBatter,
                        onPinchHit = onPinchHit,
                        onPinchRun = onPinchRun,
                        onPitchingChange = onPitchingChange,
                        onSetPitchCount = onSetPitchCount,
                        onPositionChange = onPositionChange,
                        onSetCurrentBatter = onSetCurrentBatter,
                        onHitByPitch = onHitByPitch,
                        onAction = { action ->
                            if (smartWarningForPlay(state, action) != null) {
                                warningPlay = action
                            } else {
                                continuePlay(action)
                            }
                        },
                        onRunnerEvent = { showBaseRunningDialog = true }
                    )
                    DiamondCard(state)
                    GameManagementCard(
                        state = state,
                        onPinchHit = onPinchHit,
                        onPinchRun = onPinchRun,
                        onPitchingChange = onPitchingChange,
                        onPositionChange = onPositionChange
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Game Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.weight(1f)) { Text("Undo") }
                                OutlinedButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.weight(1f)) { Text("Redo") }
                                OutlinedButton(onClick = { showEndConfirm = true }, modifier = Modifier.weight(1f)) { Text("End Game") }
                            }
                            TextButton(
                                onClick = { showDiscardConfirm = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Discard Game", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (LineupEngine.currentBatter(state) == null) {
                        MissingCurrentBatterCard(
                            state = state,
                            onSetCurrentBatter = onSetCurrentBatter
                        )
                    }
                }
            }

            GameView.OVERVIEW -> GameOverview(
                state = state,
                canUndo = canUndo,
                canRedo = canRedo,
                onUndo = onUndo,
                onRedo = onRedo,
                onRenameCurrentBatter = onRenameCurrentBatter,
                onRenameTeam = onRenameTeam,
                onRenamePlayer = onRenamePlayer,
                onSaveCurrentTeam = onSaveCurrentTeam,
                onPinchHit = onPinchHit,
                onPinchRun = onPinchRun,
                onPitchingChange = onPitchingChange,
                onPitchingChangeForTeam = onPitchingChangeForTeam,
                onPositionChange = onPositionChange,
                onReorderLineup = onReorderLineup,
                onSetCurrentBatter = onSetCurrentBatter,
                onResolveUnknownPlayer = onResolveUnknownPlayer,
                onEditGameSituation = onEditGameSituation,
                onEditPersonnelEvent = onEditPersonnelEvent
            )
            GameView.SCORECARD -> FullScorecardScreen(
                state = state,
                onEditAtBat = onEditRecordedAtBat
            )
            GameView.STATS -> LiveStatsScreen(state)
        }

        Spacer(Modifier.height(18.dp))
    }

    if (warningPlay != null && activePlayWarning != null) {
        AlertDialog(
            onDismissRequest = { warningPlay = null },
            title = { Text(activePlayWarning.title) },
            text = { Text(activePlayWarning.message) },
            confirmButton = {
                Button(
                    onClick = {
                        val action = warningPlay
                        warningPlay = null
                        if (action != null) continuePlay(action)
                    }
                ) {
                    Text(activePlayWarning.confirmText)
                }
            },
            dismissButton = {
                TextButton(onClick = { warningPlay = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    pendingPlay?.let { action ->
        RunnerResolutionDialog(
            state = state,
            action = action,
            onDismiss = { pendingPlay = null },
            onCommit = { destinations, outs, fielding ->
                pendingPlay = null
                onPlay(action, destinations, outs, fielding)
            }
        )
    }

    if (showBaseRunningDialog) {
        BaseRunningEventDialog(
            state = state,
            onDismiss = { showBaseRunningDialog = false },
            onCommit = { action, runnerId, destination, fielder ->
                showBaseRunningDialog = false
                onBaseRunning(action, runnerId, destination, fielder)
            }
        )
    }

    if (showEndConfirm) {
        AlertDialog(
            onDismissRequest = { showEndConfirm = false },
            title = { Text("End this game?") },
            text = { Text(smartEndGameMessage(state)) },
            confirmButton = {
                Button(onClick = {
                    showEndConfirm = false
                    onEndGame()
                }) {
                    Text("End Game")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard this game permanently?") },
            text = {
                Text(
                    "This permanently deletes the current game's score, inning data, stats, pitches, plays, substitutions, notes, and event history. " +
                        "It will NOT be saved in Previous Games. Saved team rosters will remain. This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDiscardConfirm = false
                        onDiscardGame()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete Game Permanently") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GameViewSwitcher(
    selected: GameView,
    onSelected: (GameView) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameViewTabButton(
                label = "Scorekeeping",
                selected = selected == GameView.SCOREKEEPING,
                modifier = Modifier.weight(1f),
                onClick = { onSelected(GameView.SCOREKEEPING) }
            )
            GameViewTabButton(
                label = "Game Overview",
                selected = selected == GameView.OVERVIEW,
                modifier = Modifier.weight(1f),
                onClick = { onSelected(GameView.OVERVIEW) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameViewTabButton(
                label = "Scorecard",
                selected = selected == GameView.SCORECARD,
                modifier = Modifier.weight(1f),
                onClick = { onSelected(GameView.SCORECARD) }
            )
            GameViewTabButton(
                label = "Stats",
                selected = selected == GameView.STATS,
                modifier = Modifier.weight(1f),
                onClick = { onSelected(GameView.STATS) }
            )
        }
    }
}

@Composable
private fun GameViewTabButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, textAlign = TextAlign.Center)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GameOverview(
    state: GameState,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onRenameCurrentBatter: (String) -> Unit,
    onRenameTeam: (Team, String) -> Unit,
    onRenamePlayer: (Int, String) -> Unit,
    onSaveCurrentTeam: (Team) -> Unit,
    onPinchHit: (String) -> Unit,
    onPinchRun: (Base, String) -> Unit,
    onPitchingChange: (String) -> Unit,
    onPitchingChangeForTeam: (Team, String) -> Unit,
    onPositionChange: (Int, String) -> Unit,
    onReorderLineup: (Team, Int, Int) -> Unit,
    onSetCurrentBatter: (Team, Int?) -> Unit,
    onResolveUnknownPlayer: (Team, Int, Int) -> Unit,
    onEditGameSituation: (GameSituationUpdate) -> Unit,
    onEditPersonnelEvent: (Long, String) -> Unit
) {
    ScoreboardCard(state)

    if (state.gameOver) {
        FinalGameCard(state)
    }

    CurrentGameSituationCard(
        state = state,
        enabled = !state.gameOver,
        onEditGameSituation = onEditGameSituation
    )

    PersonnelManagementCard(
        state = state,
        enabled = !state.gameOver,
        onRenameCurrentBatter = onRenameCurrentBatter,
        onPinchHit = onPinchHit,
        onPinchRun = onPinchRun,
        onPitchingChange = onPitchingChange,
        onPositionChange = onPositionChange
    )

    CurrentPersonnelCard(
        state = state,
        onRenameTeam = onRenameTeam,
        onRenamePlayer = onRenamePlayer,
        onPitchingChange = onPitchingChangeForTeam,
        onPositionChange = onPositionChange,
        onReorderLineup = onReorderLineup,
        onSaveCurrentTeam = onSaveCurrentTeam
    )

    GameLogCard(
        state = state,
        onEditPersonnelEvent = onEditPersonnelEvent
    )

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.weight(1f)) {
            Text(if (state.gameOver) "Undo Change" else "Undo")
        }
        OutlinedButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.weight(1f)) {
            Text("Redo")
        }
    }
}

@Composable
private fun PersonnelManagementCard(
    state: GameState,
    enabled: Boolean,
    onRenameCurrentBatter: (String) -> Unit,
    onPinchHit: (String) -> Unit,
    onPinchRun: (Base, String) -> Unit,
    onPitchingChange: (String) -> Unit,
    onPositionChange: (Int, String) -> Unit
) {
    val batter = LineupEngine.currentBatter(state)
    val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
    var showEditBatter by remember { mutableStateOf(false) }
    var showPinchHitter by remember { mutableStateOf(false) }
    var showPinchRunner by remember { mutableStateOf(false) }
    var showPitchingChange by remember { mutableStateOf(false) }
    var showPositionChange by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Personnel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Make current substitutions here. Historical personnel changes can be corrected in the inning log below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showEditBatter = true },
                    enabled = enabled && batter != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Edit Batter", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = { showPinchHitter = true },
                    enabled = enabled && batter != null,
                    modifier = Modifier.weight(1f)
                ) { Text("Pinch Hitter", textAlign = TextAlign.Center) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showPinchRunner = true },
                    enabled = enabled && !state.bases.isEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Pinch Runner", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = { showPitchingChange = true },
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) { Text("Pitcher", textAlign = TextAlign.Center) }
            }
            OutlinedButton(
                onClick = { showPositionChange = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Position Change") }
        }
    }

    if (showEditBatter && batter != null) {
        NameEntryDialog(
            title = "Edit batter name",
            label = "Player name",
            initialValue = batter.name,
            confirmText = "Save",
            onDismiss = { showEditBatter = false },
            onConfirm = {
                showEditBatter = false
                onRenameCurrentBatter(it)
            }
        )
    }
    if (showPinchHitter && batter != null) {
        NameEntryDialog(
            title = "Pinch hitter",
            label = "New hitter name",
            confirmText = "Substitute",
            supportingText = "${batter.name} will be replaced in this batting-order spot.",
            onDismiss = { showPinchHitter = false },
            onConfirm = {
                showPinchHitter = false
                onPinchHit(it)
            }
        )
    }
    if (showPinchRunner) {
        PinchRunnerDialog(
            state = state,
            onDismiss = { showPinchRunner = false },
            onConfirm = { base, name ->
                showPinchRunner = false
                onPinchRun(base, name)
            }
        )
    }
    if (showPitchingChange) {
        NameEntryDialog(
            title = "Pitching change",
            label = "New pitcher name",
            initialValue = state.pitcherName(fieldingTeam),
            confirmText = "Make Change",
            supportingText = "Changing pitcher for ${state.teamName(fieldingTeam)}.",
            onDismiss = { showPitchingChange = false },
            onConfirm = {
                showPitchingChange = false
                onPitchingChange(it)
            }
        )
    }
    if (showPositionChange) {
        PositionChangeDialog(
            state = state,
            onDismiss = { showPositionChange = false },
            onConfirm = { playerId, position ->
                showPositionChange = false
                onPositionChange(playerId, position)
            }
        )
    }
}

@Composable
private fun CurrentPersonnelCard(
    state: GameState,
    onRenameTeam: (Team, String) -> Unit,
    onRenamePlayer: (Int, String) -> Unit,
    onPitchingChange: (Team, String) -> Unit,
    onPositionChange: (Int, String) -> Unit,
    onReorderLineup: (Team, Int, Int) -> Unit,
    onSaveCurrentTeam: (Team) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Current Personnel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Edit names directly. Position changes never create duplicates; choosing an occupied position swaps the two players.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TeamPersonnelSection(
                state = state,
                team = Team.AWAY,
                onRenameTeam = onRenameTeam,
                onRenamePlayer = onRenamePlayer,
                onPitchingChange = onPitchingChange,
                onPositionChange = onPositionChange,
                onReorderLineup = onReorderLineup,
                onSaveCurrentTeam = onSaveCurrentTeam
            )
            HorizontalDivider()
            TeamPersonnelSection(
                state = state,
                team = Team.HOME,
                onRenameTeam = onRenameTeam,
                onRenamePlayer = onRenamePlayer,
                onPitchingChange = onPitchingChange,
                onPositionChange = onPositionChange,
                onReorderLineup = onReorderLineup,
                onSaveCurrentTeam = onSaveCurrentTeam
            )
        }
    }
}

@Composable
private fun TeamPersonnelSection(
    state: GameState,
    team: Team,
    onRenameTeam: (Team, String) -> Unit,
    onRenamePlayer: (Int, String) -> Unit,
    onPitchingChange: (Team, String) -> Unit,
    onPositionChange: (Int, String) -> Unit,
    onReorderLineup: (Team, Int, Int) -> Unit,
    onSaveCurrentTeam: (Team) -> Unit
) {
    val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
    var savedNotice by remember(state.teamName(team)) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            InlinePersonnelNameField(
                value = state.teamName(team),
                label = if (team == Team.AWAY) "Away team name" else "Home team name",
                onCommit = { onRenameTeam(team, it) },
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                onSaveCurrentTeam(team)
                savedNotice = true
            }) {
                Text("Save Team")
            }
        }
        if (savedNotice) {
            Text(
                "Saved ${state.teamName(team)} for future games.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        InlinePersonnelNameField(
            value = state.pitcherName(team),
            label = "Current pitcher",
            onCommit = { onPitchingChange(team, it) },
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            "Long-press and drag the ☰ handle to reorder the batting lineup.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        lineup.forEachIndexed { index, player ->
            DraggablePersonnelRow(
                state = state,
                team = team,
                player = player,
                index = index,
                lastIndex = lineup.lastIndex,
                onRenamePlayer = onRenamePlayer,
                onPositionChange = onPositionChange,
                onMove = { from, to -> onReorderLineup(team, from, to) }
            )
        }
    }
}

@Composable
private fun InlinePersonnelNameField(
    value: String,
    label: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember(value) { mutableStateOf(value) }

    fun commit() {
        val cleaned = draft.trim()
        when {
            cleaned.isEmpty() -> draft = value
            cleaned != value -> onCommit(cleaned)
        }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier.onFocusChanged { focusState ->
            if (!focusState.isFocused) commit()
        }
    )
}

@Composable
private fun PersonnelPositionDropdown(
    state: GameState,
    team: Team,
    player: Player,
    onPositionChange: (Int, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val currentPosition = state.playerPosition(player.id) ?: "—"
    val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
    val teammateIds = lineup.map(Player::id).toSet()

    Box(modifier = Modifier.width(104.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
        ) {
            Text(currentPosition, maxLines = 1)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DEFENSIVE_POSITIONS.forEach { position ->
                val occupantId = state.playerPositions.entries
                    .firstOrNull { (otherId, otherPosition) ->
                        otherId != player.id && otherId in teammateIds && otherPosition == position
                    }
                    ?.key
                val occupantName = occupantId?.let(state::playerName)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(position, fontWeight = if (position == currentPosition) FontWeight.Bold else FontWeight.Normal)
                            if (occupantName != null) {
                                Text(
                                    "Swap with $occupantName",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (position != currentPosition) {
                            onPositionChange(player.id, position)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun GameLogCard(
    state: GameState,
    onEditPersonnelEvent: (Long, String) -> Unit,
    allowEditing: Boolean = true
) {
    var editingEvent by remember { mutableStateOf<GameEvent?>(null) }
    val grouped = state.events.groupBy { it.inning }.toSortedMap()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Game Log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (state.events.isEmpty()) {
                Text(
                    "No game events recorded yet. New pitches, plays and personnel changes will appear here by inning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                grouped.forEach { (inning, events) ->
                    Text(
                        "Inning $inning",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    listOf(true, false).forEach { top ->
                        val halfEvents = events.filter { it.topOfInning == top }
                        if (halfEvents.isNotEmpty()) {
                            Text(
                                if (top) "Top" else "Bottom",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            halfEvents.forEach { event ->
                                GameEventRow(
                                    event = event,
                                    onEdit = if (allowEditing && event.isPersonnelEvent) ({ editingEvent = event }) else null
                                )
                            }
                        }
                    }
                    if (inning != grouped.keys.last()) HorizontalDivider()
                }
            }
        }
    }

    editingEvent?.let { event ->
        if (event.type == GameEventType.POSITION_CHANGE) {
            PositionEditDialog(
                event = event,
                onDismiss = { editingEvent = null },
                onConfirm = { value ->
                    editingEvent = null
                    onEditPersonnelEvent(event.id, value)
                }
            )
        } else {
            NameEntryDialog(
                title = when (event.type) {
                    GameEventType.PITCHING_CHANGE -> "Correct pitching change"
                    GameEventType.PINCH_HITTER -> "Correct pinch hitter"
                    GameEventType.PINCH_RUNNER -> "Correct pinch runner"
                    GameEventType.BATTER_EDIT -> "Correct batter name"
                    GameEventType.PLAYER_EDIT -> "Correct player name"
                    GameEventType.TEAM_EDIT -> "Correct team name"
                    else -> "Correct personnel change"
                },
                label = when (event.type) {
                    GameEventType.PITCHING_CHANGE -> "Pitcher name"
                    GameEventType.TEAM_EDIT -> "Team name"
                    else -> "Player name"
                },
                initialValue = event.actorName.orEmpty(),
                confirmText = "Save Correction",
                supportingText = "This edits the recorded game history and updates the current roster when this is still the active change.",
                onDismiss = { editingEvent = null },
                onConfirm = { value ->
                    editingEvent = null
                    onEditPersonnelEvent(event.id, value)
                }
            )
        }
    }
}

@Composable
private fun GameEventRow(
    event: GameEvent,
    onEdit: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(event.title, fontWeight = if (event.isPersonnelEvent) FontWeight.SemiBold else FontWeight.Medium)
            if (event.detail.isNotBlank()) {
                Text(
                    event.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        onEdit?.let {
            TextButton(onClick = it) { Text("Edit") }
        }
    }
}

@Composable
private fun PositionEditDialog(
    event: GameEvent,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var position by remember(event.id) { mutableStateOf(event.position ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct position change") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(event.actorName ?: "Player", fontWeight = FontWeight.SemiBold)
                PositionChoices(
                    selected = position,
                    onSelected = { position = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(position) },
                enabled = position.isNotBlank()
            ) { Text("Save Correction") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PositionChangeDialog(
    state: GameState,
    onDismiss: () -> Unit,
    onConfirm: (Int, String) -> Unit
) {
    val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
    val lineup = if (fieldingTeam == Team.AWAY) state.lineupAway else state.lineupHome
    var playerId by remember(state.currentInning, state.topOfInning) { mutableStateOf(lineup.firstOrNull()?.id) }
    var position by remember(playerId) {
        mutableStateOf(playerId?.let(state::playerPosition) ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Position change") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(
                    "${state.teamName(fieldingTeam)} is in the field. Choose a player and the new defensive position.",
                    style = MaterialTheme.typography.bodySmall
                )
                lineup.forEach { player ->
                    FilterChip(
                        selected = playerId == player.id,
                        onClick = {
                            playerId = player.id
                            position = state.playerPosition(player.id) ?: ""
                        },
                        label = { Text("${player.name} (${state.playerPosition(player.id) ?: "—"})") }
                    )
                }
                Text("New position", fontWeight = FontWeight.SemiBold)
                PositionChoices(selected = position, onSelected = { position = it })
            }
        },
        confirmButton = {
            Button(
                onClick = { playerId?.let { onConfirm(it, position) } },
                enabled = playerId != null && position.isNotBlank()
            ) { Text("Save Change") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PositionChoices(
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        DEFENSIVE_POSITIONS.chunked(5).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row.forEach { position ->
                    FilterChip(
                        selected = selected == position,
                        onClick = { onSelected(position) },
                        label = { Text(position) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ScoreboardCard(state: GameState) {
    val awayRuns = state.totalRuns(Team.AWAY)
    val homeRuns = state.totalRuns(Team.HOME)
    val displayedInnings = maxOf(state.maxInnings, state.currentInning)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Scoreboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Team", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text("R", modifier = Modifier.width(34.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                Text("H", modifier = Modifier.width(34.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                Text("E", modifier = Modifier.width(34.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            }
            ScoreRow(
                name = state.awayTeamName,
                runs = awayRuns,
                hits = state.hits[Team.AWAY] ?: 0,
                errors = state.errors[Team.AWAY] ?: 0
            )
            ScoreRow(
                name = state.homeTeamName,
                runs = homeRuns,
                hits = state.hits[Team.HOME] ?: 0,
                errors = state.errors[Team.HOME] ?: 0
            )

            HorizontalDivider()

            val inningScroll = rememberScrollState()
            Column(
                modifier = Modifier.horizontalScroll(inningScroll),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    InningCell("", header = true)
                    for (inning in 1..displayedInnings) {
                        InningCell(inning.toString(), header = true)
                    }
                }
                InningScoreRow(state, Team.AWAY, displayedInnings)
                InningScoreRow(state, Team.HOME, displayedInnings)
            }
        }
    }
}

@Composable
private fun ScoreRow(name: String, runs: Int, hits: Int, errors: Int) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.weight(1f), maxLines = 1)
        Text(runs.toString(), modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
        Text(hits.toString(), modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
        Text(errors.toString(), modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun InningScoreRow(state: GameState, team: Team, displayedInnings: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        InningCell(if (team == Team.AWAY) "A" else "H", header = true)
        val innings = state.scores[team].orEmpty()
        val trackingStart = state.events.firstOrNull {
            it.type == GameEventType.GAME_STATE_EDIT && it.title == "Tracking started mid-game"
        }
        for (inning in 1..displayedInnings) {
            val index = inning - 1
            val skippedBottom = team == Team.HOME &&
                inning == state.currentInning &&
                state.gameOver &&
                !state.gameEndedManually &&
                state.topOfInning &&
                state.currentInning >= state.maxInnings &&
                state.totalRuns(Team.HOME) > state.totalRuns(Team.AWAY)
            val hasStarted = inning < state.currentInning ||
                inning == state.currentInning &&
                (team == Team.AWAY || !state.topOfInning)
            val distributionUnknown = state.trackingStartedMidGame &&
                (state.carryInRuns[team] ?: 0) > 0 &&
                trackingStart != null &&
                (inning < trackingStart.inning ||
                    (inning == trackingStart.inning && when {
                        trackingStart.topOfInning -> team == Team.AWAY
                        else -> true
                    }))
            val recorded = innings.getOrNull(index) ?: 0
            val value = when {
                skippedBottom -> "X"
                distributionUnknown -> if (recorded > 0) "?+$recorded" else "?"
                hasStarted -> recorded.toString()
                else -> "–"
            }
            InningCell(value)
        }
    }
}

@Composable
private fun InningCell(text: String, header: Boolean = false) {
    Box(
        modifier = Modifier
            .width(30.dp)
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun GameStatusCard(
    state: GameState,
    onBall: () -> Unit,
    onStrike: () -> Unit,
    onFoul: () -> Unit,
    onRemoveBall: () -> Unit,
    onRemoveStrike: () -> Unit,
    onRemoveRecordedPitch: (Long) -> Unit,
    onAddRecordedPitch: (Long, PitchAction) -> Unit,
    onRenameCurrentBatter: (String) -> Unit,
    onPinchHit: (String) -> Unit,
    onPinchRun: (Base, String) -> Unit,
    onPitchingChange: (String) -> Unit,
    onSetPitchCount: (Team, String, Int) -> Unit,
    onPositionChange: (Int, String) -> Unit,
    onSetCurrentBatter: (Team, Int?) -> Unit,
    onHitByPitch: () -> Unit,
    onAction: (PlayAction) -> Unit,
    onRunnerEvent: () -> Unit
) {
    val half = if (state.topOfInning) "Top" else "Bottom"
    val batter = LineupEngine.currentBatter(state)
    val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
    val currentPitcher = state.pitcherName(fieldingTeam)
    val currentPitchCount = remember(
        state.events,
        state.awayPitcherName,
        state.homePitcherName,
        fieldingTeam,
        currentPitcher
    ) {
        GameStats.pitching(state, fieldingTeam)
            .lastOrNull { it.pitcherName == currentPitcher }
            ?.pitches
            ?: 0
    }

    var showEditBatter by remember { mutableStateOf(false) }
    var showPinchHitter by remember { mutableStateOf(false) }
    var showPinchRunner by remember { mutableStateOf(false) }
    var showPitchingChange by remember { mutableStateOf(false) }
    var showPitchCountEditor by remember { mutableStateOf(false) }
    var showPositionChange by remember { mutableStateOf(false) }
    var showBatterPicker by remember { mutableStateOf(false) }
    var showPitchHistory by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "$half ${state.currentInning}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = state.teamName(state.activeTeam),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batter: ${batter?.name ?: "Unknown — select batter"}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium
                )
                TextButton(onClick = { showBatterPicker = true }) {
                    Text(if (batter == null) "Select" else "Change")
                }
                if (batter != null && batter.id >= 0) {
                    TextButton(onClick = { showEditBatter = true }) { Text("Edit name") }
                }
            }

            TextButton(
                onClick = { showPitchCountEditor = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
            ) {
                Text(
                    text = "Pitcher: $currentPitcher - $currentPitchCount ${if (currentPitchCount == 1) "pitch" else "pitches"}  •  Edit",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                CountAdjuster(
                    label = "Balls",
                    value = state.balls,
                    max = 3,
                    onAdd = onBall,
                    onRemove = onRemoveBall,
                    modifier = Modifier.weight(1f)
                )
                CountAdjuster(
                    label = "Strikes",
                    value = state.strikes,
                    max = 2,
                    onAdd = onStrike,
                    onRemove = onRemoveStrike,
                    modifier = Modifier.weight(1f)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OutlinedButton(
                        onClick = onFoul,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp)
                    ) {
                        Text("Foul", fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Add pitch",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusPill("Outs", state.outs, 2, Modifier.weight(1f))
            }

            if (batter != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlayMenuButton(
                        label = "Reached Base",
                        modifier = Modifier.weight(1f),
                        actions = listOf(
                            "Single" to { onAction(PlayAction.SINGLE) },
                            "Double" to { onAction(PlayAction.DOUBLE) },
                            "Triple" to { onAction(PlayAction.TRIPLE) },
                            "Home Run" to { onAction(PlayAction.HOME_RUN) },
                            "Walk" to { onAction(PlayAction.WALK) },
                            "Intentional Walk" to { onAction(PlayAction.INTENTIONAL_WALK) },
                            "HBP" to onHitByPitch,
                            "Error" to { onAction(PlayAction.ERROR) }
                        )
                    )
                    PlayMenuButton(
                        label = "Out / Other",
                        modifier = Modifier.weight(1f),
                        actions = listOf(
                            "Strikeout" to { onAction(PlayAction.STRIKEOUT) },
                            "Ground Out" to { onAction(PlayAction.GROUND_OUT) },
                            "Fly Out" to { onAction(PlayAction.FLY_OUT) },
                            "Fielder's Choice" to { onAction(PlayAction.FIELDERS_CHOICE) },
                            "Sac Bunt" to { onAction(PlayAction.SACRIFICE_BUNT) },
                            "Sac Fly" to { onAction(PlayAction.SACRIFICE_FLY) },
                            "Double Play" to { onAction(PlayAction.DOUBLE_PLAY) },
                            "Triple Play" to { onAction(PlayAction.TRIPLE_PLAY) },
                            "Runner Event" to onRunnerEvent
                        )
                    )
                }
            }

            TextButton(
                onClick = { showPitchHistory = true },
                enabled = completedPlateAppearancePitchHistory(state).isNotEmpty(),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Edit previous at-bat pitches")
            }

        }
    }

    if (showPitchCountEditor) {
        PitchCountEditorDialog(
            pitcherName = currentPitcher,
            currentCount = currentPitchCount,
            onDismiss = { showPitchCountEditor = false },
            onSave = { count ->
                onSetPitchCount(fieldingTeam, currentPitcher, count)
                showPitchCountEditor = false
            }
        )
    }

    if (showPitchHistory) {
        PreviousAtBatPitchDialog(
            state = state,
            onDismiss = { showPitchHistory = false },
            onRemovePitch = onRemoveRecordedPitch,
            onAddPitch = onAddRecordedPitch
        )
    }

    if (showBatterPicker) {
        CurrentBatterPickerDialog(
            state = state,
            team = state.activeTeam,
            allowUnknown = true,
            onDismiss = { showBatterPicker = false },
            onConfirm = { playerId ->
                showBatterPicker = false
                onSetCurrentBatter(state.activeTeam, playerId)
            }
        )
    }

    if (showEditBatter && batter != null) {
        NameEntryDialog(
            title = "Edit batter name",
            label = "Player name",
            initialValue = batter.name,
            confirmText = "Save",
            supportingText = "This corrects the name for this lineup spot without making a substitution.",
            onDismiss = { showEditBatter = false },
            onConfirm = { name ->
                showEditBatter = false
                onRenameCurrentBatter(name)
            }
        )
    }

    if (showPinchHitter && batter != null) {
        NameEntryDialog(
            title = "Pinch hitter",
            label = "New hitter name",
            confirmText = "Substitute",
            supportingText = "${batter.name} will be replaced in this batting-order spot for the rest of the game.",
            onDismiss = { showPinchHitter = false },
            onConfirm = { name ->
                showPinchHitter = false
                onPinchHit(name)
            }
        )
    }

    if (showPinchRunner) {
        PinchRunnerDialog(
            state = state,
            onDismiss = { showPinchRunner = false },
            onConfirm = { base, name ->
                showPinchRunner = false
                onPinchRun(base, name)
            }
        )
    }

    if (showPitchingChange) {
        NameEntryDialog(
            title = "Pitching change",
            label = "New pitcher name",
            initialValue = currentPitcher,
            confirmText = "Make Change",
            supportingText = "Changing pitcher for ${state.teamName(fieldingTeam)}.",
            onDismiss = { showPitchingChange = false },
            onConfirm = { name ->
                showPitchingChange = false
                onPitchingChange(name)
            }
        )
    }

    if (showPositionChange) {
        PositionChangeDialog(
            state = state,
            onDismiss = { showPositionChange = false },
            onConfirm = { playerId, position ->
                showPositionChange = false
                onPositionChange(playerId, position)
            }
        )
    }
}

@Composable
private fun UnsavedChangesDialog(
    title: String,
    message: String,
    onKeepEditing: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Discard Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepEditing) {
                Text("Keep Editing")
            }
        }
    )
}

@Composable
private fun NameEntryDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmText: String,
    supportingText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(title, initialValue) { mutableStateOf(initialValue) }
    var showDiscardChanges by remember(title, initialValue) { mutableStateOf(false) }
    val trimmed = name.trim()
    val hasUnsavedChanges = name != initialValue

    fun requestDismiss() {
        if (hasUnsavedChanges) showDiscardChanges = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = { requestDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                supportingText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty()
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = { requestDismiss() }) {
                Text("Cancel")
            }
        }
    )

    if (showDiscardChanges) {
        UnsavedChangesDialog(
            title = "Discard changes?",
            message = "You changed this value but have not saved it. Going back now will discard your edit.",
            onKeepEditing = { showDiscardChanges = false },
            onDiscard = {
                showDiscardChanges = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun PinchRunnerDialog(
    state: GameState,
    onDismiss: () -> Unit,
    onConfirm: (Base, String) -> Unit
) {
    val runners = buildList {
        state.bases.first?.let { add(Triple(Base.FIRST, "1B", it.playerId)) }
        state.bases.second?.let { add(Triple(Base.SECOND, "2B", it.playerId)) }
        state.bases.third?.let { add(Triple(Base.THIRD, "3B", it.playerId)) }
    }
    var selectedBase by remember(state.bases) { mutableStateOf(runners.firstOrNull()?.first) }
    var name by remember(state.bases) { mutableStateOf("") }
    val selectedRunner = runners.firstOrNull { it.first == selectedBase }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pinch runner") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Choose the runner being replaced. The new runner also takes that player's batting-order spot.",
                    style = MaterialTheme.typography.bodySmall
                )
                runners.forEach { (base, baseLabel, playerId) ->
                    FilterChip(
                        selected = selectedBase == base,
                        onClick = { selectedBase = base },
                        label = {
                            Text("$baseLabel — ${state.playerName(playerId) ?: "Runner"}")
                        }
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New runner name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                selectedRunner?.let { (_, baseLabel, playerId) ->
                    Text(
                        "Replacing ${state.playerName(playerId) ?: "runner"} on $baseLabel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    selectedBase?.let { onConfirm(it, trimmed) }
                },
                enabled = selectedBase != null && trimmed.isNotEmpty()
            ) {
                Text("Substitute")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private data class PlateAppearancePitchHistory(
    val endEventId: Long,
    val inning: Int,
    val topOfInning: Boolean,
    val batterName: String,
    val result: String,
    val pitches: List<GameEvent>
)

private fun completedPlateAppearancePitchHistory(state: GameState): List<PlateAppearancePitchHistory> {
    val result = mutableListOf<PlateAppearancePitchHistory>()
    val pendingPitches = mutableListOf<GameEvent>()

    state.events.forEach { event ->
        if (event.type == GameEventType.PITCH) pendingPitches += event
        val completesPlateAppearance = event.playAction != null &&
            (event.type == GameEventType.PLAY || event.type == GameEventType.PITCH)
        if (completesPlateAppearance) {
            result += PlateAppearancePitchHistory(
                endEventId = event.id,
                inning = event.inning,
                topOfInning = event.topOfInning,
                batterName = event.actorName ?: state.playerName(event.actorPlayerId ?: Int.MIN_VALUE) ?: "Unknown",
                result = event.title,
                pitches = pendingPitches.toList()
            )
            pendingPitches.clear()
        }
    }
    return result
}

@Composable
private fun CountAdjuster(
    label: String,
    value: Int,
    max: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value.coerceAtMost(max).toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
        }
        TextButton(
            onClick = onRemove,
            enabled = value > 0,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text("− 1 $label", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PitchCountEditorDialog(
    pitcherName: String,
    currentCount: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit
) {
    var value by remember(pitcherName, currentCount) { mutableStateOf(currentCount.toString()) }
    var showDiscardChanges by remember(pitcherName, currentCount) { mutableStateOf(false) }
    val parsed = value.toIntOrNull()?.coerceIn(0, 999)
    val hasUnsavedChanges = value != currentCount.toString()

    fun requestDismiss() {
        if (hasUnsavedChanges) showDiscardChanges = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = { requestDismiss() },
        title = { Text("Edit pitch count") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "$pitcherName currently has $currentCount ${if (currentCount == 1) "pitch" else "pitches"}. Enter the correct total.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { input -> value = input.filter(Char::isDigit).take(3) },
                    label = { Text("Total pitches") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "This adjusts the pitch total without inventing a ball or strike. New pitches will continue counting from the corrected total.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { parsed?.let(onSave) },
                enabled = parsed != null
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { requestDismiss() }) { Text("Cancel") } }
    )

    if (showDiscardChanges) {
        UnsavedChangesDialog(
            title = "Discard pitch-count change?",
            message = "The corrected pitch count has not been saved. Going back now will discard it.",
            onKeepEditing = { showDiscardChanges = false },
            onDiscard = {
                showDiscardChanges = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun PreviousAtBatPitchDialog(
    state: GameState,
    onDismiss: () -> Unit,
    onRemovePitch: (Long) -> Unit,
    onAddPitch: (Long, PitchAction) -> Unit
) {
    val history = completedPlateAppearancePitchHistory(state)
    var selectedIndex by remember(history.size) { mutableStateOf((history.lastIndex).coerceAtLeast(0)) }
    var expanded by remember { mutableStateOf(false) }
    val selected = history.getOrNull(selectedIndex)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit previous at-bat pitches") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Choose an earlier plate appearance, then add or remove recorded pitches. The at-bat result stays unchanged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (history.isEmpty()) {
                    Text("No completed at-bats have been recorded yet.")
                } else if (selected != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${if (selected.topOfInning) "Top" else "Bottom"} ${selected.inning} • ${selected.batterName} • ${selected.result}",
                                textAlign = TextAlign.Center
                            )
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            history.asReversed().forEachIndexed { reversedIndex, atBat ->
                                val actualIndex = history.lastIndex - reversedIndex
                                DropdownMenuItem(
                                    text = {
                                        Text("${if (atBat.topOfInning) "Top" else "Bottom"} ${atBat.inning} • ${atBat.batterName} • ${atBat.result}")
                                    },
                                    onClick = {
                                        selectedIndex = actualIndex
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    Text("Recorded pitches", fontWeight = FontWeight.SemiBold)
                    if (selected.pitches.isEmpty()) {
                        Text(
                            "No individual pitches are recorded for this at-bat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        selected.pitches.forEachIndexed { index, pitch ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${index + 1}. ${pitch.title}",
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onRemovePitch(pitch.id) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }

                    Text("Add missed pitch", fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onAddPitch(selected.endEventId, PitchAction.BALL) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("Ball") }
                        OutlinedButton(
                            onClick = { onAddPitch(selected.endEventId, PitchAction.STRIKE) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("Strike") }
                        OutlinedButton(
                            onClick = { onAddPitch(selected.endEventId, PitchAction.FOUL) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) { Text("Foul") }
                    }
                    OutlinedButton(
                        onClick = { onAddPitch(selected.endEventId, PitchAction.IN_PLAY) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Ball in play") }

                    Text(
                        "Removing a result pitch such as Ball 4 or Strike 3 corrects the pitch count but keeps the walk or strikeout in the scorebook.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun GameManagementCard(
    state: GameState,
    onPinchHit: (String) -> Unit,
    onPinchRun: (Base, String) -> Unit,
    onPitchingChange: (String) -> Unit,
    onPositionChange: (Int, String) -> Unit
) {
    val batter = LineupEngine.currentBatter(state)
    val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
    val currentPitcher = state.pitcherName(fieldingTeam)
    var showPinchHitter by remember { mutableStateOf(false) }
    var showPinchRunner by remember { mutableStateOf(false) }
    var showPitchingChange by remember { mutableStateOf(false) }
    var showPositionChange by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Game Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showPinchHitter = true },
                    enabled = batter != null && batter.id >= 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Pinch Hitter", textAlign = TextAlign.Center) }
                OutlinedButton(
                    onClick = { showPinchRunner = true },
                    enabled = !state.bases.isEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Pinch Runner", textAlign = TextAlign.Center) }
            }
            OutlinedButton(onClick = { showPitchingChange = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Pitching Change")
            }
            OutlinedButton(onClick = { showPositionChange = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Position Change")
            }
        }
    }

    if (showPinchHitter && batter != null) {
        NameEntryDialog(
            title = "Pinch hitter",
            label = "New hitter name",
            confirmText = "Substitute",
            supportingText = "${batter.name} will be replaced in this batting-order spot for the rest of the game.",
            onDismiss = { showPinchHitter = false },
            onConfirm = { name -> showPinchHitter = false; onPinchHit(name) }
        )
    }
    if (showPinchRunner) {
        PinchRunnerDialog(
            state = state,
            onDismiss = { showPinchRunner = false },
            onConfirm = { base, name -> showPinchRunner = false; onPinchRun(base, name) }
        )
    }
    if (showPitchingChange) {
        NameEntryDialog(
            title = "Pitching change",
            label = "New pitcher name",
            initialValue = currentPitcher,
            confirmText = "Make Change",
            supportingText = "Changing pitcher for ${state.teamName(fieldingTeam)}.",
            onDismiss = { showPitchingChange = false },
            onConfirm = { name -> showPitchingChange = false; onPitchingChange(name) }
        )
    }
    if (showPositionChange) {
        PositionChangeDialog(
            state = state,
            onDismiss = { showPositionChange = false },
            onConfirm = { playerId, position -> showPositionChange = false; onPositionChange(playerId, position) }
        )
    }
}

@Composable
private fun StatusPill(label: String, value: Int, max: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value.coerceAtMost(max).toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DiamondCard(state: GameState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Inning ${state.currentInning}",
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(242.dp)
            ) {
                BaseSpot(
                    baseLabel = "2B",
                    runnerName = runnerName(state, state.bases.second?.playerId),
                    occupied = state.bases.second != null,
                    modifier = Modifier.offset(x = 100.dp, y = 8.dp),
                    width = 100.dp
                )
                BaseSpot(
                    baseLabel = "3B",
                    runnerName = runnerName(state, state.bases.third?.playerId),
                    occupied = state.bases.third != null,
                    modifier = Modifier.offset(x = 8.dp, y = 92.dp),
                    width = 110.dp
                )
                BaseSpot(
                    baseLabel = "1B",
                    runnerName = runnerName(state, state.bases.first?.playerId),
                    occupied = state.bases.first != null,
                    modifier = Modifier.offset(x = 182.dp, y = 92.dp),
                    width = 110.dp
                )
                BaseSpot(
                    baseLabel = "HOME",
                    runnerName = null,
                    occupied = false,
                    modifier = Modifier.offset(x = 100.dp, y = 168.dp),
                    width = 100.dp,
                    home = true
                )
                Text(
                    text = "Balls ${state.balls}  |  Strikes ${state.strikes}  |  Outs ${state.outs}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun BaseSpot(
    baseLabel: String,
    runnerName: String?,
    occupied: Boolean,
    modifier: Modifier,
    width: androidx.compose.ui.unit.Dp,
    home: Boolean = false
) {
    Column(
        modifier = modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val background = when {
            occupied -> MaterialTheme.colorScheme.primary
            home -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .rotate(45f)
                .background(background, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = baseLabel,
                modifier = Modifier.rotate(-45f),
                color = if (occupied) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
        if (!home) {
            Text(
                text = if (occupied) runnerName.orEmpty().ifBlank { "Unknown" } else "Empty",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (occupied) FontWeight.SemiBold else FontWeight.Normal,
                color = if (occupied) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

private fun runnerName(state: GameState, playerId: Int?): String? {
    if (playerId == null) return null
    return state.playerName(playerId) ?: "Unknown"
}

@Composable
private fun PlayMenuButton(
    label: String,
    modifier: Modifier = Modifier,
    actions: List<Pair<String, () -> Unit>>
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Button(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("$label ▾", textAlign = TextAlign.Center)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            actions.forEach { (itemLabel, action) ->
                DropdownMenuItem(
                    text = { Text(itemLabel) },
                    onClick = {
                        expanded = false
                        action()
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(actions: List<Pair<String, () -> Unit>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        actions.forEach { (label, action) ->
            Button(
                onClick = action,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)
            ) {
                Text(label, textAlign = TextAlign.Center, fontSize = 12.sp)
            }
        }
    }
}

private fun shouldResolveRunners(state: GameState, action: PlayAction): Boolean {
    return when (action) {
        PlayAction.SINGLE,
        PlayAction.DOUBLE,
        PlayAction.TRIPLE -> !state.bases.isEmpty()

        PlayAction.ERROR,
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.FIELDERS_CHOICE,
        PlayAction.SACRIFICE,
        PlayAction.SACRIFICE_BUNT,
        PlayAction.SACRIFICE_FLY,
        PlayAction.DOUBLE_PLAY,
        PlayAction.TRIPLE_PLAY -> true

        else -> false
    }
}

@Composable
private fun RunnerResolutionDialog(
    state: GameState,
    action: PlayAction,
    onDismiss: () -> Unit,
    onCommit: (Map<Int, Base?>, Int, FieldingPlay?) -> Unit
) {
    val batterId = LineupEngine.currentBatter(state)?.id ?: -1
    val preview = remember(state, action) {
        RunnerEngine.defaultDestinations(
            bases = state.bases,
            play = Play(action = action, batterId = batterId)
        )
    }
    var destinations by remember(state, action) { mutableStateOf(preview) }
    var outsRecorded by remember(state, action) { mutableIntStateOf(action.defaultOuts) }
    var putoutName by remember(state, action) { mutableStateOf<String?>(null) }
    var errorName by remember(state, action) { mutableStateOf<String?>(null) }
    var assistNames by remember(state, action) { mutableStateOf<Set<String>>(emptySet()) }
    var fieldingSequence by remember(state, action) { mutableStateOf<List<DefensiveFielder>>(emptyList()) }
    val fieldingNames = remember(state) { defensivePlayerNames(state) }
    val fieldingPositions = remember(state) { defensivePlayerPositions(state) }
    val defensiveFielderOptions = remember(state) { defensiveFielders(state) }
    val usesScorebookSequence = action in setOf(
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.SACRIFICE_FLY,
        PlayAction.DOUBLE_PLAY,
        PlayAction.TRIPLE_PLAY
    )

    val duplicateBase = listOf(Base.FIRST, Base.SECOND, Base.THIRD).any { base ->
        destinations.values.count { it == base } > 1
    }
    val needsOutCredit = action in setOf(
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.FIELDERS_CHOICE,
        PlayAction.SACRIFICE,
        PlayAction.SACRIFICE_BUNT,
        PlayAction.SACRIFICE_FLY,
        PlayAction.DOUBLE_PLAY,
        PlayAction.TRIPLE_PLAY
    )
    val sequenceRequired = usesScorebookSequence && defensiveFielderOptions.isNotEmpty()
    val fieldingSequenceReady = !sequenceRequired || when (action) {
        PlayAction.FLY_OUT, PlayAction.SACRIFICE_FLY -> fieldingSequence.size == 1
        PlayAction.DOUBLE_PLAY -> fieldingSequence.size >= 2
        PlayAction.TRIPLE_PLAY -> fieldingSequence.size >= 3
        else -> fieldingSequence.isNotEmpty()
    }
    var showResolutionWarning by remember(state, action) { mutableStateOf(false) }
    val resolutionWarnings = smartResolutionWarnings(
        state = state,
        action = action,
        batterId = batterId,
        destinations = destinations,
        outsRecorded = outsRecorded
    )

    fun commitResolvedPlay() {
        val sequencePutout = fieldingSequence.lastOrNull()?.name
        val sequencePutouts = if (fieldingSequence.isNotEmpty()) {
            val putoutCount = when (action) {
                PlayAction.DOUBLE_PLAY -> 2
                PlayAction.TRIPLE_PLAY -> 3
                else -> 1
            }
            fieldingSequence.takeLast(putoutCount.coerceAtMost(fieldingSequence.size))
                .map { it.name }
                .distinct()
        } else emptyList()
        val sequenceAssists = if (fieldingSequence.size > 1) {
            fieldingSequence.dropLast(1).map { it.name }.distinct()
        } else emptyList()
        val notation = if (fieldingSequence.isNotEmpty()) {
            scorebookFieldingNotation(action, fieldingSequence.map { it.number })
        } else null
        val fielding = FieldingPlay(
            putoutPlayerName = sequencePutout ?: putoutName,
            putoutPlayerNames = if (fieldingSequence.isNotEmpty()) sequencePutouts else listOfNotNull(putoutName),
            assistPlayerNames = if (fieldingSequence.isNotEmpty()) sequenceAssists else assistNames.toList(),
            errorPlayerName = errorName,
            fieldingNotation = notation,
            doublePlay = action == PlayAction.DOUBLE_PLAY,
            triplePlay = action == PlayAction.TRIPLE_PLAY
        ).takeIf {
            it.putoutPlayerName != null || it.putoutPlayerNames.isNotEmpty() || it.assistPlayerNames.isNotEmpty() ||
                it.errorPlayerName != null || it.fieldingNotation != null || it.doublePlay || it.triplePlay
        }
        onCommit(destinations, outsRecorded, fielding)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(playLabel(action)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Set where each runner finished. Choose OUT to remove a runner or HOME to score the run.",
                    style = MaterialTheme.typography.bodySmall
                )

                destinations.forEach { (playerId, destination) ->
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            state.playerName(playerId) ?: if (playerId == batterId) "Batter" else "Runner $playerId",
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            validDestinationChoices(state, playerId, batterId).forEach { (label, base) ->
                                FilterChip(
                                    selected = destination == base,
                                    onClick = {
                                        destinations = destinations.toMutableMap().apply { this[playerId] = base }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                Text("Outs recorded", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    (0..3).forEach { outs ->
                        FilterChip(
                            selected = outsRecorded == outs,
                            onClick = { outsRecorded = outs },
                            label = { Text(outs.toString()) }
                        )
                    }
                }

                if (action == PlayAction.ERROR) {
                    Text("Fielding error (optional)", fontWeight = FontWeight.SemiBold)
                    SimpleNameDropdown(
                        label = "Error charged to",
                        names = fieldingNames,
                        selected = errorName,
                        allowNone = true,
                        onSelected = { errorName = it }
                    )
                } else if (needsOutCredit) {
                    if (usesScorebookSequence && defensiveFielderOptions.isNotEmpty()) {
                        val sequenceNumbers = fieldingSequence.map { it.number }
                        val notation = scorebookFieldingNotation(action, sequenceNumbers)
                        Text(
                            when (action) {
                                PlayAction.FLY_OUT, PlayAction.SACRIFICE_FLY -> "Who caught it?"
                                PlayAction.DOUBLE_PLAY -> "Double-play fielding sequence"
                                PlayAction.TRIPLE_PLAY -> "Triple-play fielding sequence"
                                else -> "Ground-out fielding sequence"
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when (action) {
                                PlayAction.FLY_OUT, PlayAction.SACRIFICE_FLY ->
                                    "Tap the fielder who made the catch. The scorecard will show F8, F7, etc."
                                PlayAction.DOUBLE_PLAY ->
                                    "Tap fielders in order from the first touch through the second out. Example: 3B, 2B, 1B becomes 5-4-3 DP."
                                PlayAction.TRIPLE_PLAY ->
                                    "Tap fielders in order through all three outs."
                                else ->
                                    "Tap fielders in order. Example: SS then 1B becomes 6-3."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        ScorebookFieldingDiamond(
                            fielders = defensiveFielderOptions,
                            sequence = fieldingSequence,
                            onFielderTap = { fielder ->
                                fieldingSequence = if (action in setOf(PlayAction.FLY_OUT, PlayAction.SACRIFICE_FLY)) {
                                    listOf(fielder)
                                } else {
                                    fieldingSequence + fielder
                                }
                            }
                        )

                        if (fieldingSequence.isNotEmpty()) {
                            Text(
                                "Scorecard: $notation",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (action == PlayAction.DOUBLE_PLAY && fieldingSequence.size >= 2) {
                                val firstOut = if (fieldingSequence.size == 2) {
                                    "${fieldingSequence[0].number} unassisted"
                                } else {
                                    "${fieldingSequence[0].number}-${fieldingSequence[1].number}"
                                }
                                val secondOut = if (fieldingSequence.size == 2) {
                                    "${fieldingSequence[0].number}-${fieldingSequence[1].number}"
                                } else {
                                    "${fieldingSequence[1].number}-${fieldingSequence[2].number}"
                                }
                                Text(
                                    "First out: $firstOut  •  Second out: $secondOut",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (action !in setOf(PlayAction.FLY_OUT, PlayAction.SACRIFICE_FLY)) {
                                    TextButton(onClick = { fieldingSequence = fieldingSequence.dropLast(1) }) {
                                        Text("Undo last fielder")
                                    }
                                }
                                TextButton(onClick = { fieldingSequence = emptyList() }) { Text("Clear") }
                            }
                        }
                    } else {
                        Text("Fielding credit (optional)", fontWeight = FontWeight.SemiBold)
                        SimpleNameDropdown(
                            label = "Putout",
                            names = fieldingNames,
                            selected = putoutName,
                            allowNone = true,
                            onSelected = { putoutName = it },
                            displayName = { name ->
                                fieldingPositions[name]
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { position -> "$position — $name" }
                                    ?: name
                            }
                        )
                        if (fieldingNames.isNotEmpty()) {
                            Text("Assists", style = MaterialTheme.typography.labelLarge)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                fieldingNames.forEach { name ->
                                    FilterChip(
                                        selected = name in assistNames,
                                        onClick = {
                                            assistNames = if (name in assistNames) assistNames - name else assistNames + name
                                        },
                                        label = {
                                            val position = fieldingPositions[name]
                                            Text(if (position.isNullOrBlank()) name else "$position — $name")
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (sequenceRequired && !fieldingSequenceReady) {
                    Text(
                        when (action) {
                            PlayAction.FLY_OUT, PlayAction.SACRIFICE_FLY -> "Choose the fielder who made the catch."
                            PlayAction.DOUBLE_PLAY -> "Enter the fielding sequence for both outs."
                            PlayAction.TRIPLE_PLAY -> "Enter the fielding sequence for all three outs."
                            else -> "Enter the fielder or fielding sequence for the out."
                        },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (duplicateBase) {
                    Text(
                        "Two runners cannot finish on the same base.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (resolutionWarnings.isNotEmpty()) {
                        showResolutionWarning = true
                    } else {
                        commitResolvedPlay()
                    }
                },
                enabled = !duplicateBase && fieldingSequenceReady
            ) {
                Text("Record Play")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showResolutionWarning && resolutionWarnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showResolutionWarning = false },
            title = { Text("Check this play") },
            text = { Text(resolutionWarnings.joinToString("\n\n") { "• $it" }) },
            confirmButton = {
                Button(onClick = {
                    showResolutionWarning = false
                    commitResolvedPlay()
                }) { Text("Record Anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showResolutionWarning = false }) { Text("Go Back") }
            }
        )
    }
}

private fun defensivePlayerNames(state: GameState): List<String> {
    val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
    val lineup = if (fieldingTeam == Team.AWAY) state.lineupAway else state.lineupHome
    return (lineup.map(Player::name) + state.pitcherName(fieldingTeam))
        .filter(String::isNotBlank)
        .distinct()
}

private fun defensivePlayerPositions(state: GameState): Map<String, String> {
    val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
    val lineup = if (fieldingTeam == Team.AWAY) state.lineupAway else state.lineupHome
    return buildMap {
        lineup.forEach { player ->
            put(player.name, state.playerPosition(player.id).orEmpty())
        }
        state.pitcherName(fieldingTeam)
            .takeIf(String::isNotBlank)
            ?.let { put(it, "P") }
    }
}

private data class DefensiveFielder(
    val number: Int,
    val position: String,
    val name: String
)

private fun defensiveFielders(state: GameState): List<DefensiveFielder> {
    val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
    val lineup = if (fieldingTeam == Team.AWAY) state.lineupAway else state.lineupHome
    val fielders = lineup.mapNotNull { player ->
        val position = state.playerPosition(player.id).orEmpty().uppercase()
        val number = scorebookPositionNumber(position) ?: return@mapNotNull null
        DefensiveFielder(number, position, player.name)
    }.toMutableList()

    val pitcherName = state.pitcherName(fieldingTeam).trim()
    if (pitcherName.isNotEmpty() && fielders.none { it.number == 1 && it.name == pitcherName }) {
        fielders += DefensiveFielder(1, "P", pitcherName)
    }
    return fielders.distinctBy { it.number to it.name }.sortedBy(DefensiveFielder::number)
}

private fun scorebookPositionNumber(position: String): Int? = when (position.uppercase()) {
    "P" -> 1
    "C" -> 2
    "1B" -> 3
    "2B" -> 4
    "3B" -> 5
    "SS" -> 6
    "LF" -> 7
    "CF" -> 8
    "RF" -> 9
    else -> null
}

@Composable
private fun ScorebookFieldingDiamond(
    fielders: List<DefensiveFielder>,
    sequence: List<DefensiveFielder>,
    onFielderTap: (DefensiveFielder) -> Unit
) {
    val byPosition = remember(fielders) { fielders.associateBy { it.position.uppercase() } }

    @Composable
    fun FielderButton(position: String, modifier: Modifier = Modifier) {
        val fielder = byPosition[position]
        if (fielder == null) {
            Spacer(modifier = modifier.height(56.dp))
            return
        }
        val selectionNumber = sequence.indexOfLast { it.number == fielder.number && it.name == fielder.name }
            .takeIf { it >= 0 }
            ?.plus(1)
        OutlinedButton(
            onClick = { onFielderTap(fielder) },
            modifier = modifier.heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = "${fielder.number} · ${fielder.position}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = fielder.name,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
                if (selectionNumber != null) {
                    Text(
                        text = "#$selectionNumber",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Spacer(Modifier.weight(1f))
            FielderButton("C", Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FielderButton("1B", Modifier.weight(1f))
            FielderButton("P", Modifier.weight(1f))
            FielderButton("3B", Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Spacer(Modifier.weight(1f))
            FielderButton("2B", Modifier.weight(1f))
            FielderButton("SS", Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FielderButton("LF", Modifier.weight(1f))
            FielderButton("CF", Modifier.weight(1f))
            FielderButton("RF", Modifier.weight(1f))
        }
    }
}

private fun supportsScorebookFieldingNotation(action: PlayAction): Boolean = action in setOf(
    PlayAction.GROUND_OUT,
    PlayAction.FLY_OUT,
    PlayAction.SACRIFICE_FLY,
    PlayAction.DOUBLE_PLAY,
    PlayAction.TRIPLE_PLAY
)

private fun scorebookFieldingNotation(action: PlayAction, numbers: List<Int>): String {
    if (numbers.isEmpty()) return ""
    val sequence = numbers.joinToString("-")
    return when (action) {
        PlayAction.FLY_OUT -> "F$sequence"
        PlayAction.GROUND_OUT -> sequence
        PlayAction.SACRIFICE_FLY -> "SF$sequence"
        PlayAction.DOUBLE_PLAY -> "$sequence DP"
        PlayAction.TRIPLE_PLAY -> "$sequence TP"
        else -> sequence
    }
}

@Composable
private fun SimpleNameDropdown(
    label: String,
    names: List<String>,
    selected: String?,
    allowNone: Boolean,
    onSelected: (String?) -> Unit,
    displayName: (String) -> String = { it }
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selected?.let(displayName) ?: if (allowNone) "None" else "Choose"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text("None") }, onClick = {
                    expanded = false
                    onSelected(null)
                })
            }
            names.forEach { name ->
                DropdownMenuItem(text = { Text(displayName(name)) }, onClick = {
                    expanded = false
                    onSelected(name)
                })
            }
        }
    }
}


@Composable
private fun BaseRunningEventDialog(
    state: GameState,
    onDismiss: () -> Unit,
    onCommit: (BaseRunningAction, Int?, Base?, String?) -> Unit
) {
    val runners = listOfNotNull(state.bases.first, state.bases.second, state.bases.third)
    var action by remember(state.bases) {
        mutableStateOf(if (runners.isEmpty()) BaseRunningAction.WILD_PITCH else BaseRunningAction.STOLEN_BASE)
    }
    var runnerId by remember(state.bases) { mutableStateOf(runners.firstOrNull()?.playerId) }
    var destination by remember(action, runnerId) {
        val runner = runners.firstOrNull { it.playerId == runnerId }
        mutableStateOf(runner?.base?.let { defaultRunnerDestination(it, action) })
    }
    var fielder by remember(action) { mutableStateOf<String?>(null) }
    var showEventWarning by remember { mutableStateOf(false) }
    val fieldingNames = remember(state) { defensivePlayerNames(state) }
    val fieldingPositions = remember(state) { defensivePlayerPositions(state) }
    val runnerRequired = baseRunningActionRequiresRunner(action)
    val removesRunner = action == BaseRunningAction.CAUGHT_STEALING || action == BaseRunningAction.PICKOFF || action == BaseRunningAction.RUNNER_OUT
    val selectedRunner = runners.firstOrNull { it.playerId == runnerId }
    val destinationOptions = selectedRunner?.let { validRunnerEventDestinations(it.base, action) }.orEmpty()
    val eventWarnings = buildList {
        if (removesRunner && fielder == null) {
            add("No fielder is selected for the putout, so the out will be recorded without fielding credit.")
        }
        if (!removesRunner && runnerId != null && destination == null) {
            add("A runner is selected, but no destination base is selected.")
        }
    }

    fun commitEvent() {
        onCommit(action, runnerId, destination, fielder)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Runner / Pitch Event") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Record events that happen outside a normal plate appearance.", style = MaterialTheme.typography.bodySmall)
                if (runners.isEmpty()) {
                    Text(
                        "No runners are on base. Runner-only events are disabled; wild pitch and passed ball can still be recorded as event-only pitches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BaseRunningAction.values().forEach { option ->
                    val enabled = !(baseRunningActionRequiresRunner(option) && runners.isEmpty())
                    FilterChip(
                        selected = action == option,
                        enabled = enabled,
                        onClick = {
                            action = option
                            val selected = runners.firstOrNull { it.playerId == runnerId }
                            if (baseRunningActionRequiresRunner(option) && selected == null) {
                                runnerId = runners.firstOrNull()?.playerId
                            }
                            val currentRunner = runners.firstOrNull { it.playerId == runnerId }
                            destination = if (option in setOf(BaseRunningAction.CAUGHT_STEALING, BaseRunningAction.PICKOFF, BaseRunningAction.RUNNER_OUT)) {
                                null
                            } else {
                                currentRunner?.base?.let { defaultRunnerDestination(it, option) }
                            }
                            fielder = null
                        },
                        label = { Text(baseRunningUiLabel(option)) }
                    )
                }

                Text("Runner", fontWeight = FontWeight.SemiBold)
                if (!runnerRequired) {
                    FilterChip(
                        selected = runnerId == null,
                        onClick = { runnerId = null; destination = null },
                        label = { Text("No runner / event only") }
                    )
                }
                runners.forEach { runner ->
                    FilterChip(
                        selected = runnerId == runner.playerId,
                        onClick = {
                            runnerId = runner.playerId
                            destination = if (removesRunner) null else defaultRunnerDestination(runner.base, action)
                        },
                        label = { Text("${baseLabelUi(runner.base)} — ${state.playerName(runner.playerId) ?: "Unknown"}") }
                    )
                }

                if (!removesRunner && runnerId != null) {
                    Text("Destination", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        destinationOptions.forEach { base ->
                            FilterChip(
                                selected = destination == base,
                                onClick = { destination = base },
                                label = { Text(baseLabelUi(base)) }
                            )
                        }
                    }
                    if (destinationOptions.isEmpty()) {
                        Text(
                            "There is no valid forward destination for this runner/event.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (removesRunner) {
                    SimpleNameDropdown(
                        label = "Putout by",
                        names = fieldingNames,
                        selected = fielder,
                        allowNone = true,
                        onSelected = { fielder = it },
                        displayName = { name ->
                            fieldingPositions[name]
                                ?.takeIf(String::isNotBlank)
                                ?.let { position -> "$position — $name" }
                                ?: name
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (eventWarnings.isNotEmpty()) showEventWarning = true else commitEvent()
                },
                enabled = (!runnerRequired || runnerId != null) && (removesRunner || runnerId == null || destination != null)
            ) { Text("Record Event") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showEventWarning && eventWarnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showEventWarning = false },
            title = { Text("Check this event") },
            text = { Text(eventWarnings.joinToString("\n\n") { "• $it" }) },
            confirmButton = {
                Button(onClick = {
                    showEventWarning = false
                    commitEvent()
                }) { Text("Record Anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showEventWarning = false }) { Text("Go Back") }
            }
        )
    }
}

private fun nextBaseForUi(base: Base): Base = when (base) {
    Base.FIRST -> Base.SECOND
    Base.SECOND -> Base.THIRD
    Base.THIRD -> Base.HOME
    Base.HOME -> Base.HOME
}

private fun baseLabelUi(base: Base): String = when (base) {
    Base.FIRST -> "1B"
    Base.SECOND -> "2B"
    Base.THIRD -> "3B"
    Base.HOME -> "HOME"
}

private fun baseRunningUiLabel(action: BaseRunningAction): String = when (action) {
    BaseRunningAction.STOLEN_BASE -> "Stolen Base"
    BaseRunningAction.CAUGHT_STEALING -> "Caught Stealing"
    BaseRunningAction.PICKOFF -> "Pickoff"
    BaseRunningAction.RUNNER_OUT -> "Runner Out"
    BaseRunningAction.WILD_PITCH -> "Wild Pitch"
    BaseRunningAction.PASSED_BALL -> "Passed Ball"
    BaseRunningAction.BALK -> "Balk"
    BaseRunningAction.DEFENSIVE_INDIFFERENCE -> "Defensive Indifference"
    BaseRunningAction.RUNNER_ADVANCE -> "Other Runner Advance"
}

private fun baseRunningActionRequiresRunner(action: BaseRunningAction): Boolean = action in setOf(
    BaseRunningAction.STOLEN_BASE,
    BaseRunningAction.CAUGHT_STEALING,
    BaseRunningAction.PICKOFF,
    BaseRunningAction.RUNNER_OUT,
    BaseRunningAction.BALK,
    BaseRunningAction.DEFENSIVE_INDIFFERENCE,
    BaseRunningAction.RUNNER_ADVANCE
)

private fun baseRank(base: Base): Int = when (base) {
    Base.FIRST -> 1
    Base.SECOND -> 2
    Base.THIRD -> 3
    Base.HOME -> 4
}

private fun validRunnerEventDestinations(origin: Base, action: BaseRunningAction): List<Base> {
    val forward = listOf(Base.SECOND, Base.THIRD, Base.HOME).filter { baseRank(it) > baseRank(origin) }
    return when (action) {
        BaseRunningAction.STOLEN_BASE,
        BaseRunningAction.DEFENSIVE_INDIFFERENCE -> forward.take(1)
        BaseRunningAction.CAUGHT_STEALING,
        BaseRunningAction.PICKOFF,
        BaseRunningAction.RUNNER_OUT -> emptyList()
        else -> forward
    }
}

private fun defaultRunnerDestination(origin: Base, action: BaseRunningAction): Base? =
    validRunnerEventDestinations(origin, action).firstOrNull()

private fun runnerBase(state: GameState, playerId: Int): Base? = when (playerId) {
    state.bases.first?.playerId -> Base.FIRST
    state.bases.second?.playerId -> Base.SECOND
    state.bases.third?.playerId -> Base.THIRD
    else -> null
}

private fun validDestinationChoices(
    state: GameState,
    playerId: Int,
    batterId: Int
): List<Pair<String, Base?>> {
    if (playerId == batterId) return destinationChoices
    val origin = runnerBase(state, playerId) ?: return destinationChoices
    return destinationChoices.filter { (_, destination) ->
        destination == null || baseRank(destination) >= baseRank(origin)
    }
}

private val destinationChoices = listOf(
    "OUT" to null,
    "1B" to Base.FIRST,
    "2B" to Base.SECOND,
    "3B" to Base.THIRD,
    "HOME" to Base.HOME
)

private fun runnerCount(state: GameState): Int = listOfNotNull(
    state.bases.first,
    state.bases.second,
    state.bases.third
).size

private fun smartWarningForPlay(state: GameState, action: PlayAction): SmartActionWarning? {
    val runners = runnerCount(state)
    val reasons = mutableListOf<String>()

    when (action) {
        PlayAction.WALK -> {
            if (state.balls < 3) {
                reasons += "The current count is ${state.balls}-${state.strikes}. A normal walk needs a fourth ball."
            }
        }
        PlayAction.STRIKEOUT -> {
            if (state.strikes < 2) {
                reasons += "The current count is ${state.balls}-${state.strikes}. A normal strikeout needs a third strike."
            }
        }
        PlayAction.FIELDERS_CHOICE -> {
            if (runners == 0) reasons += "There are no runners on base for the defense to choose instead of the batter."
        }
        PlayAction.SACRIFICE, PlayAction.SACRIFICE_BUNT -> {
            if (runners == 0) reasons += "There are no runners on base to advance with a sacrifice."
            if (state.outs >= 2) reasons += "There are already 2 outs. A sacrifice bunt is not normally credited with two outs."
        }
        PlayAction.SACRIFICE_FLY -> {
            if (runners == 0) reasons += "There are no runners on base to score on a sacrifice fly."
            if (state.outs >= 2) reasons += "There are already 2 outs. A sacrifice fly is not credited when the catch makes the third out."
        }
        PlayAction.DOUBLE_PLAY -> {
            if (runners == 0) reasons += "There are no runners on base. A normal double play needs the batter plus another runner."
            if (state.outs >= 2) reasons += "There are already 2 outs, so only one out remains in the inning."
        }
        PlayAction.TRIPLE_PLAY -> {
            if (runners < 2) reasons += "A normal triple play needs at least two runners on base in addition to the batter."
            if (state.outs > 0) reasons += "There are already ${state.outs} out${if (state.outs == 1) "" else "s"}, so fewer than three outs remain in the inning."
        }
        else -> Unit
    }

    if (reasons.isEmpty()) return null
    return SmartActionWarning(
        title = "${plainPlayLabel(action)} — are you sure?",
        message = reasons.joinToString("\n\n") { "• $it" },
        confirmText = "Record ${plainPlayLabel(action)}"
    )
}

private fun smartResolutionWarnings(
    state: GameState,
    action: PlayAction,
    batterId: Int,
    destinations: Map<Int, Base?>,
    outsRecorded: Int
): List<String> = buildList {
    val batterDestination = destinations[batterId]
    val batterShouldBeOut = action in setOf(
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.SACRIFICE,
        PlayAction.SACRIFICE_BUNT,
        PlayAction.SACRIFICE_FLY
    )
    if (batterShouldBeOut && batterDestination != null) {
        add("This result says the batter is out, but the batter is currently set to finish at ${baseLabelUi(batterDestination)}.")
    }

    val expectedOuts = when (action) {
        PlayAction.GROUND_OUT, PlayAction.FLY_OUT, PlayAction.SACRIFICE, PlayAction.SACRIFICE_BUNT, PlayAction.SACRIFICE_FLY -> 1
        PlayAction.DOUBLE_PLAY -> 2
        PlayAction.TRIPLE_PLAY -> 3
        else -> null
    }
    if (expectedOuts != null && outsRecorded != expectedOuts) {
        add("${plainPlayLabel(action)} normally records $expectedOuts out${if (expectedOuts == 1) "" else "s"}, but this play is set to record $outsRecorded.")
    }

    if (action !in setOf(PlayAction.DOUBLE_PLAY, PlayAction.TRIPLE_PLAY) && state.outs + outsRecorded > 3) {
        add("There are only ${3 - state.outs} out${if (3 - state.outs == 1) "" else "s"} remaining in the inning, but this play is set to record $outsRecorded.")
    }

    if (action == PlayAction.SACRIFICE_FLY) {
        val runnerScores = destinations.any { (playerId, destination) -> playerId != batterId && destination == Base.HOME }
        if (!runnerScores) add("No existing runner is set to score. Without a run scoring after the catch, this would normally be recorded as a fly out rather than a sacrifice fly.")
    }

    if (action == PlayAction.SACRIFICE || action == PlayAction.SACRIFICE_BUNT) {
        val runnerAdvanced = destinations.any { (playerId, destination) ->
            if (playerId == batterId || destination == null) return@any false
            val origin = runnerBase(state, playerId) ?: return@any false
            baseRank(destination) > baseRank(origin)
        }
        if (!runnerAdvanced) add("No existing runner is set to advance. A sacrifice bunt normally advances a runner while the batter is retired.")
    }
}

private fun smartEndGameMessage(state: GameState): String {
    val checks = buildList {
        if (state.currentInning < state.maxInnings) {
            add("The game is only in inning ${state.currentInning} of ${state.maxInnings}.")
        }
        if (state.totalRuns(Team.AWAY) == state.totalRuns(Team.HOME)) {
            add("The score is tied ${state.totalRuns(Team.AWAY)}-${state.totalRuns(Team.HOME)}.")
        }
        if (state.balls > 0 || state.strikes > 0) {
            add("The current batter has an unfinished ${state.balls}-${state.strikes} count.")
        }
        if (!state.bases.isEmpty()) {
            add("There are still runners on base.")
        }
    }
    return if (checks.isEmpty()) {
        "The current score will be saved as the final score. You can still start a new game afterward."
    } else {
        "Before ending the game:\n\n${checks.joinToString("\n") { "• $it" }}\n\nEnd the game anyway?"
    }
}

private fun playLabel(action: PlayAction): String = when (action) {
    PlayAction.SINGLE -> "Single — Runner Results"
    PlayAction.DOUBLE -> "Double — Runner Results"
    PlayAction.TRIPLE -> "Triple — Runner Results"
    PlayAction.HOME_RUN -> "Home Run"
    PlayAction.WALK -> "Walk"
    PlayAction.INTENTIONAL_WALK -> "Intentional Walk"
    PlayAction.STRIKEOUT -> "Strikeout"
    PlayAction.GROUND_OUT -> "Ground Out — Runner Results"
    PlayAction.FLY_OUT -> "Fly Out — Runner Results"
    PlayAction.FIELDERS_CHOICE -> "Fielder's Choice — Runner Results"
    PlayAction.SACRIFICE -> "Sacrifice — Runner Results"
    PlayAction.SACRIFICE_BUNT -> "Sacrifice Bunt — Runner Results"
    PlayAction.SACRIFICE_FLY -> "Sacrifice Fly — Runner Results"
    PlayAction.DOUBLE_PLAY -> "Double Play — Runner Results"
    PlayAction.TRIPLE_PLAY -> "Triple Play — Runner Results"
    PlayAction.ERROR -> "Error — Runner Results"
    PlayAction.HIT_BY_PITCH -> "Hit By Pitch"
}

@Composable
private fun FinalGameCard(state: GameState) {
    val away = state.totalRuns(Team.AWAY)
    val home = state.totalRuns(Team.HOME)
    val result = when {
        away > home -> "${state.awayTeamName} wins $away–$home"
        home > away -> "${state.homeTeamName} wins $home–$away"
        else -> "Game ends tied $away–$home"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("FINAL", fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(result, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (state.gameEndedManually) {
                Text(
                    "Ended manually",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                )
            }
        }
    }
}


private fun parseInningScores(text: String, innings: Int): List<Int> {
    val parsed = text.split(',', '\n', ' ')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { it.toIntOrNull() }
        .map { it.coerceAtLeast(0) }
        .take(innings)
        .toMutableList()
    while (parsed.size < innings) parsed += 0
    return parsed
}

private fun buildGameStateFromSetup(
    awayName: String,
    homeName: String,
    innings: Int,
    awayPlayers: List<Player>,
    homePlayers: List<Player>,
    midGameSetup: MidGameSetup,
    gameDate: String,
    ballpark: String,
    location: String,
    notes: String,
    awayPitcher: String,
    homePitcher: String,
    awayPositions: List<String>,
    homePositions: List<String>
): GameState {
    val baseState = GameController.newGame(
        awayTeamName = awayName,
        homeTeamName = homeName,
        maxInnings = innings,
        awayLineup = awayPlayers,
        homeLineup = homePlayers,
        gameDate = gameDate,
        ballpark = ballpark,
        location = location,
        notes = notes,
        awayPitcherName = awayPitcher,
        homePitcherName = homePitcher
    )
    val positionMap = buildMap<Int, String> {
        awayPlayers.forEachIndexed { index, player ->
            awayPositions.getOrNull(index)?.takeIf(String::isNotBlank)?.let { put(player.id, it) }
        }
        homePlayers.forEachIndexed { index, player ->
            homePositions.getOrNull(index)?.takeIf(String::isNotBlank)?.let { put(player.id, it) }
        }
    }
    val positionedState = baseState.copy(playerPositions = positionMap)
    if (!midGameSetup.enabled) return positionedState

    val inning = midGameSetup.inning.coerceIn(1, 20)
    val scoreSize = maxOf(innings, inning)
    var nextUnknownId = -1
    fun newUnknownId(): Int = nextUnknownId.also { nextUnknownId-- }

    val awayBatterIndex = midGameSetup.awayBatterIndex.takeIf { it in awayPlayers.indices } ?: -1
    val homeBatterIndex = midGameSetup.homeBatterIndex.takeIf { it in homePlayers.indices } ?: -1
    val awayUnknownBatterId = if (awayBatterIndex < 0) newUnknownId() else null
    val homeUnknownBatterId = if (homeBatterIndex < 0) newUnknownId() else null

    val activeLineup = if (midGameSetup.topOfInning) awayPlayers else homePlayers
    val validRunnerIds = activeLineup.map(Player::id).toSet()
    fun runnerFor(selection: Int?, base: Base): Runner? = when {
        selection == null -> null
        selection == GameController.UNKNOWN_PLAYER_SELECTION -> Runner(newUnknownId(), base)
        selection in validRunnerIds -> Runner(selection, base)
        else -> null
    }

    val midGameEvent = GameEvent(
        id = 1L,
        inning = inning,
        topOfInning = midGameSetup.topOfInning,
        battingTeam = if (midGameSetup.topOfInning) Team.AWAY else Team.HOME,
        type = GameEventType.GAME_STATE_EDIT,
        title = "Tracking started mid-game",
        detail = "${if (midGameSetup.topOfInning) "Top" else "Bottom"} $inning • ${midGameSetup.awayScore}-${midGameSetup.homeScore} • earlier inning distribution unknown"
    )
    return positionedState.copy(
        balls = midGameSetup.balls.coerceIn(0, 3),
        strikes = midGameSetup.strikes.coerceIn(0, 2),
        outs = midGameSetup.outs.coerceIn(0, 2),
        currentInning = inning,
        topOfInning = midGameSetup.topOfInning,
        activeTeam = if (midGameSetup.topOfInning) Team.AWAY else Team.HOME,
        awayBatterIndex = awayBatterIndex,
        homeBatterIndex = homeBatterIndex,
        awayUnknownBatterId = awayUnknownBatterId,
        homeUnknownBatterId = homeUnknownBatterId,
        nextUnknownPlayerId = nextUnknownId,
        scores = mapOf(
            Team.AWAY to List(scoreSize) { 0 },
            Team.HOME to List(scoreSize) { 0 }
        ),
        carryInRuns = mapOf(
            Team.AWAY to midGameSetup.awayScore.coerceAtLeast(0),
            Team.HOME to midGameSetup.homeScore.coerceAtLeast(0)
        ),
        trackingStartedMidGame = true,
        bases = Bases(
            first = runnerFor(midGameSetup.firstRunnerId, Base.FIRST),
            second = runnerFor(midGameSetup.secondRunnerId, Base.SECOND),
            third = runnerFor(midGameSetup.thirdRunnerId, Base.THIRD)
        ),
        events = listOf(midGameEvent),
        nextEventId = 2L
    )
}

@Composable
private fun MidGameSetupCard(
    awayPlayers: List<Player>,
    homePlayers: List<Player>,
    startInningText: String,
    onStartInningTextChange: (String) -> Unit,
    topOfInning: Boolean,
    onTopOfInningChange: (Boolean) -> Unit,
    ballsText: String,
    onBallsTextChange: (String) -> Unit,
    strikesText: String,
    onStrikesTextChange: (String) -> Unit,
    outsText: String,
    onOutsTextChange: (String) -> Unit,
    awayScoresText: String,
    onAwayScoresTextChange: (String) -> Unit,
    homeScoresText: String,
    onHomeScoresTextChange: (String) -> Unit,
    awayBatterText: String,
    onAwayBatterTextChange: (String) -> Unit,
    homeBatterText: String,
    onHomeBatterTextChange: (String) -> Unit,
    firstRunnerId: Int?,
    onFirstRunnerIdChange: (Int?) -> Unit,
    secondRunnerId: Int?,
    onSecondRunnerIdChange: (Int?) -> Unit,
    thirdRunnerId: Int?,
    onThirdRunnerIdChange: (Int?) -> Unit
) {
    val battingLineup = if (topOfInning) awayPlayers else homePlayers
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Resume / Mid-Game Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Enter only what you know. The current score is enough — InningTrack will not invent which earlier innings the runs were scored in.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = startInningText,
                    onValueChange = { onStartInningTextChange(it.filter(Char::isDigit).take(2)) },
                    label = { Text("Inning") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = topOfInning,
                    onClick = { onTopOfInningChange(true) },
                    label = { Text("Top") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !topOfInning,
                    onClick = { onTopOfInningChange(false) },
                    label = { Text("Bottom") },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactNumberField("Balls", ballsText, onBallsTextChange, 1, Modifier.weight(1f))
                CompactNumberField("Strikes", strikesText, onStrikesTextChange, 1, Modifier.weight(1f))
                CompactNumberField("Outs", outsText, onOutsTextChange, 1, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactNumberField("Away score", awayScoresText, onAwayScoresTextChange, 3, Modifier.weight(1f))
                CompactNumberField("Home score", homeScoresText, onHomeScoresTextChange, 3, Modifier.weight(1f))
            }
            Text(
                "Earlier runs are stored as score-before-tracking. Inning boxes stay unknown instead of being guessed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Either current batter can be Unknown. InningTrack can still score the game and you can assign the temporary Unknown stats to the real player later.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BatterSelectionDropdown(
                label = "Away current batter",
                lineup = awayPlayers,
                selectedIndex = awayBatterText.toIntOrNull()?.minus(1),
                onSelectedIndexChange = { index -> onAwayBatterTextChange(index?.plus(1)?.toString().orEmpty()) }
            )
            BatterSelectionDropdown(
                label = "Home current batter",
                lineup = homePlayers,
                selectedIndex = homeBatterText.toIntOrNull()?.minus(1),
                onSelectedIndexChange = { index -> onHomeBatterTextChange(index?.plus(1)?.toString().orEmpty()) }
            )
            Text("Current runners (${if (topOfInning) "Away" else "Home"} batting)", fontWeight = FontWeight.SemiBold)
            RunnerSelectionDropdown("1B", battingLineup, firstRunnerId, onFirstRunnerIdChange)
            RunnerSelectionDropdown("2B", battingLineup, secondRunnerId, onSecondRunnerIdChange)
            RunnerSelectionDropdown("3B", battingLineup, thirdRunnerId, onThirdRunnerIdChange)
        }
    }
}

@Composable
private fun CompactNumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    maxDigits: Int,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxDigits)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun RunnerSelectionDropdown(
    label: String,
    lineup: List<Player>,
    selectedPlayerId: Int?,
    onSelectedPlayerIdChange: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = when {
        selectedPlayerId == null -> "No runner"
        selectedPlayerId == GameController.UNKNOWN_PLAYER_SELECTION || selectedPlayerId < 0 -> "Unknown player"
        else -> lineup.firstOrNull { it.id == selectedPlayerId }?.name ?: "Unknown player"
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $selectedName")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("No runner") }, onClick = {
                expanded = false
                onSelectedPlayerIdChange(null)
            })
            DropdownMenuItem(text = { Text("Unknown player — identify later") }, onClick = {
                expanded = false
                onSelectedPlayerIdChange(GameController.UNKNOWN_PLAYER_SELECTION)
            })
            lineup.forEachIndexed { index, player ->
                DropdownMenuItem(text = { Text("${index + 1}. ${player.name}") }, onClick = {
                    expanded = false
                    onSelectedPlayerIdChange(player.id)
                })
            }
        }
    }
}

@Composable
private fun DraggablePersonnelRow(
    state: GameState,
    team: Team,
    player: Player,
    index: Int,
    lastIndex: Int,
    onRenamePlayer: (Int, String) -> Unit,
    onPositionChange: (Int, String) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val rowHeightPx = with(LocalDensity.current) { 72.dp.toPx() }
    var dragOffsetY by remember(player.id, index) { mutableStateOf(0f) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "${index + 1}.",
            modifier = Modifier.width(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "☰",
            fontSize = 20.sp,
            modifier = Modifier
                .width(22.dp)
                .pointerInput(index, lastIndex) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetY += dragAmount.y
                        },
                        onDragEnd = {
                            val delta = (dragOffsetY / rowHeightPx).roundToInt()
                            dragOffsetY = 0f
                            val target = (index + delta).coerceIn(0, lastIndex)
                            if (target != index) onMove(index, target)
                        },
                        onDragCancel = { dragOffsetY = 0f }
                    )
                },
            color = MaterialTheme.colorScheme.primary
        )
        InlinePersonnelNameField(
            value = player.name,
            label = "Player name",
            onCommit = { onRenamePlayer(player.id, it) },
            modifier = Modifier.weight(1f)
        )
        PersonnelPositionDropdown(
            state = state,
            team = team,
            player = player,
            onPositionChange = onPositionChange
        )
    }
}

private data class ScorecardPlayerLine(
    val entriesByInning: Map<Int, List<String>>,
    val atBats: Int,
    val runs: Int,
    val hits: Int,
    val rbi: Int,
    val walks: Int,
    val strikeouts: Int
)

@Composable
private fun HistoricalScorecardScreen(
    completedGame: CompletedGame,
    onBack: () -> Unit,
    onCorrectFinalScore: (Int, Int) -> Unit,
    onCorrectEvent: (Long, Int?, String?, PlayAction?) -> Unit,
    onEditAtBat: (Long, List<PitchAction>, PlayAction, Int?, String?, String?) -> Unit,
    onDeleteEvent: (Long) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = completedGame.state
    var showScoreCorrection by remember { mutableStateOf(false) }
    var correctingEvent by remember { mutableStateOf<GameEvent?>(null) }
    var deleteEvent by remember { mutableStateOf<GameEvent?>(null) }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(GameExport.csv(state)) }
                    ?: error("Could not open CSV destination.")
            }
        }
    }
    val pdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) runCatching { GameExport.writePdf(context, uri, state) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Home") }
            Text(
                "Saved Game",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Text("FINAL", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { csvLauncher.launch("${state.awayTeamName}-${state.homeTeamName}-stats.csv") },
                modifier = Modifier.weight(1f)
            ) { Text("Export CSV") }
            OutlinedButton(
                onClick = { pdfLauncher.launch("${state.awayTeamName}-${state.homeTeamName}-scorecard.pdf") },
                modifier = Modifier.weight(1f)
            ) { Text("Export PDF") }
        }
        OutlinedButton(onClick = { showScoreCorrection = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Correct Final Score")
        }
        FullScorecardScreen(state = state, onEditAtBat = onEditAtBat)
        GameStatsCard(state)
        PostgameCorrectionsCard(
            state = state,
            onCorrect = { correctingEvent = it },
            onDelete = { deleteEvent = it }
        )
        GameLogCard(state = state, onEditPersonnelEvent = { _, _ -> }, allowEditing = false)
        Spacer(Modifier.height(24.dp))
    }

    if (showScoreCorrection) {
        FinalScoreCorrectionDialog(
            state = state,
            onDismiss = { showScoreCorrection = false },
            onConfirm = { away, home ->
                showScoreCorrection = false
                onCorrectFinalScore(away, home)
            }
        )
    }
    correctingEvent?.let { event ->
        HistoricalEventCorrectionDialog(
            state = state,
            event = event,
            onDismiss = { correctingEvent = null },
            onConfirm = { batterId, pitcher, action ->
                correctingEvent = null
                onCorrectEvent(event.id, batterId, pitcher, action)
            }
        )
    }
    deleteEvent?.let { event ->
        AlertDialog(
            onDismissRequest = { deleteEvent = null },
            title = { Text("Delete recorded event?") },
            text = { Text("Delete '${event.title}' from the saved game? InningTrack will recalculate inning runs, hits and errors from the remaining recorded events. This cannot reconstruct information that was never recorded.") },
            confirmButton = {
                Button(onClick = {
                    deleteEvent = null
                    onDeleteEvent(event.id)
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete Event") }
            },
            dismissButton = { TextButton(onClick = { deleteEvent = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PostgameCorrectionsCard(
    state: GameState,
    onCorrect: (GameEvent) -> Unit,
    onDelete: (GameEvent) -> Unit
) {
    val correctable = state.events.filter { it.type == GameEventType.PLAY || (it.type == GameEventType.PITCH && it.playAction != null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Postgame Corrections", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Correct a batter, pitcher or scoring decision after the game is final. Deleting an event recalculates tracked R/H/E; use Correct Final Score for score-only fixes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (correctable.isEmpty()) {
                Text("No recorded plate appearances to correct.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                correctable.forEach { event ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${if (event.topOfInning) "Top" else "Bottom"} ${event.inning} — ${event.title}", fontWeight = FontWeight.SemiBold)
                            Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onCorrect(event) }) { Text("Correct") }
                        TextButton(onClick = { onDelete(event) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FinalScoreCorrectionDialog(
    state: GameState,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var away by remember { mutableStateOf(state.totalRuns(Team.AWAY).toString()) }
    var home by remember { mutableStateOf(state.totalRuns(Team.HOME).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct final score") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = away, onValueChange = { away = it.filter(Char::isDigit) }, label = { Text(state.awayTeamName) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = home, onValueChange = { home = it.filter(Char::isDigit) }, label = { Text(state.homeTeamName) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("If the exact inning of a correction is unknown, InningTrack keeps the difference as an undistributed score instead of inventing an inning.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(away.toIntOrNull() ?: 0, home.toIntOrNull() ?: 0) }) { Text("Save Score") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun HistoricalEventCorrectionDialog(
    state: GameState,
    event: GameEvent,
    onDismiss: () -> Unit,
    onConfirm: (Int?, String?, PlayAction?) -> Unit
) {
    val lineup = if (event.battingTeam == Team.AWAY) state.lineupAway else state.lineupHome
    var batterId by remember(event.id) { mutableStateOf(event.actorPlayerId) }
    var pitcher by remember(event.id) { mutableStateOf(event.pitcherName.orEmpty()) }
    var action by remember(event.id) { mutableStateOf(event.playAction) }
    var actionExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct recorded play") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${if (event.topOfInning) "Top" else "Bottom"} ${event.inning} — ${event.title}")
                PlayerIdDropdown("Batter", lineup, batterId) { batterId = it }
                OutlinedTextField(
                    value = pitcher,
                    onValueChange = { pitcher = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pitcher") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { actionExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Scoring: ${action?.let(::plainPlayLabel) ?: "Unknown"}")
                    }
                    DropdownMenu(expanded = actionExpanded, onDismissRequest = { actionExpanded = false }) {
                        PlayAction.values().forEach { option ->
                            DropdownMenuItem(text = { Text(plainPlayLabel(option)) }, onClick = {
                                actionExpanded = false
                                action = option
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(batterId, pitcher.trim().takeIf(String::isNotBlank), action) }) { Text("Save Correction") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PlayerIdDropdown(
    label: String,
    players: List<Player>,
    selectedId: Int?,
    onSelected: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val name = players.firstOrNull { it.id == selectedId }?.name ?: "Unknown"
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: $name") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Unknown") }, onClick = { expanded = false; onSelected(null) })
            players.forEach { player ->
                DropdownMenuItem(text = { Text(player.name) }, onClick = { expanded = false; onSelected(player.id) })
            }
        }
    }
}

private fun plainPlayLabel(action: PlayAction): String = when (action) {
    PlayAction.SINGLE -> "Single"
    PlayAction.DOUBLE -> "Double"
    PlayAction.TRIPLE -> "Triple"
    PlayAction.HOME_RUN -> "Home Run"
    PlayAction.WALK -> "Walk"
    PlayAction.INTENTIONAL_WALK -> "Intentional Walk"
    PlayAction.STRIKEOUT -> "Strikeout"
    PlayAction.GROUND_OUT -> "Ground Out"
    PlayAction.FLY_OUT -> "Fly Out"
    PlayAction.FIELDERS_CHOICE -> "Fielder's Choice"
    PlayAction.SACRIFICE -> "Sacrifice"
    PlayAction.SACRIFICE_BUNT -> "Sacrifice Bunt"
    PlayAction.SACRIFICE_FLY -> "Sacrifice Fly"
    PlayAction.DOUBLE_PLAY -> "Double Play"
    PlayAction.TRIPLE_PLAY -> "Triple Play"
    PlayAction.ERROR -> "Error"
    PlayAction.HIT_BY_PITCH -> "Hit By Pitch"
}

@Composable
private fun FullScorecardScreen(
    state: GameState,
    onEditAtBat: ((Long, List<PitchAction>, PlayAction, Int?, String?, String?) -> Unit)? = null
) {
    val displayedInnings = maxOf(11, state.maxInnings, state.currentInning)
    val gridMetrics = remember(state.events, state.lineupAway, state.lineupHome) { scorecardGridMetrics(state) }
    var selectedAtBat by remember { mutableStateOf<GameEvent?>(null) }
    var atBatChoices by remember { mutableStateOf<List<GameEvent>>(emptyList()) }
    val cellTap: ((List<GameEvent>) -> Unit)? = if (onEditAtBat != null) {
        { events: List<GameEvent> ->
            if (events.size == 1) selectedAtBat = events.first()
            else if (events.isNotEmpty()) atBatChoices = events
        }
    } else null

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Full Scorecard",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (onEditAtBat != null) {
                "Tap any recorded at-bat cell to review and correct its pitches, batter, pitcher or outcome."
            } else {
                "Styled after a traditional paper scorebook. Use this with Scorekeeping and Game Overview."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ScorecardGameInfo(state)
        val horizontal = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontal)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            TeamScorecardSection(
                state = state,
                team = Team.AWAY,
                displayedInnings = displayedInnings,
                gridMetrics = gridMetrics,
                onAtBatCellTapped = cellTap
            )
            TeamScorecardSection(
                state = state,
                team = Team.HOME,
                displayedInnings = displayedInnings,
                gridMetrics = gridMetrics,
                onAtBatCellTapped = cellTap
            )
            PitchingScorecardSection(state)
            ScorecardSummarySection(state, displayedInnings)
        }
    }

    if (atBatChoices.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { atBatChoices = emptyList() },
            title = { Text("Choose at-bat") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "This batter had more than one plate appearance in this inning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    atBatChoices.forEachIndexed { index, event ->
                        OutlinedButton(
                            onClick = {
                                selectedAtBat = event
                                atBatChoices = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${index + 1}. ${scorecardCodeFor(event) ?: plainPlayLabel(event.playAction ?: PlayAction.GROUND_OUT)} — ${event.actorName ?: "Unknown"}")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { atBatChoices = emptyList() }) { Text("Cancel") }
            }
        )
    }

    selectedAtBat?.let { event ->
        if (onEditAtBat != null) {
            ScorecardAtBatEditorDialog(
                state = state,
                endEvent = event,
                onDismiss = { selectedAtBat = null },
                onSave = { pitches, action, batterId, pitcher, notation ->
                    selectedAtBat = null
                    onEditAtBat(event.id, pitches, action, batterId, pitcher, notation)
                }
            )
        }
    }
}

@Composable
private fun ScorecardAtBatEditorDialog(
    state: GameState,
    endEvent: GameEvent,
    onDismiss: () -> Unit,
    onSave: (List<PitchAction>, PlayAction, Int?, String?, String?) -> Unit
) {
    val lineup = if (endEvent.battingTeam == Team.AWAY) state.lineupAway else state.lineupHome
    var batterId by remember(endEvent.id) { mutableStateOf(endEvent.actorPlayerId) }
    var pitcher by remember(endEvent.id) { mutableStateOf(endEvent.pitcherName.orEmpty()) }
    var outcome by remember(endEvent.id) { mutableStateOf(endEvent.playAction ?: PlayAction.GROUND_OUT) }
    var outcomeExpanded by remember { mutableStateOf(false) }
    var pitches by remember(endEvent.id) { mutableStateOf(recordedAtBatPitches(state, endEvent.id)) }
    var fieldingNotation by remember(endEvent.id) {
        mutableStateOf(
            if (endEvent.playAction == PlayAction.GROUND_OUT) endEvent.fieldingNotation.orEmpty().removePrefix("G")
            else endEvent.fieldingNotation.orEmpty()
        )
    }
    var showDiscardChanges by remember(endEvent.id) { mutableStateOf(false) }
    val originalPitches = remember(endEvent.id) { recordedAtBatPitches(state, endEvent.id) }
    val originalOutcome = endEvent.playAction ?: PlayAction.GROUND_OUT
    val originalPitcher = endEvent.pitcherName.orEmpty()
    val originalNotation = if (endEvent.playAction == PlayAction.GROUND_OUT) endEvent.fieldingNotation.orEmpty().removePrefix("G") else endEvent.fieldingNotation.orEmpty()
    val hasUnsavedChanges = batterId != endEvent.actorPlayerId || pitcher != originalPitcher || outcome != originalOutcome ||
        pitches != originalPitches || fieldingNotation != originalNotation

    fun requestDismiss() {
        if (hasUnsavedChanges) showDiscardChanges = true else onDismiss()
    }

    val ballCount = pitches.count { it == PitchAction.BALL }
    val strikeCount = pitches.count { it == PitchAction.STRIKE }
    val foulCount = pitches.count { it == PitchAction.FOUL }
    val inPlayCount = pitches.count { it == PitchAction.IN_PLAY }
    val hbpCount = pitches.count { it == PitchAction.HIT_BY_PITCH }

    AlertDialog(
        onDismissRequest = { requestDismiss() },
        title = { Text("Edit at-bat") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${if (endEvent.topOfInning) "Top" else "Bottom"} ${endEvent.inning}",
                    fontWeight = FontWeight.Bold
                )
                PlayerIdDropdown("Batter", lineup, batterId) { batterId = it }
                OutlinedTextField(
                    value = pitcher,
                    onValueChange = { pitcher = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pitcher") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { outcomeExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Outcome: ${plainPlayLabel(outcome)}")
                    }
                    DropdownMenu(
                        expanded = outcomeExpanded,
                        onDismissRequest = { outcomeExpanded = false }
                    ) {
                        PlayAction.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(plainPlayLabel(option)) },
                                onClick = {
                                    outcome = option
                                    if (!supportsScorebookFieldingNotation(option)) fieldingNotation = ""
                                    outcomeExpanded = false
                                }
                            )
                        }
                    }
                }

                if (supportsScorebookFieldingNotation(outcome)) {
                    OutlinedTextField(
                        value = fieldingNotation,
                        onValueChange = { fieldingNotation = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Scorecard fielding notation") },
                        placeholder = {
                            Text(
                                when (outcome) {
                                    PlayAction.FLY_OUT -> "F8"
                                    PlayAction.GROUND_OUT -> "6-3"
                                    PlayAction.DOUBLE_PLAY -> "5-4-3 DP"
                                    PlayAction.TRIPLE_PLAY -> "5-4-3-2 TP"
                                    PlayAction.SACRIFICE_FLY -> "SF8"
                                    else -> "6-3"
                                }
                            )
                        },
                        singleLine = true
                    )
                    Text(
                        "Use defensive position numbers: 1 P, 2 C, 3 1B, 4 2B, 5 3B, 6 SS, 7 LF, 8 CF, 9 RF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()
                Text("Pitch sequence", fontWeight = FontWeight.SemiBold)
                Text(
                    "${pitches.size} pitches • Balls $ballCount • Strikes $strikeCount • Fouls $foulCount${if (inPlayCount > 0) " • In play $inPlayCount" else ""}${if (hbpCount > 0) " • HBP $hbpCount" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { pitches = pitches + PitchAction.BALL },
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Ball") }
                    OutlinedButton(
                        onClick = { pitches = pitches + PitchAction.STRIKE },
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Strike") }
                    OutlinedButton(
                        onClick = { pitches = pitches + PitchAction.FOUL },
                        modifier = Modifier.weight(1f)
                    ) { Text("+ Foul") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { pitches = pitches + PitchAction.IN_PLAY },
                        modifier = Modifier.weight(1f)
                    ) { Text("+ In play") }
                    OutlinedButton(
                        onClick = { pitches = pitches + PitchAction.HIT_BY_PITCH },
                        modifier = Modifier.weight(1f)
                    ) { Text("+ HBP") }
                }

                if (pitches.isEmpty()) {
                    Text(
                        "No pitches are recorded for this at-bat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    pitches.forEachIndexed { index, pitch ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}. ${pitchEditorLabel(pitch)}",
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                pitches = pitches.toMutableList().also { it.removeAt(index) }
                            }) {
                                Text("Remove")
                            }
                        }
                    }
                }

                if (endEvent.runsScored > 0 || endEvent.outsRecorded > 0 || endEvent.putoutPlayerName != null || endEvent.assistPlayerNames.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Recorded play details", fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append("Runs: ${endEvent.runsScored} • Outs: ${endEvent.outsRecorded}")
                            endEvent.putoutPlayerName?.let { append(" • Putout: $it") }
                            if (endEvent.assistPlayerNames.isNotEmpty()) append(" • Assists: ${endEvent.assistPlayerNames.joinToString()}")
                            endEvent.fieldingNotation?.let { append(" • Scorecard: $it") }
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Changing the outcome updates the scorecard and statistical classification. Runner movement, runs already recorded, and fielding credits are preserved; correct those separately in Game Overview if needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    pitches,
                    outcome,
                    batterId,
                    pitcher.trim().takeIf(String::isNotBlank),
                    fieldingNotation.trim().takeIf(String::isNotBlank)
                )
            }) { Text("Save At-Bat") }
        },
        dismissButton = { TextButton(onClick = { requestDismiss() }) { Text("Cancel") } }
    )

    if (showDiscardChanges) {
        UnsavedChangesDialog(
            title = "Discard at-bat changes?",
            message = "You changed this recorded at-bat but have not saved it. Going back now will discard those corrections.",
            onKeepEditing = { showDiscardChanges = false },
            onDiscard = {
                showDiscardChanges = false
                onDismiss()
            }
        )
    }
}

private fun recordedAtBatPitches(state: GameState, endEventId: Long): List<PitchAction> {
    val endIndex = state.events.indexOfFirst { it.id == endEventId }
    if (endIndex < 0) return emptyList()
    val endEvent = state.events[endIndex]
    if (!isScorecardPlateAppearance(endEvent)) return emptyList()
    val previousCompletionIndex = state.events
        .subList(0, endIndex)
        .indexOfLast(::isScorecardPlateAppearance)
    return state.events
        .subList(previousCompletionIndex + 1, endIndex + 1)
        .filter { event ->
            event.type == GameEventType.PITCH &&
                event.battingTeam == endEvent.battingTeam &&
                event.actorPlayerId == endEvent.actorPlayerId
        }
        .mapNotNull(GameEvent::pitchAction)
}

private fun pitchEditorLabel(action: PitchAction): String = when (action) {
    PitchAction.BALL -> "Ball"
    PitchAction.STRIKE -> "Strike"
    PitchAction.FOUL -> "Foul"
    PitchAction.IN_PLAY -> "Ball in play"
    PitchAction.HIT_BY_PITCH -> "Hit by pitch"
}

private fun isScorecardPlateAppearance(event: GameEvent): Boolean =
    event.playAction != null && event.type in setOf(GameEventType.PLAY, GameEventType.PITCH)

private fun scorecardPlateAppearances(
    state: GameState,
    team: Team,
    playerId: Int,
    inning: Int
): List<GameEvent> = state.events.filter { event ->
    event.battingTeam == team &&
        event.actorPlayerId == playerId &&
        event.inning == inning &&
        isScorecardPlateAppearance(event)
}

@Composable
private fun ScorecardGameInfo(state: GameState) {
    val details = buildList {
        if (state.gameDate.isNotBlank()) add("Date: ${state.gameDate}")
        if (state.ballpark.isNotBlank()) add("Ballpark: ${state.ballpark}")
        if (state.location.isNotBlank()) add("Location: ${state.location}")
    }
    if (details.isNotEmpty() || state.notes.isNotBlank()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (state.notes.isNotBlank()) {
                    Text("Notes: ${state.notes}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TeamScorecardSection(
    state: GameState,
    team: Team,
    displayedInnings: Int,
    gridMetrics: ScorecardGridMetrics,
    onAtBatCellTapped: ((List<GameEvent>) -> Unit)? = null
) {
    val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
    val lineMap = buildScorecardLines(state, team)
    Text(
        text = if (team == Team.AWAY) "VISITING TEAM  ${state.awayTeamName}" else "HOME TEAM  ${state.homeTeamName}",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScorecardHeaderCell("No", 36.dp, gridMetrics.rowHeight)
        ScorecardHeaderCell("BATTER", 180.dp, gridMetrics.rowHeight)
        ScorecardHeaderCell("PO", 44.dp, gridMetrics.rowHeight)
        for (inning in 1..displayedInnings) ScorecardHeaderCell(inning.toString(), gridMetrics.inningWidth, gridMetrics.rowHeight)
        listOf("AB", "R", "H", "RBI", "BB", "SO").forEach { label ->
            ScorecardHeaderCell(label, 42.dp, gridMetrics.rowHeight)
        }
    }
    val unknownStats = GameStats.batting(state, team).filter { it.playerId < 0 }
    val rows = lineup.mapIndexed { index, player ->
        Triple(player.id, "${index + 1}", player.name)
    } + unknownStats.mapIndexed { index, stat ->
        Triple(stat.playerId, "?${index + 1}", "Unknown")
    }
    rows.forEach { (playerId, orderLabel, playerName) ->
        val line = lineMap[playerId] ?: ScorecardPlayerLine(emptyMap(), 0, 0, 0, 0, 0, 0)
        Row(verticalAlignment = Alignment.Top) {
            ScorecardBodyCell(orderLabel, 36.dp, height = gridMetrics.rowHeight)
            ScorecardBodyCell(playerName, 180.dp, height = gridMetrics.rowHeight)
            ScorecardBodyCell(if (playerId < 0) "?" else state.playerPosition(playerId) ?: "—", 44.dp, height = gridMetrics.rowHeight)
            for (inning in 1..displayedInnings) {
                val entry = line.entriesByInning[inning]?.joinToString(" / ") ?: ""
                val plateAppearances = scorecardPlateAppearances(state, team, playerId, inning)
                val cellOnClick: (() -> Unit)? = if (onAtBatCellTapped != null && plateAppearances.isNotEmpty()) {
                    { onAtBatCellTapped(plateAppearances) }
                } else null
                ScorecardBodyCell(
                    text = entry,
                    width = gridMetrics.inningWidth,
                    height = gridMetrics.rowHeight,
                    onClick = cellOnClick
                )
            }
            ScorecardBodyCell(line.atBats.toString(), 42.dp, height = gridMetrics.rowHeight)
            ScorecardBodyCell(line.runs.toString(), 42.dp, height = gridMetrics.rowHeight)
            ScorecardBodyCell(line.hits.toString(), 42.dp, height = gridMetrics.rowHeight)
            ScorecardBodyCell(line.rbi.toString(), 42.dp, height = gridMetrics.rowHeight)
            ScorecardBodyCell(line.walks.toString(), 42.dp, height = gridMetrics.rowHeight)
            ScorecardBodyCell(line.strikeouts.toString(), 42.dp, height = gridMetrics.rowHeight)
        }
    }
}

@Composable
private fun PitchingScorecardSection(state: GameState) {
    Text("PITCHING", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScorecardHeaderCell("TEAM", 96.dp)
        ScorecardHeaderCell("PITCHER", 160.dp)
        listOf("IP", "BF", "P", "H", "R", "ER", "BB", "SO", "HR", "ERA", "WHIP").forEach { label ->
            ScorecardHeaderCell(label, 44.dp)
        }
    }
    listOf(Team.AWAY, Team.HOME).forEach { team ->
        val rows = GameStats.pitching(state, team)
        rows.forEachIndexed { index, stat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScorecardBodyCell(if (index == 0) state.teamName(team) else "", 96.dp)
                ScorecardBodyCell(stat.pitcherName, 160.dp)
                ScorecardBodyCell(stat.inningsPitchedDisplay, 44.dp)
                ScorecardBodyCell(stat.battersFaced.toString(), 44.dp)
                ScorecardBodyCell(stat.pitches.toString(), 44.dp)
                ScorecardBodyCell(stat.hitsAllowed.toString(), 44.dp)
                ScorecardBodyCell(stat.runsAllowed.toString(), 44.dp)
                ScorecardBodyCell(stat.earnedRuns.toString(), 44.dp)
                ScorecardBodyCell(stat.walks.toString(), 44.dp)
                ScorecardBodyCell(stat.strikeouts.toString(), 44.dp)
                ScorecardBodyCell(stat.homeRunsAllowed.toString(), 44.dp)
                ScorecardBodyCell(formatRate(stat.era), 44.dp)
                ScorecardBodyCell(formatRate(stat.whip), 44.dp)
            }
        }
    }
}

@Composable
private fun ScorecardSummarySection(state: GameState, displayedInnings: Int) {
    Text("LINESCORE", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScorecardHeaderCell("TEAM", 96.dp)
        for (inning in 1..displayedInnings) ScorecardHeaderCell(inning.toString(), 36.dp)
        listOf("R", "H", "E").forEach { ScorecardHeaderCell(it, 42.dp) }
    }
    listOf(Team.AWAY, Team.HOME).forEach { team ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScorecardBodyCell(state.teamName(team), 96.dp)
            val innings = state.scores[team].orEmpty()
            val trackingStart = state.events.firstOrNull {
                it.type == GameEventType.GAME_STATE_EDIT && it.title == "Tracking started mid-game"
            }
            for (inning in 1..displayedInnings) {
                val recorded = innings.getOrNull(inning - 1) ?: 0
                val unknownDistribution = state.trackingStartedMidGame &&
                    (state.carryInRuns[team] ?: 0) > 0 &&
                    trackingStart != null &&
                    (inning < trackingStart.inning ||
                        (inning == trackingStart.inning && when {
                            trackingStart.topOfInning -> team == Team.AWAY
                            else -> true
                        }))
                val text = when {
                    unknownDistribution -> if (recorded > 0) "?+$recorded" else "?"
                    inning <= state.currentInning -> recorded.toString()
                    else -> ""
                }
                ScorecardBodyCell(text, 36.dp)
            }
            ScorecardBodyCell(state.totalRuns(team).toString(), 42.dp)
            ScorecardBodyCell((state.hits[team] ?: 0).toString(), 42.dp)
            ScorecardBodyCell((state.errors[team] ?: 0).toString(), 42.dp)
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "Game overview and scorekeeping continue to be the editable sources of truth. This scorecard mirrors the game log and inning lines in a traditional scorebook-style layout.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ScorecardHeaderCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp = 36.dp
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun ScorecardBodyCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp = 36.dp,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .then(clickModifier)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            maxLines = 3,
            color = if (onClick != null && text.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

private data class ScorecardGridMetrics(
    val inningWidth: androidx.compose.ui.unit.Dp,
    val rowHeight: androidx.compose.ui.unit.Dp
)

private fun scorecardGridMetrics(state: GameState): ScorecardGridMetrics {
    val lines = buildScorecardLines(state, Team.AWAY).values + buildScorecardLines(state, Team.HOME).values
    val cellTexts = lines.flatMap { line ->
        line.entriesByInning.values.map { entries -> entries.joinToString(" / ") }
    }
    val longest = cellTexts.maxOfOrNull(String::length) ?: 0
    val maxEntries = lines.flatMap { it.entriesByInning.values }.maxOfOrNull { it.size } ?: 0

    val width = when {
        longest > 14 -> 104.dp
        longest > 9 -> 88.dp
        longest > 6 -> 76.dp
        else -> 64.dp
    }
    val height = when {
        maxEntries >= 3 || longest > 18 -> 72.dp
        maxEntries == 2 || longest > 10 -> 58.dp
        else -> 44.dp
    }
    return ScorecardGridMetrics(width, height)
}

private fun normalizeGroundOutNotation(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return "GO"
    val cleaned = text
        .replace(Regex("^GO\\s*/\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^GO\\s+", RegexOption.IGNORE_CASE), "")
        .replace(Regex("^G(?=\\d)", RegexOption.IGNORE_CASE), "")
        .trim()
    return cleaned.ifBlank { "GO" }
}

private fun buildScorecardLines(state: GameState, team: Team): Map<Int, ScorecardPlayerLine> {
    val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
    val stats = GameStats.batting(state, team)
    val trackedPlayers = (lineup.map(Player::id) + stats.filter { it.playerId < 0 }.map(BattingStats::playerId)).toSet()
    val entries = mutableMapOf<Int, MutableMap<Int, MutableList<String>>>()
    state.events.filter { it.battingTeam == team && it.actorPlayerId in trackedPlayers }.forEach { event ->
        val playerId = event.actorPlayerId ?: return@forEach
        val code = scorecardCodeFor(event) ?: return@forEach
        val inningEntries = entries.getOrPut(playerId) { mutableMapOf() }
            .getOrPut(event.inning) { mutableListOf() }
        val previous = inningEntries.lastOrNull()
        val replacesGenericOut = when (event.playAction) {
            PlayAction.GROUND_OUT -> previous == "GO" && code != "GO"
            PlayAction.FLY_OUT -> previous == "FO" && code != "FO"
            PlayAction.SACRIFICE_FLY -> previous == "SF" && code != "SF"
            PlayAction.DOUBLE_PLAY -> previous == "DP" && code != "DP"
            PlayAction.TRIPLE_PLAY -> previous == "TP" && code != "TP"
            else -> false
        }
        if (replacesGenericOut) {
            inningEntries[inningEntries.lastIndex] = code
        } else {
            inningEntries.add(code)
        }
    }
    val statMap = stats.associateBy(BattingStats::playerId)
    return trackedPlayers.associateWith { playerId ->
        val stat = statMap[playerId]
        ScorecardPlayerLine(
            entriesByInning = entries[playerId].orEmpty().mapValues { it.value.toList() },
            atBats = stat?.atBats ?: 0,
            runs = stat?.runs ?: 0,
            hits = stat?.hits ?: 0,
            rbi = stat?.rbi ?: 0,
            walks = stat?.walks ?: 0,
            strikeouts = stat?.strikeouts ?: 0
        )
    }
}

private fun scorecardCodeFor(event: GameEvent): String? {
    val action = event.playAction
    if (action != null) {
        return when (action) {
            PlayAction.SINGLE -> "1B"
            PlayAction.DOUBLE -> "2B"
            PlayAction.TRIPLE -> "3B"
            PlayAction.HOME_RUN -> "HR"
            PlayAction.WALK -> "BB"
            PlayAction.INTENTIONAL_WALK -> "IBB"
            PlayAction.STRIKEOUT -> "K"
            PlayAction.GROUND_OUT -> normalizeGroundOutNotation(event.fieldingNotation)
            PlayAction.FLY_OUT -> event.fieldingNotation ?: "FO"
            PlayAction.FIELDERS_CHOICE -> "FC"
            PlayAction.SACRIFICE -> "SAC"
            PlayAction.SACRIFICE_BUNT -> "SH"
            PlayAction.SACRIFICE_FLY -> event.fieldingNotation ?: "SF"
            PlayAction.DOUBLE_PLAY -> event.fieldingNotation ?: "DP"
            PlayAction.TRIPLE_PLAY -> event.fieldingNotation ?: "TP"
            PlayAction.ERROR -> "E"
            PlayAction.HIT_BY_PITCH -> "HBP"
        }
    }
    return when (event.type) {
        GameEventType.PLAY -> when (event.title.lowercase()) {
            "single" -> "1B"
            "double" -> "2B"
            "triple" -> "3B"
            "home run" -> "HR"
            "strikeout" -> "K"
            "ground out" -> normalizeGroundOutNotation(event.fieldingNotation)
            "fly out" -> event.fieldingNotation ?: "FO"
            "fielder's choice" -> "FC"
            "sacrifice" -> "SAC"
            "sacrifice bunt" -> "SH"
            "sacrifice fly" -> event.fieldingNotation ?: "SF"
            "double play" -> event.fieldingNotation ?: "DP"
            "triple play" -> event.fieldingNotation ?: "TP"
            "intentional walk" -> "IBB"
            "error" -> "E"
            else -> null
        }
        GameEventType.PITCH -> when (event.title) {
            "Ball 4 — Walk" -> "BB"
            "Strike 3 — Strikeout" -> "K"
            "Hit by pitch" -> "HBP"
            else -> null
        }
        GameEventType.BASERUNNING -> when (event.baseRunningAction) {
            BaseRunningAction.STOLEN_BASE -> "SB"
            BaseRunningAction.CAUGHT_STEALING -> "CS"
            BaseRunningAction.PICKOFF -> "PO"
            BaseRunningAction.RUNNER_OUT -> "OUT"
            BaseRunningAction.WILD_PITCH -> "WP"
            BaseRunningAction.PASSED_BALL -> "PB"
            BaseRunningAction.BALK -> "BK"
            BaseRunningAction.DEFENSIVE_INDIFFERENCE -> "DI"
            BaseRunningAction.RUNNER_ADVANCE -> "ADV"
            null -> null
        }
        else -> null
    }
}

@Composable
private fun BatterSelectionDropdown(
    label: String,
    lineup: List<Player>,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = selectedIndex?.takeIf { it in lineup.indices }?.let { lineup[it] }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: ${selected?.name ?: "Unknown"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Unknown / choose later") },
                onClick = {
                    expanded = false
                    onSelectedIndexChange(null)
                }
            )
            lineup.forEachIndexed { index, player ->
                DropdownMenuItem(
                    text = { Text("${index + 1}. ${player.name}") },
                    onClick = {
                        expanded = false
                        onSelectedIndexChange(index)
                    }
                )
            }
        }
    }
}

@Composable
private fun CurrentBatterPickerDialog(
    state: GameState,
    team: Team,
    allowUnknown: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Int?) -> Unit
) {
    val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
    val currentIndex = if (team == Team.AWAY) state.awayBatterIndex else state.homeBatterIndex
    var selectedId by remember(currentIndex, team) {
        mutableStateOf(currentIndex.takeIf { it in lineup.indices }?.let { lineup[it].id })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Current batter — ${state.teamName(team)}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (allowUnknown) {
                    FilterChip(
                        selected = selectedId == null,
                        onClick = { selectedId = null },
                        label = { Text("Unknown — choose later") }
                    )
                }
                lineup.forEachIndexed { index, player ->
                    FilterChip(
                        selected = selectedId == player.id,
                        onClick = { selectedId = player.id },
                        label = { Text("${index + 1}. ${player.name}") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedId) },
                enabled = allowUnknown || selectedId != null
            ) { Text("Use Batter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun MissingCurrentBatterCard(
    state: GameState,
    onSetCurrentBatter: (Team, Int?) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Current batter is unknown", fontWeight = FontWeight.Bold)
            Text(
                "Choose the batter for ${state.teamName(state.activeTeam)} before recording the next pitch or play. This is useful when you joined the game mid-inning.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Select Current Batter")
            }
        }
    }
    if (showPicker) {
        CurrentBatterPickerDialog(
            state = state,
            team = state.activeTeam,
            allowUnknown = false,
            onDismiss = { showPicker = false },
            onConfirm = { playerId ->
                showPicker = false
                onSetCurrentBatter(state.activeTeam, playerId)
            }
        )
    }
}

@Composable
private fun CurrentGameSituationCard(
    state: GameState,
    enabled: Boolean,
    onEditGameSituation: (GameSituationUpdate) -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }
    val awayBatter = state.awayBatterIndex.takeIf { it in state.lineupAway.indices }?.let { state.lineupAway[it].name }
    val homeBatter = state.homeBatterIndex.takeIf { it in state.lineupHome.indices }?.let { state.lineupHome[it].name }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Current Game State", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${if (state.topOfInning) "Top" else "Bottom"} ${state.currentInning} • ${state.awayTeamName} ${state.totalRuns(Team.AWAY)} – ${state.totalRuns(Team.HOME)} ${state.homeTeamName}",
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedButton(onClick = { showEditor = true }, enabled = enabled) { Text("Edit") }
            }
            Text("Count ${state.balls}-${state.strikes}, ${state.outs} out${if (state.outs == 1) "" else "s"}")
            if (state.trackingStartedMidGame && ((state.carryInRuns[Team.AWAY] ?: 0) > 0 || (state.carryInRuns[Team.HOME] ?: 0) > 0)) {
                Text(
                    "Score before tracking: ${state.awayTeamName} ${state.carryInRuns[Team.AWAY] ?: 0} • ${state.homeTeamName} ${state.carryInRuns[Team.HOME] ?: 0}. Inning distribution is intentionally left unknown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text(
                "Away batter: ${awayBatter ?: "Unknown"} • Home batter: ${homeBatter ?: "Unknown"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (enabled && (awayBatter == null || homeBatter == null)) {
                Text(
                    "Unknown is allowed. InningTrack can keep scoring under a temporary Unknown identity; when you identify the player later, those recorded stats can be reassigned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    if (showEditor) {
        GameSituationDialog(
            state = state,
            onDismiss = { showEditor = false },
            onConfirm = { update ->
                showEditor = false
                onEditGameSituation(update)
            }
        )
    }
}

@Composable
private fun GameSituationDialog(
    state: GameState,
    onDismiss: () -> Unit,
    onConfirm: (GameSituationUpdate) -> Unit
) {
    var inningText by remember(state.currentInning) { mutableStateOf(state.currentInning.toString()) }
    var top by remember(state.topOfInning) { mutableStateOf(state.topOfInning) }
    var ballsText by remember(state.balls) { mutableStateOf(state.balls.toString()) }
    var strikesText by remember(state.strikes) { mutableStateOf(state.strikes.toString()) }
    var outsText by remember(state.outs) { mutableStateOf(state.outs.toString()) }
    var awayTotalText by remember(state.scores) { mutableStateOf(state.totalRuns(Team.AWAY).toString()) }
    var homeTotalText by remember(state.scores) { mutableStateOf(state.totalRuns(Team.HOME).toString()) }
    var awayBatterId by remember(state.awayBatterIndex, state.lineupAway) {
        mutableStateOf(state.awayBatterIndex.takeIf { it in state.lineupAway.indices }?.let { state.lineupAway[it].id })
    }
    var homeBatterId by remember(state.homeBatterIndex, state.lineupHome) {
        mutableStateOf(state.homeBatterIndex.takeIf { it in state.lineupHome.indices }?.let { state.lineupHome[it].id })
    }
    var firstRunnerId by remember(state.bases) { mutableStateOf(state.bases.first?.playerId) }
    var secondRunnerId by remember(state.bases) { mutableStateOf(state.bases.second?.playerId) }
    var thirdRunnerId by remember(state.bases) { mutableStateOf(state.bases.third?.playerId) }

    val activeLineup = if (top) state.lineupAway else state.lineupHome
    LaunchedEffect(top) {
        val validIds = activeLineup.map(Player::id).toSet()
        if (firstRunnerId != null && firstRunnerId !in validIds) firstRunnerId = null
        if (secondRunnerId != null && secondRunnerId !in validIds) secondRunnerId = null
        if (thirdRunnerId != null && thirdRunnerId !in validIds) thirdRunnerId = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter / Correct Current Game State") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Fill only what you know. Batters and runners can remain Unknown; InningTrack will track them with temporary identities that you can assign to real players later.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = inningText,
                        onValueChange = { inningText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Inning") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(selected = top, onClick = { top = true }, label = { Text("Top") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = !top, onClick = { top = false }, label = { Text("Bottom") }, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumberField(state.awayTeamName, awayTotalText, { awayTotalText = it }, 3, Modifier.weight(1f))
                    CompactNumberField(state.homeTeamName, homeTotalText, { homeTotalText = it }, 3, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactNumberField("Balls", ballsText, { ballsText = it }, 1, Modifier.weight(1f))
                    CompactNumberField("Strikes", strikesText, { strikesText = it }, 1, Modifier.weight(1f))
                    CompactNumberField("Outs", outsText, { outsText = it }, 1, Modifier.weight(1f))
                }
                CurrentBatterDropdown("${state.awayTeamName} batter", state.lineupAway, awayBatterId) { awayBatterId = it }
                CurrentBatterDropdown("${state.homeTeamName} batter", state.lineupHome, homeBatterId) { homeBatterId = it }
                HorizontalDivider()
                Text("Baserunners (${if (top) state.awayTeamName else state.homeTeamName} batting)", fontWeight = FontWeight.SemiBold)
                RunnerSelectionDropdown("1B", activeLineup, firstRunnerId) { firstRunnerId = it }
                RunnerSelectionDropdown("2B", activeLineup, secondRunnerId) { secondRunnerId = it }
                RunnerSelectionDropdown("3B", activeLineup, thirdRunnerId) { thirdRunnerId = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                val inning = inningText.toIntOrNull()?.coerceAtLeast(1) ?: state.currentInning
                val awayTotal = awayTotalText.toIntOrNull()?.coerceAtLeast(0) ?: state.totalRuns(Team.AWAY)
                val homeTotal = homeTotalText.toIntOrNull()?.coerceAtLeast(0) ?: state.totalRuns(Team.HOME)
                onConfirm(
                    GameSituationUpdate(
                        inning = inning,
                        topOfInning = top,
                        balls = ballsText.toIntOrNull()?.coerceIn(0, 3) ?: 0,
                        strikes = strikesText.toIntOrNull()?.coerceIn(0, 2) ?: 0,
                        outs = outsText.toIntOrNull()?.coerceIn(0, 2) ?: 0,
                        awayScore = awayTotal,
                        homeScore = homeTotal,
                        awayBatterId = awayBatterId,
                        homeBatterId = homeBatterId,
                        firstRunnerId = firstRunnerId,
                        secondRunnerId = secondRunnerId,
                        thirdRunnerId = thirdRunnerId
                    )
                )
            }) { Text("Save Current State") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun adjustScoresToTotal(existing: List<Int>, inning: Int, targetTotal: Int): List<Int> {
    val size = maxOf(existing.size, inning, 1)
    val result = MutableList(size) { index -> existing.getOrNull(index) ?: 0 }
    val currentIndex = (inning - 1).coerceIn(0, result.lastIndex)
    val otherTotal = result.filterIndexed { index, _ -> index != currentIndex }.sum()
    if (otherTotal <= targetTotal) {
        result[currentIndex] = targetTotal - otherTotal
    } else {
        result.indices.forEach { result[it] = 0 }
        result[currentIndex] = targetTotal
    }
    return result
}

@Composable
private fun CurrentBatterDropdown(
    label: String,
    lineup: List<Player>,
    selectedPlayerId: Int?,
    onSelected: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = lineup.firstOrNull { it.id == selectedPlayerId }?.name ?: "Unknown"
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $selectedName")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Unknown — choose later") }, onClick = {
                expanded = false
                onSelected(null)
            })
            lineup.forEachIndexed { index, player ->
                DropdownMenuItem(text = { Text("${index + 1}. ${player.name}") }, onClick = {
                    expanded = false
                    onSelected(player.id)
                })
            }
        }
    }
}

@Composable
private fun LiveStatsScreen(state: GameState) {
    val battingTeam = state.activeTeam
    val pitchingTeam = if (battingTeam == Team.AWAY) Team.HOME else Team.AWAY
    val battingStats = remember(state.events, state.lineupAway, state.lineupHome, battingTeam) {
        GameStats.batting(state, battingTeam)
    }
    val pitchingStats = remember(state.events, state.awayPitcherName, state.homePitcherName, pitchingTeam) {
        GameStats.pitching(state, pitchingTeam)
    }
    val currentBatter = LineupEngine.currentBatter(state)
    val currentBatterStats = currentBatter?.let { batter ->
        battingStats.firstOrNull { it.playerId == batter.id }
            ?: BattingStats(playerId = batter.id, playerName = batter.name)
    }
    val currentPitcherName = state.pitcherName(pitchingTeam)
    val currentPitcherStats = pitchingStats.lastOrNull { it.pitcherName == currentPitcherName }
        ?: pitchingStats.lastOrNull()

    ScoreboardCard(state)

    FullGameStatsSummary(state)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Pitching — ${state.teamName(pitchingTeam)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Current pitcher: ${currentPitcherStats?.pitcherName ?: currentPitcherName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            currentPitcherStats?.let { stat ->
                CompactStatGrid(
                    listOf(
                        "IP" to stat.inningsPitchedDisplay,
                        "Pitches" to stat.pitches.toString(),
                        "H" to stat.hitsAllowed.toString(),
                        "R" to stat.runsAllowed.toString(),
                        "ER" to stat.earnedRuns.toString(),
                        "BB" to stat.walks.toString(),
                        "SO" to stat.strikeouts.toString(),
                        "ERA" to formatRate(stat.era),
                        "WHIP" to formatRate(stat.whip)
                    )
                )
            }
            PitchingStatsTable(pitchingStats)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Batting — ${state.teamName(battingTeam)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            BattingStatsTable(battingStats)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Current Batter", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (currentBatterStats == null) {
                Text(
                    "No current batter is selected.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(currentBatterStats.playerName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                CompactStatGrid(
                    listOf(
                        "PA" to currentBatterStats.plateAppearances.toString(),
                        "AB" to currentBatterStats.atBats.toString(),
                        "R" to currentBatterStats.runs.toString(),
                        "H" to currentBatterStats.hits.toString(),
                        "RBI" to currentBatterStats.rbi.toString(),
                        "BB" to currentBatterStats.walks.toString(),
                        "SO" to currentBatterStats.strikeouts.toString(),
                        "AVG" to formatRate(currentBatterStats.average),
                        "OBP" to formatRate(currentBatterStats.onBasePercentage),
                        "SLG" to formatRate(currentBatterStats.slugging),
                        "OPS" to formatRate(currentBatterStats.ops)
                    )
                )
            }
        }
    }

    if (state.trackingStartedMidGame) {
        Text(
            "Stats include only play recorded after InningTrack began tracking this game; earlier activity is not estimated.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun FullGameStatsSummary(state: GameState) {
    val awayBatting = remember(state.events, state.lineupAway) { GameStats.batting(state, Team.AWAY) }
    val homeBatting = remember(state.events, state.lineupHome) { GameStats.batting(state, Team.HOME) }

    fun teamTotals(team: Team, batting: List<BattingStats>): List<String> = listOf(
        state.teamName(team),
        state.totalRuns(team).toString(),
        (state.hits[team] ?: 0).toString(),
        (state.errors[team] ?: 0).toString(),
        batting.sumOf { it.plateAppearances }.toString(),
        batting.sumOf { it.atBats }.toString(),
        batting.sumOf { it.walks }.toString(),
        batting.sumOf { it.strikeouts }.toString()
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Full Game Stats", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${if (state.topOfInning) "Top" else "Bottom"} ${state.currentInning} • ${state.outs} out${if (state.outs == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                StatsRow(listOf("Team", "R", "H", "E", "PA", "AB", "BB", "SO"), header = true)
                StatsRow(teamTotals(Team.AWAY, awayBatting))
                StatsRow(teamTotals(Team.HOME, homeBatting))
            }
        }
    }
}

@Composable
private fun CompactStatGrid(stats: List<Pair<String, String>>) {
    stats.chunked(4).forEach { rowStats ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rowStats.forEach { (label, value) ->
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
            repeat(4 - rowStats.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun GameStatsCard(
    state: GameState,
    onResolveUnknownPlayer: ((Team, Int, Int) -> Unit)? = null
) {
    var team by remember { mutableStateOf(Team.AWAY) }
    var statView by remember { mutableIntStateOf(0) } // 0 batting, 1 pitching, 2 fielding
    val battingStats = remember(state.events, state.lineupAway, state.lineupHome, team) { GameStats.batting(state, team) }
    val pitchingStats = remember(state.events, state.awayPitcherName, state.homePitcherName, team) { GameStats.pitching(state, team) }
    val fieldingStats = remember(state.events, state.playerPositions, team) { GameStats.fielding(state, team) }
    val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
    val unresolved = battingStats.filter { it.playerId < 0 }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Game Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = team == Team.AWAY, onClick = { team = Team.AWAY }, label = { Text(state.awayTeamName) })
                FilterChip(selected = team == Team.HOME, onClick = { team = Team.HOME }, label = { Text(state.homeTeamName) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = statView == 0, onClick = { statView = 0 }, label = { Text("Batting") })
                FilterChip(selected = statView == 1, onClick = { statView = 1 }, label = { Text("Pitching") })
                FilterChip(selected = statView == 2, onClick = { statView = 2 }, label = { Text("Fielding") })
            }
            Text(
                when (statView) {
                    1 -> "Pitching includes inherited runners, earned runs, ERA, WHIP, wild pitches and balks when those details were recorded."
                    2 -> "Fielding credits come from optional putout/assist/error details entered while scoring, plus passed balls."
                    else -> "Batting includes rate stats plus sacrifices, steals, caught stealing and double plays when recorded."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.trackingStartedMidGame) {
                Text(
                    "Stats reflect play recorded in InningTrack from the point tracking began; earlier unknown game activity is not guessed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (statView == 0 && unresolved.isNotEmpty() && onResolveUnknownPlayer != null) {
                HorizontalDivider()
                Text("Identify Unknown Players", fontWeight = FontWeight.SemiBold)
                Text(
                    "Assign an Unknown identity later and its recorded events and stats move to the selected real player.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                unresolved.forEachIndexed { index, stat ->
                    UnknownStatsAssignmentRow(
                        label = "Unknown ${index + 1}",
                        stat = stat,
                        lineup = lineup,
                        onAssign = { playerId -> onResolveUnknownPlayer?.invoke(team, stat.playerId, playerId) }
                    )
                }
                HorizontalDivider()
            }
            when (statView) {
                1 -> PitchingStatsTable(pitchingStats)
                2 -> FieldingStatsTable(fieldingStats)
                else -> BattingStatsTable(battingStats)
            }
        }
    }
}

@Composable
private fun UnknownStatsAssignmentRow(
    label: String,
    stat: BattingStats,
    lineup: List<Player>,
    onAssign: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text(
                "${stat.plateAppearances} PA • ${stat.hits} H • ${stat.runs} R • ${stat.rbi} RBI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("Assign") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                lineup.forEachIndexed { index, player ->
                    DropdownMenuItem(
                        text = { Text("${index + 1}. ${player.name}") },
                        onClick = {
                            expanded = false
                            onAssign(player.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BattingStatsTable(stats: List<BattingStats>) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scroll)) {
        StatsRow(
            listOf("Player", "PA", "AB", "R", "H", "2B", "3B", "HR", "RBI", "BB", "IBB", "HBP", "SO", "SH", "SF", "SB", "CS", "GIDP", "AVG", "OBP", "SLG", "OPS"),
            header = true
        )
        stats.forEach { s ->
            StatsRow(
                listOf(
                    s.playerName, s.plateAppearances, s.atBats, s.runs, s.hits, s.doubles, s.triples,
                    s.homeRuns, s.rbi, s.walks, s.intentionalWalks, s.hitByPitch, s.strikeouts,
                    s.sacrificeBunts, s.sacrificeFlies, s.stolenBases, s.caughtStealing, s.groundedIntoDoublePlay,
                    formatRate(s.average), formatRate(s.onBasePercentage), formatRate(s.slugging), formatRate(s.ops)
                ).map(Any::toString)
            )
        }
    }
}

@Composable
private fun PitchingStatsTable(stats: List<PitchingStats>) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scroll)) {
        StatsRow(listOf("Pitcher", "IP", "BF", "P", "B", "S", "H", "R", "ER", "BB", "IBB", "SO", "HBP", "HR", "WP", "BK", "IR", "IRS", "ERA", "WHIP", "S%"), header = true)
        stats.forEach { s ->
            StatsRow(
                listOf(
                    s.pitcherName, s.inningsPitchedDisplay, s.battersFaced, s.pitches, s.balls, s.strikes,
                    s.hitsAllowed, s.runsAllowed, s.earnedRuns, s.walks, s.intentionalWalks, s.strikeouts,
                    s.hitBatters, s.homeRunsAllowed, s.wildPitches, s.balks, s.inheritedRunners,
                    s.inheritedRunnersScored, formatRate(s.era), formatRate(s.whip), formatRate(s.strikePercentage)
                ).map(Any::toString)
            )
        }
    }
}

@Composable
private fun FieldingStatsTable(stats: List<FieldingStats>) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scroll)) {
        StatsRow(listOf("Player", "PO", "A", "E", "DP", "TP", "PB"), header = true)
        stats.forEach { s ->
            StatsRow(listOf(s.playerName, s.putouts.toString(), s.assists.toString(), s.errors.toString(), s.doublePlays.toString(), s.triplePlays.toString(), s.passedBalls.toString()))
        }
        if (stats.isEmpty()) {
            Text("No individual fielding credits recorded yet.", modifier = Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatsRow(values: List<String>, header: Boolean = false) {
    Row {
        values.forEachIndexed { index, value ->
            Box(
                modifier = Modifier
                    .width(if (index == 0) 136.dp else 54.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    .padding(horizontal = 5.dp, vertical = 6.dp),
                contentAlignment = if (index == 0) Alignment.CenterStart else Alignment.Center
            ) {
                Text(value, fontSize = 11.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

private fun formatRate(value: Double?): String = value?.let { String.format(Locale.US, "%.3f", it).removePrefix("0") } ?: "—"
