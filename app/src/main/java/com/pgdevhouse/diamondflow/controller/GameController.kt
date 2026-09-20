package com.pgdevhouse.diamondflow.controller

import com.pgdevhouse.diamondflow.engine.InningEngine
import com.pgdevhouse.diamondflow.engine.LineupEngine
import com.pgdevhouse.diamondflow.engine.ScoreEngine
import com.pgdevhouse.diamondflow.engine.RunnerEngine
import com.pgdevhouse.diamondflow.logic.GameEngine
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.BaseRunningAction
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.GameEvent
import com.pgdevhouse.diamondflow.model.GameEventType
import com.pgdevhouse.diamondflow.model.GameSnapshot
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.GameStats
import com.pgdevhouse.diamondflow.model.FieldingPlay
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Runner
import com.pgdevhouse.diamondflow.model.ScoredRun
import com.pgdevhouse.diamondflow.model.Team
import java.util.ArrayDeque

class GameController(
    initialState: GameState = defaultState()
) {

    private val engine = GameEngine()
    private val history = ArrayDeque<GameState>()
    private val redoHistory = ArrayDeque<GameState>()

    var state: GameState = initialState
        private set

    val snapshot: GameSnapshot
        get() = GameSnapshot.from(state)

    val canUndo: Boolean
        get() = history.isNotEmpty()

    val canRedo: Boolean
        get() = redoHistory.isNotEmpty()

    fun ball() = pitch(PitchAction.BALL)
    fun strike() = pitch(PitchAction.STRIKE)
    fun foul() = pitch(PitchAction.FOUL)
    fun hitByPitch() = pitch(PitchAction.HIT_BY_PITCH)

    fun removeCurrentBall(): Boolean = removeCurrentCountPitch(
        matches = { it == PitchAction.BALL },
        updateCount = { current -> current.copy(balls = (current.balls - 1).coerceAtLeast(0)) }
    )

    fun removeCurrentStrike(): Boolean = removeCurrentCountPitch(
        matches = { it == PitchAction.STRIKE || it == PitchAction.FOUL },
        updateCount = { current -> current.copy(strikes = (current.strikes - 1).coerceAtLeast(0)) }
    )

    /**
     * Removes one recorded pitch from a completed plate appearance without changing
     * the already-recorded result of that plate appearance. If the selected pitch
     * itself carried the result (Ball 4, Strike 3, HBP), it is converted to a normal
     * PLAY event so the walk/strikeout/HBP remains in the scorebook while the pitch
     * count is corrected.
     */
    fun removeRecordedPitch(eventId: Long): Boolean {
        val original = state.events.firstOrNull { it.id == eventId } ?: return false
        if (original.type != GameEventType.PITCH || original.pitchAction == null) return false

        mutateCorrection { current ->
            val updatedEvents = current.events.mapNotNull { event ->
                if (event.id != eventId) return@mapNotNull event
                if (event.playAction == null) {
                    null
                } else {
                    event.copy(
                        type = GameEventType.PLAY,
                        title = playLabel(event.playAction),
                        pitchAction = null
                    )
                }
            }
            renumberEvents(current, updatedEvents)
        }
        return true
    }

    /** Adds a pitch-only correction immediately before a completed plate appearance. */
    fun addPitchToRecordedAtBat(plateAppearanceEndEventId: Long, action: PitchAction): Boolean {
        if (action !in setOf(PitchAction.BALL, PitchAction.STRIKE, PitchAction.FOUL, PitchAction.IN_PLAY)) return false
        val endIndex = state.events.indexOfFirst { it.id == plateAppearanceEndEventId }
        if (endIndex < 0) return false
        val endEvent = state.events[endIndex]
        if (!isPlateAppearanceCompletion(endEvent)) return false

        mutateCorrection { current ->
            val currentEndIndex = current.events.indexOfFirst { it.id == plateAppearanceEndEventId }
            if (currentEndIndex < 0) return@mutateCorrection current
            val currentEnd = current.events[currentEndIndex]
            val added = GameEvent(
                id = 0,
                inning = currentEnd.inning,
                topOfInning = currentEnd.topOfInning,
                battingTeam = currentEnd.battingTeam,
                type = GameEventType.PITCH,
                title = when (action) {
                    PitchAction.BALL -> "Ball"
                    PitchAction.STRIKE -> "Strike"
                    PitchAction.FOUL -> "Foul"
                    PitchAction.IN_PLAY -> "In play"
                    PitchAction.HIT_BY_PITCH -> "Hit by pitch"
                },
                detail = "Pitch-history correction • Batter: ${currentEnd.actorName ?: "Unknown"}",
                actorPlayerId = currentEnd.actorPlayerId,
                actorName = currentEnd.actorName,
                team = currentEnd.battingTeam,
                pitcherName = currentEnd.pitcherName,
                pitchAction = action
            )
            val updated = current.events.toMutableList().apply { add(currentEndIndex, added) }
            renumberEvents(current, updated)
        }
        return true
    }

    fun single() = play(PlayAction.SINGLE)
    fun double() = play(PlayAction.DOUBLE)
    fun triple() = play(PlayAction.TRIPLE)
    fun homeRun() = play(PlayAction.HOME_RUN)
    fun strikeout() = play(PlayAction.STRIKEOUT)
    fun groundOut() = play(PlayAction.GROUND_OUT)
    fun flyOut() = play(PlayAction.FLY_OUT)
    fun error() = play(PlayAction.ERROR)
    fun fieldersChoice() = play(PlayAction.FIELDERS_CHOICE)
    fun sacrifice() = play(PlayAction.SACRIFICE)
    fun intentionalWalk() = play(PlayAction.INTENTIONAL_WALK)
    fun sacrificeBunt() = play(PlayAction.SACRIFICE_BUNT)
    fun sacrificeFly() = play(PlayAction.SACRIFICE_FLY)
    fun doublePlay() = play(PlayAction.DOUBLE_PLAY)
    fun triplePlay() = play(PlayAction.TRIPLE_PLAY)

    private fun pitch(action: PitchAction) {
        if (state.gameOver) return
        val batterId = LineupEngine.currentBatter(state)?.id ?: return
        val previous = state
        var next = engine.applyPitch(previous, action, batterId)
        if (next.gameOver && !previous.gameOver && next.endedAtEpochMillis == null) {
            next = next.copy(endedAtEpochMillis = System.currentTimeMillis())
        }
        val event = pitchEvent(previous, next, action)
        next = next.copy(
            events = next.events + event.copy(id = next.nextEventId),
            nextEventId = next.nextEventId + 1
        )
        history.addLast(previous)
        while (history.size > MAX_UNDO_STATES) {
            history.removeFirst()
        }
        redoHistory.clear()
        state = next
    }

    fun play(
        action: PlayAction,
        manualRunnerDestinations: Map<Int, Base?> = emptyMap(),
        outsRecorded: Int = action.defaultOuts,
        fielding: FieldingPlay? = null
    ) {
        if (LineupEngine.currentBatter(state) == null) return
        mutateWithEvents(
            eventBuilder = { previous, next ->
                listOfNotNull(
                    terminalPitchForPlay(action)?.let { pitchAction -> outcomePitchEvent(previous, pitchAction) },
                    playEvent(previous, next, action, outsRecorded, manualRunnerDestinations, fielding)
                )
            }
        ) { current ->
            engine.applyPlay(
                state = current,
                play = Play(
                    action = action,
                    fielding = fielding,
                    manualRunnerDestinations = manualRunnerDestinations,
                    outsRecorded = outsRecorded
                )
            )
        }
    }

    fun setPitchCount(team: Team, pitcherName: String, targetCount: Int): Boolean {
        val normalizedName = pitcherName.trim()
        if (normalizedName.isBlank()) return false
        val target = targetCount.coerceIn(0, 999)
        val currentCount = GameStats.pitching(state, team)
            .lastOrNull { it.pitcherName == normalizedName }
            ?.pitches
            ?: 0
        val adjustment = target - currentCount
        if (adjustment == 0) return false

        val battingTeam = opposite(team)
        mutateCorrection { current ->
            val event = GameEvent(
                id = current.nextEventId,
                inning = current.currentInning,
                topOfInning = current.topOfInning,
                battingTeam = battingTeam,
                type = GameEventType.GAME_CORRECTION,
                title = "Pitch count corrected",
                detail = "$normalizedName: $currentCount → $target pitches",
                team = team,
                pitcherName = normalizedName,
                pitchCountAdjustment = adjustment
            )
            current.copy(
                events = current.events + event,
                nextEventId = current.nextEventId + 1
            )
        }
        return true
    }

    fun recordBaseRunningEvent(
        action: BaseRunningAction,
        runnerId: Int?,
        destination: Base? = null,
        fielderName: String? = null
    ) {
        val existingRunner = runnerId?.let { id ->
            listOfNotNull(state.bases.first, state.bases.second, state.bases.third)
                .firstOrNull { it.playerId == id }
        }
        val runnerRequired = action in setOf(
            BaseRunningAction.STOLEN_BASE,
            BaseRunningAction.CAUGHT_STEALING,
            BaseRunningAction.PICKOFF,
            BaseRunningAction.RUNNER_OUT,
            BaseRunningAction.DEFENSIVE_INDIFFERENCE,
            BaseRunningAction.RUNNER_ADVANCE
        )
        if (runnerRequired && existingRunner == null) return
        val from = existingRunner?.base
        val resolvedDestination = when (action) {
            BaseRunningAction.CAUGHT_STEALING,
            BaseRunningAction.PICKOFF,
            BaseRunningAction.RUNNER_OUT -> null
            else -> destination ?: from?.let(::nextBase)
        }
        if (existingRunner != null && resolvedDestination == from) return

        mutateWithEvent(
            eventBuilder = { previous, _ ->
                val fieldingTeam = opposite(previous.activeTeam)
                val scored = if (existingRunner != null && resolvedDestination == Base.HOME) {
                    listOf(
                        ScoredRun(
                            playerId = existingRunner.playerId,
                            responsiblePitcherName = existingRunner.responsiblePitcherName,
                            earned = existingRunner.earnedRunEligible
                        )
                    )
                } else emptyList()
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.BASERUNNING,
                    title = baseRunningLabel(action),
                    detail = buildString {
                        if (existingRunner != null) {
                            append(previous.playerName(existingRunner.playerId) ?: "Unknown")
                            append(": ${from?.let(::baseLabel) ?: "base"}")
                            if (resolvedDestination != null) append(" → ${baseLabel(resolvedDestination)}")
                            if (action == BaseRunningAction.CAUGHT_STEALING || action == BaseRunningAction.PICKOFF || action == BaseRunningAction.RUNNER_OUT) append(" • out")
                        } else {
                            append("No runner advanced")
                        }
                    },
                    actorPlayerId = existingRunner?.playerId,
                    actorName = existingRunner?.let { previous.playerName(it.playerId) ?: "Unknown" },
                    team = previous.activeTeam,
                    pitcherName = previous.pitcherName(fieldingTeam),
                    baseRunningAction = action,
                    fromBase = from,
                    toBase = resolvedDestination,
                    outsRecorded = if (existingRunner != null && (action == BaseRunningAction.CAUGHT_STEALING || action == BaseRunningAction.PICKOFF || action == BaseRunningAction.RUNNER_OUT)) 1 else 0,
                    runsScored = scored.size,
                    scoredPlayerIds = scored.map(ScoredRun::playerId),
                    scoredRuns = scored,
                    putoutPlayerName = fielderName?.takeIf(String::isNotBlank),
                    errorPlayerName = if (action == BaseRunningAction.PASSED_BALL) {
                        fielderAtPosition(previous, fieldingTeam, "C")
                    } else null
                )
            }
        ) { current ->
            val currentRunner = runnerId?.let { id ->
                listOfNotNull(current.bases.first, current.bases.second, current.bases.third)
                    .firstOrNull { it.playerId == id }
            }
            var updated = current
            if (currentRunner != null) {
                updated = current.copy(bases = removeRunner(current.bases, currentRunner.playerId))
                when {
                    resolvedDestination == Base.HOME -> updated = ScoreEngine.apply(updated, 1)
                    resolvedDestination != null -> updated = updated.copy(
                        bases = placeRunner(updated.bases, currentRunner.copy(base = resolvedDestination))
                    )
                }
                if (action == BaseRunningAction.CAUGHT_STEALING || action == BaseRunningAction.PICKOFF || action == BaseRunningAction.RUNNER_OUT) {
                    updated = updated.copy(outs = (updated.outs + 1).coerceAtMost(3))
                }
            }
            InningEngine.apply(updated)
        }
    }

    fun setCurrentBatter(team: Team, playerId: Int?) {
        val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
        val targetIndex = playerId?.let { id -> lineup.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: -1
        val oldIndex = if (team == Team.AWAY) state.awayBatterIndex else state.homeBatterIndex
        val oldUnknownId = if (team == Team.AWAY) state.awayUnknownBatterId else state.homeUnknownBatterId
        if (playerId != null && targetIndex < 0) return
        if (playerId != null && targetIndex == oldIndex && oldUnknownId == null) return
        if (playerId == null && oldIndex < 0 && oldUnknownId != null) return

        val targetName = playerId?.let { id -> lineup.firstOrNull { it.id == id }?.name }
        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.GAME_STATE_EDIT,
                    title = if (oldUnknownId != null && playerId != null) "Unknown player identified" else "Current batter set",
                    detail = when {
                        oldUnknownId != null && targetName != null -> "${previous.teamName(team)}: Unknown → $targetName; recorded stats reassigned"
                        playerId == null -> "${previous.teamName(team)} batter marked Unknown"
                        else -> "${previous.teamName(team)}: ${targetName ?: "Batter"}"
                    },
                    actorPlayerId = playerId,
                    actorName = targetName,
                    replacedPlayerId = oldUnknownId,
                    replacedPlayerName = oldUnknownId?.let { "Unknown" },
                    team = team
                )
            }
        ) { current ->
            var updated = current
            if (oldUnknownId != null && playerId != null) {
                updated = resolveUnknownIdentity(updated, oldUnknownId, playerId, targetName ?: "Player")
            }
            if (playerId == null) {
                val existingUnknown = if (team == Team.AWAY) updated.awayUnknownBatterId else updated.homeUnknownBatterId
                val unknownId = existingUnknown ?: updated.nextUnknownPlayerId
                val nextUnknown = if (existingUnknown == null) updated.nextUnknownPlayerId - 1 else updated.nextUnknownPlayerId
                updated = when (team) {
                    Team.AWAY -> updated.copy(awayBatterIndex = -1, awayUnknownBatterId = unknownId, nextUnknownPlayerId = nextUnknown)
                    Team.HOME -> updated.copy(homeBatterIndex = -1, homeUnknownBatterId = unknownId, nextUnknownPlayerId = nextUnknown)
                }
            } else {
                updated = when (team) {
                    Team.AWAY -> updated.copy(awayBatterIndex = targetIndex, awayUnknownBatterId = null)
                    Team.HOME -> updated.copy(homeBatterIndex = targetIndex, homeUnknownBatterId = null)
                }
            }
            updated
        }
    }

    fun resolveUnknownPlayer(team: Team, unknownPlayerId: Int, playerId: Int) {
        if (unknownPlayerId >= 0) return
        val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
        val player = lineup.firstOrNull { it.id == playerId } ?: return
        val hasUnknownData = state.events.any {
            it.actorPlayerId == unknownPlayerId ||
                it.replacedPlayerId == unknownPlayerId ||
                unknownPlayerId in it.scoredPlayerIds
        } || listOf(state.bases.first, state.bases.second, state.bases.third).any { it?.playerId == unknownPlayerId } ||
            state.awayUnknownBatterId == unknownPlayerId || state.homeUnknownBatterId == unknownPlayerId
        if (!hasUnknownData) return

        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.GAME_STATE_EDIT,
                    title = "Unknown player identified",
                    detail = "${previous.teamName(team)}: Unknown → ${player.name}; recorded stats reassigned",
                    actorPlayerId = player.id,
                    actorName = player.name,
                    replacedPlayerId = unknownPlayerId,
                    replacedPlayerName = "Unknown",
                    team = team
                )
            }
        ) { current ->
            var updated = resolveUnknownIdentity(current, unknownPlayerId, player.id, player.name)
            if (team == Team.AWAY && current.awayUnknownBatterId == unknownPlayerId) {
                updated = updated.copy(awayBatterIndex = lineup.indexOfFirst { it.id == player.id }, awayUnknownBatterId = null)
            }
            if (team == Team.HOME && current.homeUnknownBatterId == unknownPlayerId) {
                updated = updated.copy(homeBatterIndex = lineup.indexOfFirst { it.id == player.id }, homeUnknownBatterId = null)
            }
            updated
        }
    }

    /**
     * Current-game editor used by the mid-game workflow. Scores are totals, not
     * inning-by-inning guesses. Any difference between the entered total and
     * the runs InningTrack has actually recorded is kept as carry-in runs.
     */
    fun setGameSituation(
        inning: Int,
        topOfInning: Boolean,
        balls: Int,
        strikes: Int,
        outs: Int,
        awayScore: Int,
        homeScore: Int,
        awayBatterId: Int?,
        homeBatterId: Int?,
        firstRunnerId: Int?,
        secondRunnerId: Int?,
        thirdRunnerId: Int?
    ) {
        val targetInning = inning.coerceAtLeast(1)
        val activeTeam = if (topOfInning) Team.AWAY else Team.HOME
        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.GAME_STATE_EDIT,
                    title = "Current game state corrected",
                    detail = "${if (topOfInning) "Top" else "Bottom"} $targetInning • ${awayScore.coerceAtLeast(0)}-${homeScore.coerceAtLeast(0)}"
                )
            }
        ) { current ->
            var updated = ensureScoreSize(current, targetInning)
            updated = applyBatterSelection(updated, Team.AWAY, awayBatterId)
            updated = applyBatterSelection(updated, Team.HOME, homeBatterId)

            val activeLineupIds = (if (activeTeam == Team.AWAY) updated.lineupAway else updated.lineupHome)
                .map(Player::id)
                .toSet()
            val first = resolveRunnerChoice(updated, updated.bases.first, firstRunnerId, Base.FIRST, activeLineupIds)
            updated = first.first
            val second = resolveRunnerChoice(updated, updated.bases.second, secondRunnerId, Base.SECOND, activeLineupIds)
            updated = second.first
            val third = resolveRunnerChoice(updated, updated.bases.third, thirdRunnerId, Base.THIRD, activeLineupIds)
            updated = third.first

            val awayAdjusted = scoreStateForTotal(updated.scores[Team.AWAY].orEmpty(), awayScore.coerceAtLeast(0))
            val homeAdjusted = scoreStateForTotal(updated.scores[Team.HOME].orEmpty(), homeScore.coerceAtLeast(0))
            updated.copy(
                currentInning = targetInning,
                topOfInning = topOfInning,
                activeTeam = activeTeam,
                balls = balls.coerceIn(0, 3),
                strikes = strikes.coerceIn(0, 2),
                outs = outs.coerceIn(0, 2),
                scores = mapOf(
                    Team.AWAY to awayAdjusted.first,
                    Team.HOME to homeAdjusted.first
                ),
                carryInRuns = mapOf(
                    Team.AWAY to awayAdjusted.second,
                    Team.HOME to homeAdjusted.second
                ),
                trackingStartedMidGame = true,
                bases = Bases(first = first.second, second = second.second, third = third.second)
            )
        }
    }

    /** Backward-compatible overload for older callers/tests that already know inning scores. */
    fun setGameSituation(
        inning: Int,
        topOfInning: Boolean,
        balls: Int,
        strikes: Int,
        outs: Int,
        awayScores: List<Int>,
        homeScores: List<Int>,
        awayBatterId: Int?,
        homeBatterId: Int?,
        firstRunnerId: Int?,
        secondRunnerId: Int?,
        thirdRunnerId: Int?
    ) {
        val targetInning = inning.coerceAtLeast(1)
        val neededSize = maxOf(state.maxInnings, targetInning)
        fun normalized(scores: List<Int>): List<Int> = scores.map { it.coerceAtLeast(0) }.toMutableList().apply {
            while (size < neededSize) add(0)
        }
        val away = normalized(awayScores)
        val home = normalized(homeScores)
        setGameSituation(
            inning = targetInning,
            topOfInning = topOfInning,
            balls = balls,
            strikes = strikes,
            outs = outs,
            awayScore = away.sum(),
            homeScore = home.sum(),
            awayBatterId = awayBatterId,
            homeBatterId = homeBatterId,
            firstRunnerId = firstRunnerId,
            secondRunnerId = secondRunnerId,
            thirdRunnerId = thirdRunnerId
        )
        // Preserve explicitly supplied inning allocation for callers that know it.
        state = state.copy(
            scores = mapOf(Team.AWAY to away, Team.HOME to home),
            carryInRuns = mapOf(Team.AWAY to 0, Team.HOME to 0)
        )
    }

    fun renameCurrentBatter(newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return

        val batter = LineupEngine.currentBatter(state) ?: return
        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.BATTER_EDIT,
                    title = "Batter name corrected",
                    detail = "${batter.name} → $name",
                    actorPlayerId = batter.id,
                    actorName = name,
                    replacedPlayerId = batter.id,
                    replacedPlayerName = batter.name,
                    team = previous.activeTeam
                )
            }
        ) { current ->
            updateCurrentLineupPlayer(current) { player ->
                player.copy(name = name)
            }
        }
    }


    fun renameTeam(team: Team, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return

        val oldName = state.teamName(team)
        if (oldName == name) return
        val oldPitcherName = state.pitcherName(team)

        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.TEAM_EDIT,
                    title = "Team name changed",
                    detail = "$oldName → $name",
                    actorName = name,
                    replacedPlayerName = oldName,
                    team = team                )
            }
        ) { current ->
            when (team) {
                Team.AWAY -> current.copy(
                    awayTeamName = name,
                    awayPitcherName = if (oldPitcherName == "$oldName Pitcher") "$name Pitcher" else current.awayPitcherName
                )
                Team.HOME -> current.copy(
                    homeTeamName = name,
                    homePitcherName = if (oldPitcherName == "$oldName Pitcher") "$name Pitcher" else current.homePitcherName
                )
            }
        }
    }

    fun renamePlayer(playerId: Int, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return

        val oldName = state.playerName(playerId) ?: return
        if (oldName == name) return
        val team = teamForPlayer(state, playerId) ?: return

        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.PLAYER_EDIT,
                    title = "Player name changed",
                    detail = "$oldName → $name",
                    actorPlayerId = playerId,
                    actorName = name,
                    replacedPlayerId = playerId,
                    replacedPlayerName = oldName,
                    team = team
                )
            }
        ) { current ->
            var updated = renamePlayerEverywhere(current, playerId, name)
            if (current.playerPosition(playerId) == "P" && current.pitcherName(team) == oldName) {
                updated = when (team) {
                    Team.AWAY -> updated.copy(awayPitcherName = name)
                    Team.HOME -> updated.copy(homePitcherName = name)
                }
            }
            updated
        }
    }

    fun pinchHit(newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return

        val oldBatter = LineupEngine.currentBatter(state) ?: return
        val replacementId = nextPlayerId(state)
        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.PINCH_HITTER,
                    title = "Pinch hitter",
                    detail = "$name for ${oldBatter.name}",
                    actorPlayerId = replacementId,
                    actorName = name,
                    replacedPlayerId = oldBatter.id,
                    replacedPlayerName = oldBatter.name,
                    team = previous.activeTeam
                )
            }
        ) { current ->
            val replacement = Player(id = replacementId, name = name)
            val updated = updateCurrentLineupPlayer(current) { replacement }
            transferPosition(updated, oldBatter.id, replacementId)
        }
    }

    fun pinchRun(base: Base, newName: String) {
        val name = newName.trim()
        if (name.isEmpty() || base == Base.HOME) return

        val oldRunner = runnerAt(state, base) ?: return
        val replacementId = nextPlayerId(state)
        val oldRunnerName = state.playerName(oldRunner.playerId) ?: "Runner"

        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.PINCH_RUNNER,
                    title = "Pinch runner",
                    detail = "$name for $oldRunnerName at ${baseLabel(base)}",
                    actorPlayerId = replacementId,
                    actorName = name,
                    replacedPlayerId = oldRunner.playerId,
                    replacedPlayerName = oldRunnerName,
                    team = previous.activeTeam,
                    base = base
                )
            }
        ) { current ->
            val runner = runnerAt(current, base) ?: return@mutateWithEvent current
            val replacement = Player(id = replacementId, name = name)
            val updatedLineup = replacePlayerInLineup(
                lineup = if (current.activeTeam == Team.AWAY) current.lineupAway else current.lineupHome,
                playerId = runner.playerId,
                replacement = replacement
            ) ?: return@mutateWithEvent current

            val updatedBases = when (base) {
                Base.FIRST -> current.bases.copy(first = runner.copy(playerId = replacement.id, base = Base.FIRST))
                Base.SECOND -> current.bases.copy(second = runner.copy(playerId = replacement.id, base = Base.SECOND))
                Base.THIRD -> current.bases.copy(third = runner.copy(playerId = replacement.id, base = Base.THIRD))
                Base.HOME -> current.bases
            }

            val updated = if (current.activeTeam == Team.AWAY) {
                current.copy(lineupAway = updatedLineup, bases = updatedBases)
            } else {
                current.copy(lineupHome = updatedLineup, bases = updatedBases)
            }
            transferPosition(updated, runner.playerId, replacementId)
        }
    }

    fun pitchingChange(newPitcherName: String) {
        val fieldingTeam = if (state.activeTeam == Team.AWAY) Team.HOME else Team.AWAY
        pitchingChange(fieldingTeam, newPitcherName)
    }

    fun pitchingChange(team: Team, newPitcherName: String) {
        val name = newPitcherName.trim()
        if (name.isEmpty()) return

        val oldName = state.pitcherName(team)
        if (oldName == name) return

        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.PITCHING_CHANGE,
                    title = "Pitching change",
                    detail = "${previous.teamName(team)}: $oldName → $name",
                    actorName = name,
                    replacedPlayerName = oldName,
                    team = team,
                    inheritedRunners = if (team == opposite(previous.activeTeam)) {
                        listOf(previous.bases.first, previous.bases.second, previous.bases.third).count { it != null }
                    } else 0
                )
            }
        ) { current ->
            when (team) {
                Team.AWAY -> current.copy(awayPitcherName = name)
                Team.HOME -> current.copy(homePitcherName = name)
            }
        }
    }

    fun positionChange(playerId: Int, newPosition: String) {
        val position = newPosition.trim().uppercase()
        if (position.isEmpty()) return
        val playerName = state.playerName(playerId) ?: return
        val team = teamForPlayer(state, playerId) ?: return
        val oldPosition = state.playerPositions[playerId] ?: "—"
        if (oldPosition == position) return

        val teammateIds = when (team) {
            Team.AWAY -> state.lineupAway.map(Player::id).toSet()
            Team.HOME -> state.lineupHome.map(Player::id).toSet()
        }
        val occupantId = state.playerPositions.entries
            .firstOrNull { (otherId, otherPosition) ->
                otherId != playerId && otherId in teammateIds && otherPosition == position
            }
            ?.key
        val occupantName = occupantId?.let(state::playerName)
        val eventDetail = if (occupantId != null) {
            "$playerName: $oldPosition → $position; ${occupantName ?: "teammate"} → $oldPosition"
        } else {
            "$playerName: $oldPosition → $position"
        }

        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.POSITION_CHANGE,
                    title = if (occupantId == null) "Position change" else "Position swap",
                    detail = eventDetail,
                    actorPlayerId = playerId,
                    actorName = playerName,
                    replacedPlayerId = occupantId,
                    replacedPlayerName = occupantName,
                    team = team,
                    position = position
                )
            }
        ) { current ->
            assignUniquePosition(current, team, playerId, position)
        }
    }

    fun reorderLineup(team: Team, fromIndex: Int, toIndex: Int) {
        val lineup = when (team) {
            Team.AWAY -> state.lineupAway
            Team.HOME -> state.lineupHome
        }
        if (fromIndex !in lineup.indices || toIndex !in lineup.indices || fromIndex == toIndex) return

        val moving = lineup[fromIndex]
        val currentAwayBatterId = state.lineupAway.getOrNull(state.awayBatterIndex)?.id
        val currentHomeBatterId = state.lineupHome.getOrNull(state.homeBatterIndex)?.id
        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.LINEUP_REORDER,
                    title = "Batting order changed",
                    detail = "${previous.teamName(team)}: ${moving.name} moved from ${fromIndex + 1} to ${toIndex + 1}",
                    actorPlayerId = moving.id,
                    actorName = moving.name,
                    team = team
                )
            }
        ) { current ->
            val updatedAway = if (team == Team.AWAY) current.lineupAway.moved(fromIndex, toIndex) else current.lineupAway
            val updatedHome = if (team == Team.HOME) current.lineupHome.moved(fromIndex, toIndex) else current.lineupHome
            current.copy(
                lineupAway = updatedAway,
                lineupHome = updatedHome,
                awayBatterIndex = currentAwayBatterId?.let { id -> updatedAway.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: current.awayBatterIndex,
                homeBatterIndex = currentHomeBatterId?.let { id -> updatedHome.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: current.homeBatterIndex
            )
        }
    }

    /**
     * Corrects a personnel event without rewinding the entire game. The event itself is edited,
     * and the current roster/pitcher/position is updated when that event still controls it.
     */
    fun editPersonnelEvent(eventId: Long, newValue: String) {
        val value = newValue.trim()
        if (value.isEmpty()) return
        val event = state.events.firstOrNull { it.id == eventId } ?: return
        if (!event.isPersonnelEvent) return

        mutateCorrection { current ->
            when (event.type) {
                GameEventType.BATTER_EDIT,
                GameEventType.PLAYER_EDIT,
                GameEventType.PINCH_HITTER,
                GameEventType.PINCH_RUNNER -> {
                    val playerId = event.actorPlayerId ?: return@mutateCorrection current
                    val oldName = event.actorName.orEmpty()
                    val renamed = renamePlayerEverywhere(current, playerId, value)
                    renamed.copy(
                        events = renamed.events.map { logged ->
                            when {
                                logged.id == eventId -> logged.copy(
                                    actorName = value,
                                    detail = personnelDetail(logged, value)
                                )
                                logged.actorPlayerId == playerId && logged.actorName == oldName -> logged.copy(actorName = value)
                                logged.replacedPlayerId == playerId && logged.replacedPlayerName == oldName -> logged.copy(replacedPlayerName = value)
                                else -> logged
                            }
                        }
                    )
                }

                GameEventType.PITCHING_CHANGE -> {
                    val team = event.team ?: return@mutateCorrection current
                    val oldPitcherName = event.actorName.orEmpty()
                    val nextChangeId = current.events
                        .filter { it.type == GameEventType.PITCHING_CHANGE && it.team == team && it.id > eventId }
                        .minByOrNull(GameEvent::id)
                        ?.id
                    val isLatest = nextChangeId == null
                    var updated = current.copy(
                        events = current.events.map { logged ->
                            when {
                                logged.id == eventId -> logged.copy(
                                    actorName = value,
                                    detail = "${current.teamName(team)}: ${logged.replacedPlayerName ?: "Pitcher"} → $value"
                                )
                                logged.id > eventId && (nextChangeId == null || logged.id < nextChangeId) &&
                                    opposite(logged.battingTeam) == team &&
                                    (logged.pitcherName == oldPitcherName || logged.pitcherName.isNullOrBlank()) -> {
                                    logged.copy(pitcherName = value)
                                }
                                else -> logged
                            }
                        }
                    )
                    if (isLatest) {
                        updated = when (team) {
                            Team.AWAY -> updated.copy(awayPitcherName = value)
                            Team.HOME -> updated.copy(homePitcherName = value)
                        }
                    }
                    updated
                }

                GameEventType.POSITION_CHANGE -> {
                    val playerId = event.actorPlayerId ?: return@mutateCorrection current
                    val position = value.uppercase()
                    val isLatest = current.events
                        .filter { it.type == GameEventType.POSITION_CHANGE && it.actorPlayerId == playerId }
                        .maxByOrNull(GameEvent::id)
                        ?.id == eventId
                    var updated = current.copy(
                        events = current.events.map { logged ->
                            if (logged.id == eventId) {
                                logged.copy(
                                    position = position,
                                    detail = "${logged.actorName ?: current.playerName(playerId) ?: "Player"}: position → $position"
                                )
                            } else logged
                        }
                    )
                    if (isLatest) {
                        val team = event.team ?: teamForPlayer(updated, playerId)
                        if (team != null) {
                            updated = assignUniquePosition(updated, team, playerId, position)
                        }
                    }
                    updated
                }

                GameEventType.TEAM_EDIT -> {
                    val team = event.team ?: return@mutateCorrection current
                    val isLatest = current.events
                        .filter { it.type == GameEventType.TEAM_EDIT && it.team == team }
                        .maxByOrNull(GameEvent::id)
                        ?.id == eventId
                    var updated = current.copy(
                        events = current.events.map { logged ->
                            if (logged.id == eventId) {
                                logged.copy(
                                    actorName = value,
                                    detail = "${logged.replacedPlayerName ?: current.teamName(team)} → $value"
                                )
                            } else logged
                        }
                    )
                    if (isLatest) {
                        updated = when (team) {
                            Team.AWAY -> updated.copy(awayTeamName = value)
                            Team.HOME -> updated.copy(homeTeamName = value)
                        }
                    }
                    updated
                }

                else -> current
            }
        }
    }

    fun correctRecordedEvent(
        eventId: Long,
        batterPlayerId: Int? = null,
        pitcherName: String? = null,
        playAction: PlayAction? = null
    ) {
        val original = state.events.firstOrNull { it.id == eventId } ?: return
        if (original.type !in setOf(GameEventType.PLAY, GameEventType.PITCH)) return
        mutateCorrection { current ->
            val teamLineup = if (original.battingTeam == Team.AWAY) current.lineupAway else current.lineupHome
            val batter = batterPlayerId?.let { id -> teamLineup.firstOrNull { it.id == id } }
            val correctedAction = playAction ?: original.playAction
            val correctedType = if (correctedAction != null && correctedAction !in setOf(
                    PlayAction.WALK, PlayAction.STRIKEOUT, PlayAction.HIT_BY_PITCH
                ) && original.type == GameEventType.PITCH
            ) GameEventType.PLAY else original.type
            val corrected = original.copy(
                type = correctedType,
                title = correctedAction?.let(::playLabel) ?: original.title,
                actorPlayerId = batter?.id ?: original.actorPlayerId,
                actorName = batter?.name ?: original.actorName,
                pitcherName = pitcherName?.trim()?.takeIf(String::isNotBlank) ?: original.pitcherName,
                playAction = correctedAction,
                pitchAction = if (correctedType == GameEventType.PLAY) null else original.pitchAction,
                fieldingNotation = if (correctedAction != null && supportsFieldingNotation(correctedAction)) original.fieldingNotation else null
            )
            val replaced = current.copy(
                events = current.events.map { if (it.id == eventId) corrected else it }
            )
            recalculateRecordedTotals(replaced)
        }
    }

    /**
     * Replaces the editable record for one completed plate appearance in a single
     * correction. The pitch sequence, batter, pitcher and scoring outcome are
     * updated together so scorecard and game-stat views stay in sync. Existing
     * runner movement, runs scored and fielding credits are intentionally kept;
     * those can be corrected separately from Game Overview.
     */
    fun editRecordedAtBat(
        endEventId: Long,
        pitches: List<PitchAction>,
        playAction: PlayAction,
        batterPlayerId: Int? = null,
        pitcherName: String? = null,
        fieldingNotation: String? = null
    ): Boolean {
        val originalEndIndex = state.events.indexOfFirst { it.id == endEventId }
        if (originalEndIndex < 0) return false
        val originalEnd = state.events[originalEndIndex]
        if (!isPlateAppearanceCompletion(originalEnd)) return false

        val allowedPitches = normalizeAtBatPitches(pitches, playAction)

        mutateCorrection { current ->
            val endIndex = current.events.indexOfFirst { it.id == endEventId }
            if (endIndex < 0) return@mutateCorrection current
            val endEvent = current.events[endIndex]
            if (!isPlateAppearanceCompletion(endEvent)) return@mutateCorrection current

            val previousCompletionIndex = current.events
                .subList(0, endIndex)
                .indexOfLast(::isPlateAppearanceCompletion)
            val startIndex = previousCompletionIndex + 1
            val teamLineup = if (endEvent.battingTeam == Team.AWAY) current.lineupAway else current.lineupHome
            val batter = batterPlayerId?.let { id -> teamLineup.firstOrNull { it.id == id } }
            val correctedBatterId = batter?.id ?: endEvent.actorPlayerId
            val correctedBatterName = batter?.name ?: endEvent.actorName
            val correctedPitcher = pitcherName?.trim()?.takeIf(String::isNotBlank) ?: endEvent.pitcherName

            val atBatPitchIds = current.events
                .subList(startIndex, endIndex + 1)
                .filter { event ->
                    event.type == GameEventType.PITCH &&
                        event.battingTeam == endEvent.battingTeam &&
                        event.actorPlayerId == endEvent.actorPlayerId
                }
                .mapTo(mutableSetOf()) { it.id }
                .apply { add(endEvent.id) }

            val baseEvents = current.events.toMutableList().apply {
                removeAll { it.id in atBatPitchIds }
            }
            val insertionIndex = baseEvents.indexOfFirst { it.id == endEventId }.let { index ->
                if (index >= 0) index else baseEvents.indexOfFirst { event ->
                    event.id > endEventId
                }.let { if (it >= 0) it else baseEvents.size }
            }

            val pitchEvents = allowedPitches.mapIndexed { index, action ->
                GameEvent(
                    id = 0,
                    inning = endEvent.inning,
                    topOfInning = endEvent.topOfInning,
                    battingTeam = endEvent.battingTeam,
                    type = GameEventType.PITCH,
                    title = when (action) {
                        PitchAction.BALL -> "Ball"
                        PitchAction.STRIKE -> "Strike"
                        PitchAction.FOUL -> "Foul"
                        PitchAction.IN_PLAY -> "Ball in play"
                        PitchAction.HIT_BY_PITCH -> "Hit by pitch"
                    },
                    detail = "At-bat correction • Pitch ${index + 1} • Batter: ${correctedBatterName ?: "Unknown"}",
                    actorPlayerId = correctedBatterId,
                    actorName = correctedBatterName,
                    team = endEvent.battingTeam,
                    pitcherName = correctedPitcher,
                    pitchAction = action
                )
            }

            val correctedEnd = endEvent.copy(
                id = 0,
                type = GameEventType.PLAY,
                title = playLabel(playAction),
                actorPlayerId = correctedBatterId,
                actorName = correctedBatterName,
                pitcherName = correctedPitcher,
                playAction = playAction,
                pitchAction = null,
                outsRecorded = playAction.defaultOuts.coerceIn(0, 3),
                fieldingNotation = if (supportsFieldingNotation(playAction)) {
                    fieldingNotation?.trim()?.uppercase()?.takeIf(String::isNotBlank)
                } else null
            )

            val rebuilt = baseEvents.toMutableList().apply {
                addAll(insertionIndex, pitchEvents + correctedEnd)
            }
            recalculateRecordedTotals(renumberEvents(current, rebuilt))
        }
        return true
    }

    fun deleteRecordedEvent(eventId: Long) {
        val original = state.events.firstOrNull { it.id == eventId } ?: return
        if (original.type !in setOf(GameEventType.PLAY, GameEventType.PITCH, GameEventType.BASERUNNING, GameEventType.FIELDING_CREDIT)) return
        mutateCorrection { current ->
            recalculateRecordedTotals(current.copy(events = current.events.filterNot { it.id == eventId }))
        }
    }

    fun correctFinalScore(awayScore: Int, homeScore: Int) {
        mutateCorrection { current ->
            val away = scoreStateForTotal(current.scores[Team.AWAY].orEmpty(), awayScore)
            val home = scoreStateForTotal(current.scores[Team.HOME].orEmpty(), homeScore)
            current.copy(
                scores = current.scores.toMutableMap().apply {
                    this[Team.AWAY] = away.first
                    this[Team.HOME] = home.first
                },
                carryInRuns = current.carryInRuns.toMutableMap().apply {
                    this[Team.AWAY] = away.second
                    this[Team.HOME] = home.second
                }
            )
        }
    }

    fun undo(): Boolean {
        val previous = history.pollLast() ?: return false
        redoHistory.addLast(state)
        while (redoHistory.size > MAX_UNDO_STATES) redoHistory.removeFirst()
        state = previous
        return true
    }

    fun redo(): Boolean {
        val next = redoHistory.pollLast() ?: return false
        history.addLast(state)
        while (history.size > MAX_UNDO_STATES) history.removeFirst()
        state = next
        return true
    }

    fun endGame() {
        if (state.gameOver) return
        mutateWithEvent(
            eventBuilder = { previous, _ ->
                GameEvent(
                    id = 0,
                    inning = previous.currentInning,
                    topOfInning = previous.topOfInning,
                    battingTeam = previous.activeTeam,
                    type = GameEventType.GAME_END,
                    title = "Game ended manually"
                )
            }
        ) { current ->
            current.copy(
                gameOver = true,
                gameEndedManually = true,
                endedAtEpochMillis = System.currentTimeMillis()
            )
        }
    }

    fun loadBasesForTesting() {
        mutate { current ->
            current.copy(
                bases = Bases(
                    first = Runner(91, Base.FIRST),
                    second = Runner(92, Base.SECOND),
                    third = Runner(93, Base.THIRD)
                )
            )
        }
    }

    fun resetGame(newState: GameState = defaultState()) {
        history.clear()
        redoHistory.clear()
        state = newState
    }

    fun clearUndoHistory() {
        history.clear()
        redoHistory.clear()
    }

    private fun pitchEvent(previous: GameState, next: GameState, action: PitchAction): GameEvent {
        val batter = LineupEngine.currentBatter(previous)
        val completedAction = when {
            action == PitchAction.BALL && previous.balls >= 3 -> PlayAction.WALK
            action == PitchAction.STRIKE && previous.strikes >= 2 -> PlayAction.STRIKEOUT
            action == PitchAction.HIT_BY_PITCH -> PlayAction.HIT_BY_PITCH
            else -> null
        }
        val completed = when {
            completedAction == PlayAction.WALK -> "Ball 4 — Walk"
            completedAction == PlayAction.STRIKEOUT -> "Strike 3 — Strikeout"
            completedAction == PlayAction.HIT_BY_PITCH -> "Hit by pitch"
            action == PitchAction.BALL -> "Ball"
            action == PitchAction.STRIKE -> "Strike"
            action == PitchAction.FOUL -> "Foul"
            action == PitchAction.IN_PLAY -> "Ball in play"
            else -> "Hit by pitch"
        }
        val fieldingTeam = opposite(previous.activeTeam)
        val scored = completedAction?.let { actionType ->
            scoredRunsForPlay(previous, actionType, emptyMap())
        }.orEmpty()
        val catcher = if (completedAction == PlayAction.STRIKEOUT) fielderAtPosition(previous, fieldingTeam, "C") else null
        return GameEvent(
            id = 0,
            inning = previous.currentInning,
            topOfInning = previous.topOfInning,
            battingTeam = previous.activeTeam,
            type = GameEventType.PITCH,
            title = completed,
            detail = eventDetail(previous, next, batter?.name),
            actorPlayerId = batter?.id,
            actorName = batter?.name,
            team = previous.activeTeam,
            pitcherName = previous.pitcherName(fieldingTeam),
            playAction = completedAction,
            pitchAction = action,
            outsRecorded = if (completedAction == PlayAction.STRIKEOUT) 1 else 0,
            runsScored = scored.size,
            scoredPlayerIds = scored.map(ScoredRun::playerId),
            scoredRuns = scored,
            putoutPlayerName = catcher
        )
    }

    private fun normalizeAtBatPitches(pitches: List<PitchAction>, playAction: PlayAction): List<PitchAction> {
        val allowed = pitches.filter { it in setOf(
            PitchAction.BALL, PitchAction.STRIKE, PitchAction.FOUL, PitchAction.IN_PLAY, PitchAction.HIT_BY_PITCH
        ) }
        val requiredTerminal = terminalPitchForPlay(playAction)
        if (requiredTerminal == null) return allowed
        return if (allowed.lastOrNull() == requiredTerminal) allowed else allowed + requiredTerminal
    }

    private fun outcomePitchEvent(previous: GameState, action: PitchAction): GameEvent {
        val batter = LineupEngine.currentBatter(previous)
        val fieldingTeam = opposite(previous.activeTeam)
        return GameEvent(
            id = 0,
            inning = previous.currentInning,
            topOfInning = previous.topOfInning,
            battingTeam = previous.activeTeam,
            type = GameEventType.PITCH,
            title = when (action) {
                PitchAction.BALL -> "Ball"
                PitchAction.STRIKE -> "Strike"
                PitchAction.FOUL -> "Foul"
                PitchAction.IN_PLAY -> "Ball in play"
                PitchAction.HIT_BY_PITCH -> "Hit by pitch"
            },
            detail = "Final pitch • Batter: ${batter?.name ?: "Unknown"}",
            actorPlayerId = batter?.id,
            actorName = batter?.name,
            team = previous.activeTeam,
            pitcherName = previous.pitcherName(fieldingTeam),
            pitchAction = action
        )
    }

    private fun terminalPitchForPlay(action: PlayAction): PitchAction? = when (action) {
        PlayAction.WALK -> PitchAction.BALL
        PlayAction.STRIKEOUT -> PitchAction.STRIKE
        PlayAction.INTENTIONAL_WALK -> null
        PlayAction.HIT_BY_PITCH -> PitchAction.HIT_BY_PITCH
        PlayAction.SINGLE,
        PlayAction.DOUBLE,
        PlayAction.TRIPLE,
        PlayAction.HOME_RUN,
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.FIELDERS_CHOICE,
        PlayAction.SACRIFICE,
        PlayAction.SACRIFICE_BUNT,
        PlayAction.SACRIFICE_FLY,
        PlayAction.ERROR,
        PlayAction.DOUBLE_PLAY,
        PlayAction.TRIPLE_PLAY -> PitchAction.IN_PLAY
    }

    private fun playEvent(
        previous: GameState,
        next: GameState,
        action: PlayAction,
        outsRecorded: Int,
        manualRunnerDestinations: Map<Int, Base?>,
        fielding: FieldingPlay?
    ): GameEvent {
        val batter = LineupEngine.currentBatter(previous)
        val label = playLabel(action)
        val actualOuts = outsRecorded.coerceIn(0, (3 - previous.outs).coerceAtLeast(0))
        val fieldingTeam = opposite(previous.activeTeam)
        val scored = scoredRunsForPlay(previous, action, manualRunnerDestinations)
        return GameEvent(
            id = 0,
            inning = previous.currentInning,
            topOfInning = previous.topOfInning,
            battingTeam = previous.activeTeam,
            type = GameEventType.PLAY,
            title = label,
            detail = eventDetail(previous, next, batter?.name, actualOuts),
            actorPlayerId = batter?.id,
            actorName = batter?.name,
            team = previous.activeTeam,
            pitcherName = previous.pitcherName(fieldingTeam),
            playAction = action,
            outsRecorded = actualOuts,
            runsScored = scored.size,
            scoredPlayerIds = scored.map(ScoredRun::playerId),
            scoredRuns = scored,
            putoutPlayerName = fielding?.putoutPlayerName,
            putoutPlayerNames = fielding?.putoutPlayerNames.orEmpty(),
            assistPlayerNames = fielding?.assistPlayerNames.orEmpty(),
            errorPlayerName = fielding?.errorPlayerName,
            fieldingNotation = fielding?.fieldingNotation
        )
    }

    private fun scoredRunsForPlay(
        previous: GameState,
        action: PlayAction,
        manualRunnerDestinations: Map<Int, Base?>
    ): List<ScoredRun> {
        val batterId = LineupEngine.currentBatter(previous)?.id ?: return emptyList()
        val fieldingTeam = opposite(previous.activeTeam)
        val result = RunnerEngine.apply(
            previous.bases,
            Play(
                action = action,
                batterId = batterId,
                manualRunnerDestinations = manualRunnerDestinations,
                responsiblePitcherName = previous.pitcherName(fieldingTeam)
            )
        )
        return result.scoredRunners.map { runner ->
            ScoredRun(
                playerId = runner.playerId,
                responsiblePitcherName = runner.responsiblePitcherName ?: previous.pitcherName(fieldingTeam),
                earned = runner.earnedRunEligible
            )
        }
    }

    private fun nextBase(base: Base): Base = when (base) {
        Base.FIRST -> Base.SECOND
        Base.SECOND -> Base.THIRD
        Base.THIRD -> Base.HOME
        Base.HOME -> Base.HOME
    }

    private fun removeRunner(bases: Bases, playerId: Int): Bases = bases.copy(
        first = bases.first?.takeUnless { it.playerId == playerId },
        second = bases.second?.takeUnless { it.playerId == playerId },
        third = bases.third?.takeUnless { it.playerId == playerId }
    )

    private fun placeRunner(bases: Bases, runner: Runner): Bases = when (runner.base) {
        Base.FIRST -> bases.copy(first = runner)
        Base.SECOND -> bases.copy(second = runner)
        Base.THIRD -> bases.copy(third = runner)
        Base.HOME -> bases
    }

    private fun fielderAtPosition(state: GameState, team: Team, position: String): String? {
        val lineup = if (team == Team.AWAY) state.lineupAway else state.lineupHome
        return lineup.firstOrNull { state.playerPosition(it.id) == position }?.name
    }

    private fun baseRunningLabel(action: BaseRunningAction): String = when (action) {
        BaseRunningAction.STOLEN_BASE -> "Stolen base"
        BaseRunningAction.CAUGHT_STEALING -> "Caught stealing"
        BaseRunningAction.PICKOFF -> "Picked off"
        BaseRunningAction.RUNNER_OUT -> "Runner out"
        BaseRunningAction.WILD_PITCH -> "Wild pitch"
        BaseRunningAction.PASSED_BALL -> "Passed ball"
        BaseRunningAction.BALK -> "Balk"
        BaseRunningAction.DEFENSIVE_INDIFFERENCE -> "Defensive indifference"
        BaseRunningAction.RUNNER_ADVANCE -> "Runner advance"
    }

    private fun opposite(team: Team): Team = if (team == Team.AWAY) Team.HOME else Team.AWAY

    private fun eventDetail(
        previous: GameState,
        next: GameState,
        actorName: String?,
        outsRecorded: Int? = null
    ): String {
        val details = mutableListOf<String>()
        actorName?.let(details::add)
        outsRecorded?.takeIf { it > 0 }?.let { details += "$it out${if (it == 1) "" else "s"}" }
        val runs = next.totalRuns(previous.activeTeam) - previous.totalRuns(previous.activeTeam)
        if (runs > 0) details += "$runs run${if (runs == 1) "" else "s"} scored"
        if (next.gameOver && !previous.gameOver) details += "Game final"
        if (details.isEmpty()) {
            details += "Count ${previous.balls}-${previous.strikes} → ${next.balls}-${next.strikes}"
        }
        return details.joinToString(" • ")
    }

    private fun updateCurrentLineupPlayer(
        current: GameState,
        replacement: (Player) -> Player
    ): GameState {
        return when (current.activeTeam) {
            Team.AWAY -> {
                if (current.lineupAway.isEmpty() || current.awayBatterIndex < 0) return current
                val index = current.awayBatterIndex % current.lineupAway.size
                val lineup = current.lineupAway.toMutableList()
                lineup[index] = replacement(lineup[index])
                current.copy(lineupAway = lineup)
            }

            Team.HOME -> {
                if (current.lineupHome.isEmpty() || current.homeBatterIndex < 0) return current
                val index = current.homeBatterIndex % current.lineupHome.size
                val lineup = current.lineupHome.toMutableList()
                lineup[index] = replacement(lineup[index])
                current.copy(lineupHome = lineup)
            }
        }
    }

    private fun replacePlayerInLineup(
        lineup: List<Player>,
        playerId: Int,
        replacement: Player
    ): List<Player>? {
        val index = lineup.indexOfFirst { it.id == playerId }
        if (index == -1) return null
        return lineup.toMutableList().apply { this[index] = replacement }
    }

    private fun renamePlayerEverywhere(current: GameState, playerId: Int, newName: String): GameState {
        fun rename(lineup: List<Player>): List<Player> = lineup.map { player ->
            if (player.id == playerId) player.copy(name = newName) else player
        }
        return current.copy(
            lineupAway = rename(current.lineupAway),
            lineupHome = rename(current.lineupHome)
        )
    }

    private fun transferPosition(current: GameState, oldPlayerId: Int, newPlayerId: Int): GameState {
        val position = current.playerPositions[oldPlayerId] ?: return current
        return current.copy(
            playerPositions = current.playerPositions - oldPlayerId + (newPlayerId to position)
        )
    }

    private fun runnerAt(state: GameState, base: Base): Runner? = when (base) {
        Base.FIRST -> state.bases.first
        Base.SECOND -> state.bases.second
        Base.THIRD -> state.bases.third
        Base.HOME -> null
    }

    private fun teamForPlayer(state: GameState, playerId: Int): Team? = when {
        state.lineupAway.any { it.id == playerId } -> Team.AWAY
        state.lineupHome.any { it.id == playerId } -> Team.HOME
        else -> null
    }

    private fun <T> List<T>.moved(fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex == toIndex) return this
        val mutable = toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable.toList()
    }

    private fun ensureScoreSize(current: GameState, inning: Int): GameState {
        val size = maxOf(current.maxInnings, inning, current.scores[Team.AWAY].orEmpty().size, current.scores[Team.HOME].orEmpty().size)
        fun grow(values: List<Int>): List<Int> = values.toMutableList().apply {
            while (this.size < size) add(0)
        }
        return current.copy(
            scores = mapOf(
                Team.AWAY to grow(current.scores[Team.AWAY].orEmpty()),
                Team.HOME to grow(current.scores[Team.HOME].orEmpty())
            )
        )
    }

    private fun applyBatterSelection(current: GameState, team: Team, playerId: Int?): GameState {
        val lineup = if (team == Team.AWAY) current.lineupAway else current.lineupHome
        val oldUnknownId = if (team == Team.AWAY) current.awayUnknownBatterId else current.homeUnknownBatterId
        if (playerId == null) {
            val existing = oldUnknownId
            val unknownId = existing ?: current.nextUnknownPlayerId
            val nextUnknown = if (existing == null) current.nextUnknownPlayerId - 1 else current.nextUnknownPlayerId
            return when (team) {
                Team.AWAY -> current.copy(awayBatterIndex = -1, awayUnknownBatterId = unknownId, nextUnknownPlayerId = nextUnknown)
                Team.HOME -> current.copy(homeBatterIndex = -1, homeUnknownBatterId = unknownId, nextUnknownPlayerId = nextUnknown)
            }
        }
        val index = lineup.indexOfFirst { it.id == playerId }
        if (index < 0) return current
        var updated = current
        if (oldUnknownId != null) {
            updated = resolveUnknownIdentity(updated, oldUnknownId, playerId, lineup[index].name)
        }
        return when (team) {
            Team.AWAY -> updated.copy(awayBatterIndex = index, awayUnknownBatterId = null)
            Team.HOME -> updated.copy(homeBatterIndex = index, homeUnknownBatterId = null)
        }
    }

    private fun resolveRunnerChoice(
        current: GameState,
        previousRunner: Runner?,
        selectedPlayerId: Int?,
        base: Base,
        validPlayerIds: Set<Int>
    ): Pair<GameState, Runner?> {
        if (selectedPlayerId == null) return current to null
        if (selectedPlayerId == UNKNOWN_PLAYER_SELECTION) {
            if (previousRunner?.playerId?.let { it < 0 } == true) return current to previousRunner.copy(base = base)
            val id = current.nextUnknownPlayerId
            return current.copy(nextUnknownPlayerId = id - 1) to Runner(id, base)
        }
        if (selectedPlayerId !in validPlayerIds) return current to previousRunner
        var updated = current
        if (previousRunner != null && previousRunner.playerId < 0) {
            val realName = current.playerName(selectedPlayerId) ?: "Player"
            updated = resolveUnknownIdentity(updated, previousRunner.playerId, selectedPlayerId, realName)
        }
        return updated to Runner(selectedPlayerId, base)
    }

    private fun scoreStateForTotal(recorded: List<Int>, targetTotal: Int): Pair<List<Int>, Int> {
        val target = targetTotal.coerceAtLeast(0)
        val result = recorded.map { it.coerceAtLeast(0) }.toMutableList()
        var recordedTotal = result.sum()
        if (target >= recordedTotal) return result to (target - recordedTotal)

        var toRemove = recordedTotal - target
        for (index in result.indices.reversed()) {
            if (toRemove <= 0) break
            val remove = minOf(result[index], toRemove)
            result[index] -= remove
            toRemove -= remove
        }
        recordedTotal = result.sum()
        return result to (target - recordedTotal).coerceAtLeast(0)
    }

    private fun resolveUnknownIdentity(
        current: GameState,
        unknownId: Int,
        realPlayerId: Int,
        realPlayerName: String
    ): GameState {
        if (unknownId >= 0 || realPlayerId < 0 || unknownId == realPlayerId) return current
        fun remapRunner(runner: Runner?): Runner? = runner?.let {
            if (it.playerId == unknownId) it.copy(playerId = realPlayerId) else it
        }
        val updatedEvents = current.events.map { event ->
            val actorWasUnknown = event.actorPlayerId == unknownId
            val replacedWasUnknown = event.replacedPlayerId == unknownId
            event.copy(
                actorPlayerId = if (actorWasUnknown) realPlayerId else event.actorPlayerId,
                actorName = if (actorWasUnknown) realPlayerName else event.actorName,
                replacedPlayerId = if (replacedWasUnknown) realPlayerId else event.replacedPlayerId,
                replacedPlayerName = if (replacedWasUnknown) realPlayerName else event.replacedPlayerName,
                scoredPlayerIds = event.scoredPlayerIds.map { if (it == unknownId) realPlayerId else it },
                scoredRuns = event.scoredRuns.map { run ->
                    if (run.playerId == unknownId) run.copy(playerId = realPlayerId) else run
                }
            )
        }
        return current.copy(
            bases = Bases(
                first = remapRunner(current.bases.first),
                second = remapRunner(current.bases.second),
                third = remapRunner(current.bases.third)
            ),
            events = updatedEvents,
            awayUnknownBatterId = current.awayUnknownBatterId.takeUnless { it == unknownId },
            homeUnknownBatterId = current.homeUnknownBatterId.takeUnless { it == unknownId }
        )
    }

    private fun assignUniquePosition(
        current: GameState,
        team: Team,
        playerId: Int,
        position: String
    ): GameState {
        val teammateIds = when (team) {
            Team.AWAY -> current.lineupAway.map(Player::id).toSet()
            Team.HOME -> current.lineupHome.map(Player::id).toSet()
        }
        val oldPosition = current.playerPositions[playerId]
        val occupantId = current.playerPositions.entries
            .firstOrNull { (otherId, otherPosition) ->
                otherId != playerId && otherId in teammateIds && otherPosition == position
            }
            ?.key

        val positions = current.playerPositions.toMutableMap()
        positions[playerId] = position
        if (occupantId != null) {
            if (oldPosition == null) {
                positions.remove(occupantId)
            } else {
                positions[occupantId] = oldPosition
            }
        }

        var updated = current.copy(playerPositions = positions)
        val pitcherName = when {
            position == "P" -> current.playerName(playerId)
            oldPosition == "P" && occupantId != null -> current.playerName(occupantId)
            else -> null
        }
        if (pitcherName != null) {
            updated = when (team) {
                Team.AWAY -> updated.copy(awayPitcherName = pitcherName)
                Team.HOME -> updated.copy(homePitcherName = pitcherName)
            }
        }
        return updated
    }

    private fun personnelDetail(event: GameEvent, newName: String): String = when (event.type) {
        GameEventType.BATTER_EDIT,
        GameEventType.PLAYER_EDIT -> "${event.replacedPlayerName ?: "Player"} → $newName"
        GameEventType.PINCH_HITTER -> "$newName for ${event.replacedPlayerName ?: "hitter"}"
        GameEventType.PINCH_RUNNER -> "$newName for ${event.replacedPlayerName ?: "runner"} at ${event.base?.let(::baseLabel) ?: "base"}"
        else -> event.detail
    }

    private fun nextPlayerId(state: GameState): Int {
        val ids = buildList {
            addAll(state.lineupAway.map(Player::id))
            addAll(state.lineupHome.map(Player::id))
            state.bases.first?.let { add(it.playerId) }
            state.bases.second?.let { add(it.playerId) }
            state.bases.third?.let { add(it.playerId) }
        }
        return (ids.maxOrNull() ?: 0) + 1
    }

    private fun recalculateRecordedTotals(current: GameState): GameState {
        val maxInning = maxOf(current.maxInnings, current.currentInning, current.events.maxOfOrNull(GameEvent::inning) ?: 1)
        val scores = mutableMapOf(
            Team.AWAY to MutableList(maxInning) { 0 },
            Team.HOME to MutableList(maxInning) { 0 }
        )
        val hits = mutableMapOf(Team.AWAY to 0, Team.HOME to 0)
        val errors = mutableMapOf(Team.AWAY to 0, Team.HOME to 0)
        val hitActions = setOf(PlayAction.SINGLE, PlayAction.DOUBLE, PlayAction.TRIPLE, PlayAction.HOME_RUN)
        current.events.forEach { event ->
            if (event.runsScored > 0) {
                val target = scores.getValue(event.battingTeam)
                val index = (event.inning - 1).coerceIn(0, target.lastIndex)
                target[index] += event.runsScored
            }
            if (event.playAction in hitActions && event.type in setOf(GameEventType.PLAY, GameEventType.PITCH)) {
                hits[event.battingTeam] = (hits[event.battingTeam] ?: 0) + 1
            }
            if (event.playAction == PlayAction.ERROR && event.type == GameEventType.PLAY) {
                val fielding = opposite(event.battingTeam)
                errors[fielding] = (errors[fielding] ?: 0) + 1
            }
        }
        return current.copy(
            scores = mapOf(Team.AWAY to scores.getValue(Team.AWAY), Team.HOME to scores.getValue(Team.HOME)),
            hits = hits,
            errors = errors
        )
    }

    private fun removeCurrentCountPitch(
        matches: (PitchAction) -> Boolean,
        updateCount: (GameState) -> GameState
    ): Boolean {
        val batterId = LineupEngine.currentBatter(state)?.id ?: return false
        val lastCompletionIndex = state.events.indexOfLast(::isPlateAppearanceCompletion)
        val candidate = state.events
            .drop(lastCompletionIndex + 1)
            .asReversed()
            .firstOrNull { event ->
                event.type == GameEventType.PITCH &&
                    event.playAction == null &&
                    event.battingTeam == state.activeTeam &&
                    event.actorPlayerId == batterId &&
                    event.pitchAction?.let(matches) == true
            } ?: return false

        mutateCorrection { current ->
            updateCount(current).copy(
                events = current.events.filterNot { it.id == candidate.id }
            )
        }
        return true
    }

    private fun isPlateAppearanceCompletion(event: GameEvent): Boolean =
        event.playAction != null && (event.type == GameEventType.PLAY || event.type == GameEventType.PITCH)

    private fun renumberEvents(current: GameState, events: List<GameEvent>): GameState {
        val renumbered = events.mapIndexed { index, event -> event.copy(id = index + 1L) }
        return current.copy(
            events = renumbered,
            nextEventId = renumbered.size + 1L
        )
    }

    private fun mutate(block: (GameState) -> GameState) {
        mutateWithEvent(eventBuilder = null, block = block)
    }

    private fun mutateCorrection(block: (GameState) -> GameState) {
        val previous = state
        val next = block(previous)
        if (next == previous) return
        history.addLast(previous)
        while (history.size > MAX_UNDO_STATES) {
            history.removeFirst()
        }
        redoHistory.clear()
        state = next
    }

    private fun mutateWithEvents(
        eventBuilder: ((GameState, GameState) -> List<GameEvent>)?,
        block: (GameState) -> GameState
    ) {
        if (state.gameOver) return
        val previous = state
        var next = block(previous)
        if (next == previous) return
        if (next.gameOver && !previous.gameOver && next.endedAtEpochMillis == null) {
            next = next.copy(endedAtEpochMillis = System.currentTimeMillis())
        }

        val events = eventBuilder?.invoke(previous, next).orEmpty()
        if (events.isNotEmpty()) {
            val startId = next.nextEventId
            next = next.copy(
                events = next.events + events.mapIndexed { index, event -> event.copy(id = startId + index) },
                nextEventId = startId + events.size
            )
        }

        history.addLast(previous)
        while (history.size > MAX_UNDO_STATES) {
            history.removeFirst()
        }
        redoHistory.clear()
        state = next
    }

    private fun mutateWithEvent(
        eventBuilder: ((GameState, GameState) -> GameEvent?)?,
        block: (GameState) -> GameState
    ) {
        if (state.gameOver) return
        val previous = state
        var next = block(previous)
        if (next == previous) return
        if (next.gameOver && !previous.gameOver && next.endedAtEpochMillis == null) {
            next = next.copy(endedAtEpochMillis = System.currentTimeMillis())
        }

        val event = eventBuilder?.invoke(previous, next)
        if (event != null) {
            next = next.copy(
                events = next.events + event.copy(id = next.nextEventId),
                nextEventId = next.nextEventId + 1
            )
        }

        history.addLast(previous)
        while (history.size > MAX_UNDO_STATES) {
            history.removeFirst()
        }
        redoHistory.clear()
        state = next
    }

    private fun supportsFieldingNotation(action: PlayAction): Boolean = action in setOf(
        PlayAction.GROUND_OUT,
        PlayAction.FLY_OUT,
        PlayAction.SACRIFICE_FLY,
        PlayAction.DOUBLE_PLAY,
        PlayAction.TRIPLE_PLAY
    )

    companion object {
        const val UNKNOWN_PLAYER_SELECTION: Int = Int.MIN_VALUE
        private const val MAX_UNDO_STATES = 100
        private val DEFAULT_POSITIONS = listOf("C", "1B", "2B", "3B", "SS", "LF", "CF", "RF", "DH")

        fun defaultState(): GameState {
            return newGame(
                awayTeamName = "Away",
                homeTeamName = "Home",
                maxInnings = 9,
                awayLineup = defaultLineup(prefix = "Away", idStart = 1),
                homeLineup = defaultLineup(prefix = "Home", idStart = 1001)
            )
        }

        fun newGame(
            awayTeamName: String,
            homeTeamName: String,
            maxInnings: Int,
            awayLineup: List<Player>,
            homeLineup: List<Player>,
            gameDate: String = "",
            ballpark: String = "",
            location: String = "",
            notes: String = "",
            awayPitcherName: String = "",
            homePitcherName: String = ""
        ): GameState {
            val innings = maxInnings.coerceIn(1, 20)
            val resolvedAway = awayLineup.ifEmpty { defaultLineup(awayTeamName.ifBlank { "Away" }, 1) }
            val resolvedHome = homeLineup.ifEmpty { defaultLineup(homeTeamName.ifBlank { "Home" }, 1001) }
            val resolvedAwayName = awayTeamName.ifBlank { "Away" }
            val resolvedHomeName = homeTeamName.ifBlank { "Home" }
            return GameState(
                gameId = java.util.UUID.randomUUID().toString(),
                gameDate = gameDate.trim(),
                ballpark = ballpark.trim(),
                location = location.trim(),
                notes = notes.trim(),
                startedAtEpochMillis = System.currentTimeMillis(),
                maxInnings = innings,
                awayTeamName = resolvedAwayName,
                homeTeamName = resolvedHomeName,
                awayPitcherName = awayPitcherName.trim().ifEmpty { "$resolvedAwayName Pitcher" },
                homePitcherName = homePitcherName.trim().ifEmpty { "$resolvedHomeName Pitcher" },
                lineupAway = resolvedAway,
                lineupHome = resolvedHome,
                playerPositions = initialPositions(resolvedAway) + initialPositions(resolvedHome),
                scores = mapOf(
                    Team.AWAY to List(innings) { 0 },
                    Team.HOME to List(innings) { 0 }
                )
            )
        }

        fun defaultLineup(prefix: String, idStart: Int): List<Player> {
            return List(9) { index ->
                Player(
                    id = idStart + index,
                    name = "$prefix Player ${index + 1}"
                )
            }
        }

        private fun initialPositions(lineup: List<Player>): Map<Int, String> {
            return lineup.mapIndexedNotNull { index, player ->
                DEFAULT_POSITIONS.getOrNull(index)?.let { player.id to it }
            }.toMap()
        }

        fun playLabel(action: PlayAction): String = when (action) {
            PlayAction.SINGLE -> "Single"
            PlayAction.DOUBLE -> "Double"
            PlayAction.TRIPLE -> "Triple"
            PlayAction.HOME_RUN -> "Home run"
            PlayAction.WALK -> "Walk"
            PlayAction.INTENTIONAL_WALK -> "Intentional walk"
            PlayAction.STRIKEOUT -> "Strikeout"
            PlayAction.GROUND_OUT -> "Ground out"
            PlayAction.FLY_OUT -> "Fly out"
            PlayAction.FIELDERS_CHOICE -> "Fielder's choice"
            PlayAction.SACRIFICE -> "Sacrifice"
            PlayAction.SACRIFICE_BUNT -> "Sacrifice bunt"
            PlayAction.SACRIFICE_FLY -> "Sacrifice fly"
            PlayAction.DOUBLE_PLAY -> "Double play"
            PlayAction.TRIPLE_PLAY -> "Triple play"
            PlayAction.ERROR -> "Error"
            PlayAction.HIT_BY_PITCH -> "Hit by pitch"
        }

        private fun baseLabel(base: Base): String = when (base) {
            Base.FIRST -> "1B"
            Base.SECOND -> "2B"
            Base.THIRD -> "3B"
            Base.HOME -> "Home"
        }
    }
}
