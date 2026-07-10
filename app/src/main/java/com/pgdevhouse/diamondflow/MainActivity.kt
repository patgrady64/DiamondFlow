package com.pgdevhouse.diamondflow

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.model.Player
import com.pgdevhouse.diamondflow.model.Team

class MainActivity : Activity() {

    private val controller = GameController()

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
            controller.ball()
            render()
        }

        addButton(root, "Strike") {
            controller.strike()
            render()
        }

        addButton(root, "Foul") {
            controller.foul()
            render()
        }

        addButton(root, "Single") {
            controller.single()
            render()
        }

        addButton(root, "Double") {
            controller.double()
            render()
        }

        addButton(root, "Triple") {
            controller.triple()
            render()
        }

        addButton(root, "Home Run") {
            controller.homeRun()
            render()
        }

        addButton(root, "Ground Out") {
            controller.groundOut()
            render()
        }

        addButton(root, "Fly Out") {
            controller.flyOut()
            render()
        }

        addButton(root, "Bases Loaded Test") {
            controller.loadBasesForTesting()
            render()
        }

        addButton(root, "Reset Game") {
            controller.resetGame()
            render()
        }

        setContentView(scrollView)
        render()
    }

    private fun render() {
        val snapshot = controller.snapshot

        inningText.text = "Inning: ${snapshot.inningLabel}"
        scoreText.text = "Score: ${snapshot.scoreLabel}"
        teamText.text = "Batting: ${activeTeamLabel(controller.state.activeTeam)}"
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