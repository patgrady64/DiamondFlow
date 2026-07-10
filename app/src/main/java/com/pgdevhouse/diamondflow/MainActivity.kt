package com.pgdevhouse.diamondflow

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import com.pgdevhouse.diamondflow.logic.GameEngine
import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.GameSnapshot
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.PitchAction
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Team

class MainActivity : Activity() {

    private val engine = GameEngine()

    private var state = GameState(
        lineupAway = defaultLineup(prefix = "Away"),
        lineupHome = defaultLineup(prefix = "Home")
    )

    private lateinit var inningText: TextView
    private lateinit var scoreText: TextView
    private lateinit var countText: TextView
    private lateinit var outsText: TextView
    private lateinit var basesText: TextView
    private lateinit var batterText: TextView
    private lateinit var teamText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val scrollView = ScrollView(this).apply {
            addView(root)
        }

        inningText = label(root)
        scoreText = label(root)
        teamText = label(root)
        batterText = label(root)
        countText = label(root)
        outsText = label(root)
        basesText = label(root)

        addButton(root, "Ball") {
            state = engine.applyPitch(
                state = state,
                pitch = PitchAction.BALL
            )
            render()
        }

        addButton(root, "Strike") {
            state = engine.applyPitch(
                state = state,
                pitch = PitchAction.STRIKE
            )
            render()
        }

        addButton(root, "Foul") {
            state = engine.applyPitch(
                state = state,
                pitch = PitchAction.FOUL
            )
            render()
        }

        addButton(root, "Single") {
            state = engine.applyPlay(
                state = state,
                play = Play(action = PlayAction.SINGLE)
            )
            render()
        }

        addButton(root, "Double") {
            state = engine.applyPlay(
                state = state,
                play = Play(action = PlayAction.DOUBLE)
            )
            render()
        }

        addButton(root, "Triple") {
            state = engine.applyPlay(
                state = state,
                play = Play(action = PlayAction.TRIPLE)
            )
            render()
        }

        addButton(root, "Home Run") {
            state = engine.applyPlay(
                state = state,
                play = Play(action = PlayAction.HOME_RUN)
            )
            render()
        }

        addButton(root, "Ground Out") {
            state = engine.applyPlay(
                state = state,
                play = Play(action = PlayAction.GROUND_OUT)
            )
            render()
        }

        addButton(root, "Fly Out") {
            state = engine.applyPlay(
                state = state,
                play = Play(action = PlayAction.FLY_OUT)
            )
            render()
        }

        addButton(root, "Bases Loaded Test") {
            state = state.copy(
                bases = com.pgdevhouse.diamondflow.model.Bases(
                    first = com.pgdevhouse.diamondflow.model.Runner(91, Base.FIRST),
                    second = com.pgdevhouse.diamondflow.model.Runner(92, Base.SECOND),
                    third = com.pgdevhouse.diamondflow.model.Runner(93, Base.THIRD)
                )
            )
            render()
        }

        addButton(root, "Reset Game") {
            state = GameState(
                lineupAway = defaultLineup(prefix = "Away"),
                lineupHome = defaultLineup(prefix = "Home")
            )
            render()
        }

        setContentView(scrollView)
        render()
    }

    private fun render() {
        val snapshot = GameSnapshot.from(state)

        inningText.text = "Inning: ${snapshot.inningLabel}"
        scoreText.text = "Score: ${snapshot.scoreLabel}"
        teamText.text = "Batting: ${activeTeamLabel(state.activeTeam)}"
        batterText.text = "Batter: ${snapshot.currentBatterName ?: "No batter"}"
        countText.text = "Count: ${snapshot.countLabel}"
        outsText.text = "Outs: ${snapshot.outsLabel}"
        basesText.text = "Bases: ${snapshot.basesLabel}"
    }

    private fun label(root: LinearLayout): TextView {
        val textView = TextView(this).apply {
            textSize = 20f
            setPadding(0, 8, 0, 8)
        }

        root.addView(
            textView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        return textView
    }

    private fun addButton(
        root: LinearLayout,
        text: String,
        onClick: () -> Unit
    ) {
        val button = Button(this).apply {
            this.text = text
            setOnClickListener {
                onClick()
            }
        }

        root.addView(
            button,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun activeTeamLabel(team: Team): String {
        return when (team) {
            Team.AWAY -> "Away"
            Team.HOME -> "Home"
        }
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