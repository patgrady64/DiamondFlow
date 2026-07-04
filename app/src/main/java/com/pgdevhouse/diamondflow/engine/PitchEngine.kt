package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.PlayAction

object PitchEngine {

    fun apply(
        state: GameState,
        pitch: PitchAction
    ): PitchResolution {

        return when (pitch) {
            PitchAction.BALL -> applyBall(state)

            PitchAction.STRIKE -> applyStrike(state)

            PitchAction.FOUL -> applyFoul(state)

            PitchAction.HIT_BY_PITCH -> PitchResolution(
                state = state,
                completedPlayAction = PlayAction.HIT_BY_PITCH
            )
        }
    }

    private fun applyBall(state: GameState): PitchResolution {
        return if (state.balls >= 3) {
            PitchResolution(
                state = state,
                completedPlayAction = PlayAction.WALK
            )
        } else {
            PitchResolution(
                state = state.copy(
                    balls = state.balls + 1
                )
            )
        }
    }

    private fun applyStrike(state: GameState): PitchResolution {
        return if (state.strikes >= 2) {
            PitchResolution(
                state = state,
                completedPlayAction = PlayAction.STRIKEOUT
            )
        } else {
            PitchResolution(
                state = state.copy(
                    strikes = state.strikes + 1
                )
            )
        }
    }

    private fun applyFoul(state: GameState): PitchResolution {
        return if (state.strikes < 2) {
            PitchResolution(
                state = state.copy(
                    strikes = state.strikes + 1
                )
            )
        } else {
            PitchResolution(state = state)
        }
    }
}