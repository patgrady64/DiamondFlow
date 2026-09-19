package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Team

object StatEngine {

    fun apply(state: GameState, play: Play): GameState {
        var hits = state.hits
        var errors = state.errors

        if (play.action in hitActions) {
            hits = hits.toMutableMap().apply {
                this[state.activeTeam] = (this[state.activeTeam] ?: 0) + 1
            }
        }

        if (play.action == PlayAction.ERROR) {
            val fieldingTeam = when (state.activeTeam) {
                Team.AWAY -> Team.HOME
                Team.HOME -> Team.AWAY
            }
            errors = errors.toMutableMap().apply {
                this[fieldingTeam] = (this[fieldingTeam] ?: 0) + 1
            }
        }

        return state.copy(hits = hits, errors = errors)
    }

    private val hitActions = setOf(
        PlayAction.SINGLE,
        PlayAction.DOUBLE,
        PlayAction.TRIPLE,
        PlayAction.HOME_RUN
    )
}
