package com.pgdevhouse.diamondflow.storage

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.pgdevhouse.diamondflow.model.GameState
import com.pgdevhouse.diamondflow.model.GameStats
import com.pgdevhouse.diamondflow.model.Team
import java.io.OutputStream
import java.util.Locale

object GameExport {
    fun csv(state: GameState): String = buildString {
        appendLine("InningTrack Game Export")
        appendLine("Away,${csvCell(state.awayTeamName)}")
        appendLine("Home,${csvCell(state.homeTeamName)}")
        appendLine("Final Score,${state.totalRuns(Team.AWAY)},${state.totalRuns(Team.HOME)}")
        appendLine()
        appendLine("Batting")
        appendLine("Team,Player,PA,AB,R,H,2B,3B,HR,RBI,BB,IBB,HBP,SO,SH,SF,SB,CS,GIDP,AVG,OBP,SLG,OPS")
        listOf(Team.AWAY, Team.HOME).forEach { team ->
            GameStats.batting(state, team).forEach { s ->
                appendLine(listOf(
                    state.teamName(team), s.playerName, s.plateAppearances, s.atBats, s.runs, s.hits,
                    s.doubles, s.triples, s.homeRuns, s.rbi, s.walks, s.intentionalWalks, s.hitByPitch,
                    s.strikeouts, s.sacrificeBunts, s.sacrificeFlies, s.stolenBases, s.caughtStealing,
                    s.groundedIntoDoublePlay, rate(s.average), rate(s.onBasePercentage), rate(s.slugging), rate(s.ops)
                ).joinToString(",") { csvCell(it.toString()) })
            }
        }
        appendLine()
        appendLine("Pitching")
        appendLine("Team,Pitcher,IP,BF,Pitches,H,R,ER,BB,IBB,SO,HR,WP,BK,IR,IRS,ERA,WHIP")
        listOf(Team.AWAY, Team.HOME).forEach { team ->
            GameStats.pitching(state, team).forEach { s ->
                appendLine(listOf(
                    state.teamName(team), s.pitcherName, s.inningsPitchedDisplay, s.battersFaced, s.pitches,
                    s.hitsAllowed, s.runsAllowed, s.earnedRuns, s.walks, s.intentionalWalks, s.strikeouts,
                    s.homeRunsAllowed, s.wildPitches, s.balks, s.inheritedRunners, s.inheritedRunnersScored,
                    rate(s.era), rate(s.whip)
                ).joinToString(",") { csvCell(it.toString()) })
            }
        }
        appendLine()
        appendLine("Fielding")
        appendLine("Team,Player,PO,A,E,DP,TP,PB")
        listOf(Team.AWAY, Team.HOME).forEach { team ->
            GameStats.fielding(state, team).forEach { s ->
                appendLine(listOf(state.teamName(team), s.playerName, s.putouts, s.assists, s.errors, s.doublePlays, s.triplePlays, s.passedBalls)
                    .joinToString(",") { csvCell(it.toString()) })
            }
        }
    }

    fun writePdf(context: Context, uri: Uri, state: GameState) {
        context.contentResolver.openOutputStream(uri)?.use { output -> writePdf(output, state) }
            ?: error("Could not open destination.")
    }

    private fun writePdf(output: OutputStream, state: GameState) {
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 10f; isAntiAlias = true }
        val bold = Paint(paint).apply { isFakeBoldText = true; textSize = 14f }
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNumber).create())
        var canvas = page.canvas
        var y = 36f

        fun newPage() {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNumber).create())
            canvas = page.canvas
            y = 36f
        }
        fun line(text: String, strong: Boolean = false) {
            if (y > 760f) newPage()
            canvas.drawText(text.take(105), 28f, y, if (strong) bold else paint)
            y += if (strong) 20f else 14f
        }

        line("InningTrack Scorecard", true)
        line("${state.awayTeamName} ${state.totalRuns(Team.AWAY)} - ${state.totalRuns(Team.HOME)} ${state.homeTeamName}", true)
        listOf(state.gameDate, state.ballpark, state.location).filter(String::isNotBlank).forEach(::line)
        line("")
        line("Line Score", true)
        val maxInnings = maxOf(state.maxInnings, state.currentInning)
        line("Inning: " + (1..maxInnings).joinToString("  "))
        listOf(Team.AWAY, Team.HOME).forEach { team ->
            val scores = state.scores[team].orEmpty()
            line(state.teamName(team) + ": " + (1..maxInnings).joinToString("  ") { scores.getOrNull(it - 1)?.toString() ?: "-" } +
                "   R ${state.totalRuns(team)} H ${state.hits[team] ?: 0} E ${state.errors[team] ?: 0}")
        }
        listOf(Team.AWAY, Team.HOME).forEach { team ->
            line("")
            line("${state.teamName(team)} Batting", true)
            line("Player                 PA AB R H 2B 3B HR RBI BB SO SB CS AVG/OBP/SLG")
            GameStats.batting(state, team).forEach { s ->
                line(String.format(Locale.US, "%-20s %2d %2d %d %d %2d %2d %2d %3d %2d %2d %2d %2d %s/%s/%s",
                    s.playerName.take(20), s.plateAppearances, s.atBats, s.runs, s.hits, s.doubles, s.triples,
                    s.homeRuns, s.rbi, s.walks, s.strikeouts, s.stolenBases, s.caughtStealing,
                    rate(s.average), rate(s.onBasePercentage), rate(s.slugging)))
            }
            line("${state.teamName(team)} Pitching", true)
            line("Pitcher                IP  BF Pitches H R ER BB SO HR ERA WHIP")
            GameStats.pitching(state, team).forEach { s ->
                line(String.format(Locale.US, "%-20s %4s %3d %7d %d %d %2d %2d %2d %2d %s %s",
                    s.pitcherName.take(20), s.inningsPitchedDisplay, s.battersFaced, s.pitches, s.hitsAllowed,
                    s.runsAllowed, s.earnedRuns, s.walks, s.strikeouts, s.homeRunsAllowed, rate(s.era), rate(s.whip)))
            }
        }
        line("")
        line("Game Log", true)
        state.events.forEach { event ->
            line("${if (event.topOfInning) "T" else "B"}${event.inning}  ${event.title}${if (event.detail.isBlank()) "" else " - ${event.detail}"}")
        }
        document.finishPage(page)
        document.writeTo(output)
        document.close()
    }

    private fun csvCell(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else value

    private fun rate(value: Double?): String = value?.let { String.format(Locale.US, "%.3f", it) } ?: ""
}
