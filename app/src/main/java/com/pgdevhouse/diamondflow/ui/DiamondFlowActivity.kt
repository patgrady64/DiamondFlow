package com.pgdevhouse.diamondflow.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pgdevhouse.diamondflow.R
import com.pgdevhouse.diamondflow.engine.FlowEngine
import com.pgdevhouse.diamondflow.model.GameState

class DiamondFlowActivity : AppCompatActivity() {

    private var state = GameState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diamond_flow)

        val ballsText = findViewById<TextView>(R.id.ballsText)
        val strikesText = findViewById<TextView>(R.id.strikesText)
        val outsText = findViewById<TextView>(R.id.outsText)

        val ballPlus = findViewById<Button>(R.id.ballPlus)
        val strikePlus = findViewById<Button>(R.id.strikePlus)
        val outPlus = findViewById<Button>(R.id.outPlus)

        fun render() {
            ballsText.text = state.balls.toString()
            strikesText.text = state.strikes.toString()
            outsText.text = state.outs.toString()
        }

        ballPlus.setOnClickListener {
            state = FlowEngine.addBall(state)
            render()
        }

        strikePlus.setOnClickListener {
            state = FlowEngine.addStrike(state)
            render()
        }

        outPlus.setOnClickListener {
            state = FlowEngine.addOut(state)
            render()
        }

        render()
    }
}