package com.pgdevhouse.diamondflow

import com.pgdevhouse.diamondflow.controller.GameController
import com.pgdevhouse.diamondflow.model.GameEventType
import com.pgdevhouse.diamondflow.model.Team
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonnelEditingTest {

    @Test
    fun roster_name_edit_preserves_player_identity_and_is_logged() {
        val controller = GameController()
        val player = controller.state.lineupHome[4]

        controller.renamePlayer(player.id, "Updated Player")

        assertEquals("Updated Player", controller.state.playerName(player.id))
        assertEquals(player.id, controller.state.lineupHome[4].id)
        assertEquals(GameEventType.PLAYER_EDIT, controller.state.events.last().type)
    }

    @Test
    fun choosing_an_occupied_position_swaps_players_without_duplicates() {
        val controller = GameController()
        val home = controller.state.lineupHome
        val catcher = home[0]
        val shortstop = home[4]
        val originalPitcherName = controller.state.homePitcherName

        controller.positionChange(catcher.id, "SS")

        assertEquals("SS", controller.state.playerPosition(catcher.id))
        assertEquals("C", controller.state.playerPosition(shortstop.id))
        assertEquals(originalPitcherName, controller.state.homePitcherName)

        val positions = home.mapNotNull { controller.state.playerPosition(it.id) }
        assertEquals(positions.size, positions.toSet().size)
    }

    @Test
    fun overview_can_change_either_teams_pitcher() {
        val controller = GameController()

        controller.pitchingChange(Team.AWAY, "Away Reliever")

        assertEquals("Away Reliever", controller.state.awayPitcherName)
        assertTrue(controller.state.events.last().detail.contains("Away"))
    }
    @Test
    fun team_name_can_be_changed_and_is_logged() {
        val controller = GameController()

        controller.renameTeam(Team.AWAY, "Baltimore Hawks")

        assertEquals("Baltimore Hawks", controller.state.awayTeamName)
        assertEquals(GameEventType.TEAM_EDIT, controller.state.events.last().type)
        assertTrue(controller.state.events.last().detail.contains("Away"))
    }

    @Test
    fun renaming_team_updates_only_the_default_pitcher_label() {
        val controller = GameController()
        controller.renameTeam(Team.HOME, "Tigers")
        assertEquals("Tigers Pitcher", controller.state.homePitcherName)

        controller.pitchingChange(Team.HOME, "Jordan Smith")
        controller.renameTeam(Team.HOME, "Wildcats")

        assertEquals("Jordan Smith", controller.state.homePitcherName)
    }

}
