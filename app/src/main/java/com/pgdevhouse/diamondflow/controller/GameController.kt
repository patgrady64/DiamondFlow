package com.pgdevhouse.diamondflow.controller

import com.pgdevhouse.diamondflow.logic.GameEngine
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.GameSnapshot
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Runner

class GameController(
    initialState: GameState = defaultState()
) {

    private val engine = GameEngine()

    var state: GameState = initialState
        private set

    val snapshot: GameSnapshot
        get() = GameSnapshot.from(state)

    fun ball() {
        state = engine.applyPitch(
            state = state,
            pitch = PitchAction.BALL
        )
    }

    fun strike() {
        state = engine.applyPitch(
            state = state,
            pitch = PitchAction.STRIKE
        )
    }

    fun foul() {
        state = engine.applyPitch(
            state = state,
            pitch = PitchAction.FOUL
        )
    }

    fun single() {
        applyPlay(PlayAction.SINGLE)
    }

    fun double() {
        applyPlay(PlayAction.DOUBLE)
    }

    fun triple() {
        applyPlay(PlayAction.TRIPLE)
    }

    fun homeRun() {
        applyPlay(PlayAction.HOME_RUN)
    }

    fun groundOut() {
        applyPlay(PlayAction.GROUND_OUT)
    }

    fun flyOut() {
        applyPlay(PlayAction.FLY_OUT)
    }

    fun loadBasesForTesting() {
        state = state.copy(
            bases = Bases(
                first = Runner(
                    playerId = 91,
                    base = Base.FIRST
                ),
                second = Runner(
                    playerId = 92,
                    base = Base.SECOND
                ),
                third = Runner(
                    playerId = 93,
                    base = Base.THIRD
                )
            )
        )
    }

    fun resetGame() {
        state = defaultState()
    }

    private fun applyPlay(action: PlayAction) {
        state = engine.applyPlay(
            state = state,
            play = Play(action = action)
        )
    }

    companion object {

        fun defaultState(): GameState {
            return GameState(
                lineupAway = defaultLineup(prefix = "Away"),
                lineupHome = defaultLineup(prefix = "Home")
            )
        }

        private fun defaultLineup(prefix: String): List<Player> {
            return listOf(
                Player(id = 1, name = "$prefix Player 1"),
                Player(id = 2, name = "$prefix Player 2"),
                Player(id = 3, name = "$prefix Player 3"),
                Player(id = 4, name = "$prefix Player 4"),
                Player(id = 5, name = "$prefix Player 5"),
                Player(id = 6, name = "$prefix Player 6"),
                Player(id = 7, name = "$prefix Player 7"),
                Player(id = 8, name = "$prefix Player 8"),
                Player(id = 9, name = "$prefix Player 9")
            )
        }
    }
}