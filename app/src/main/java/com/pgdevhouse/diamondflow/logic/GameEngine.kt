package com.pgdevhouse.diamondflow.logic

import com.pgdevhouse.diamondflow.engine.CountEngine
import com.pgdevhouse.diamondflow.engine.InningEngine
import com.pgdevhouse.diamondflow.engine.LineupEngine
import com.pgdevhouse.diamondflow.engine.OutEngine
import com.pgdevhouse.diamondflow.engine.PitchEngine
import com.pgdevhouse.diamondflow.engine.RunnerEngine
import com.pgdevhouse.diamondflow.engine.ScoreEngine
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.Play

class GameEngine {

    fun applyPitch(
        state: GameState,
        pitch: PitchAction,
        batterId: Int = -1
    ): GameState {

        val pitchResolution = PitchEngine.apply(
            state = state,
            pitch = pitch
        )

        val completedAction =
            pitchResolution.completedPlayAction
                ?: return pitchResolution.state

        return applyPlay(
            state = pitchResolution.state,
            play = Play(
                action = completedAction,
                batterId = batterId
            )
        )
    }

    fun applyPlay(
        state: GameState,
        play: Play
    ): GameState {

        val resolvedPlay = resolveBatter(
            state = state,
            play = play
        )

        var updatedState = CountEngine.apply(
            state = state,
            play = resolvedPlay
        )

        updatedState = OutEngine.apply(
            state = updatedState,
            play = resolvedPlay
        )

        val runnerResult = RunnerEngine.apply(
            bases = updatedState.bases,
            play = resolvedPlay
        )

        updatedState = updatedState.copy(
            bases = runnerResult.bases
        )

        updatedState = ScoreEngine.apply(
            state = updatedState,
            runsScored = runnerResult.runsScored
        )

        updatedState = LineupEngine.apply(updatedState)

        updatedState = InningEngine.apply(updatedState)

        return updatedState
    }

    private fun resolveBatter(
        state: GameState,
        play: Play
    ): Play {
        if (play.batterId != -1) {
            return play
        }

        val currentBatter = LineupEngine.currentBatter(state)
            ?: return play

        return play.copy(
            batterId = currentBatter.id
        )
    }
}