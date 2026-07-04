package com.pgdevhouse.diamondflow.logic

import com.pgdevhouse.diamondflow.engine.CountEngine
import com.pgdevhouse.diamondflow.engine.InningEngine
import com.pgdevhouse.diamondflow.engine.OutEngine
import com.pgdevhouse.diamondflow.engine.PitchEngine
import com.pgdevhouse.diamondflow.engine.RunnerEngine
import com.pgdevhouse.diamondflow.engine.ScoreEngine
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.Play

class GameEngine {

    fun applyPlay(
        state: GameState,
        play: Play
    ): GameState {

        var updatedState = CountEngine.apply(
            state = state,
            play = play
        )

        updatedState = OutEngine.apply(
            state = updatedState,
            play = play
        )

        val runnerResult = RunnerEngine.apply(
            bases = updatedState.bases,
            play = play
        )

        updatedState = updatedState.copy(
            bases = runnerResult.bases
        )

        updatedState = ScoreEngine.apply(
            state = updatedState,
            runsScored = runnerResult.runsScored
        )

        updatedState = InningEngine.apply(updatedState)

        return updatedState
    }

    fun applyPitch(
        state: GameState,
        pitch: PitchAction,
        batterId: Int
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
}