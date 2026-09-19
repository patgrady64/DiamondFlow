package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.Base
import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.Play
import com.pgdevhouse.diamondflow.model.PlayAction
import com.pgdevhouse.diamondflow.model.Runner

object RunnerEngine {

    fun apply(
        bases: Bases,
        play: Play
    ): RunnerResult {
        val destinations = defaultDestinations(bases, play).toMutableMap()
        play.manualRunnerDestinations.forEach { (playerId, destination) ->
            destinations[playerId] = destination
        }
        return buildResult(bases, play, destinations)
    }

    fun defaultDestinations(
        bases: Bases,
        play: Play
    ): Map<Int, Base?> {
        val destinations = currentDestinations(bases)

        when (play.action) {
            PlayAction.SINGLE -> {
                moveExistingRunners(bases, destinations, 1)
                destinations[play.batterId] = Base.FIRST
            }

            PlayAction.DOUBLE -> {
                moveExistingRunners(bases, destinations, 2)
                destinations[play.batterId] = Base.SECOND
            }

            PlayAction.TRIPLE -> {
                moveExistingRunners(bases, destinations, 3)
                destinations[play.batterId] = Base.THIRD
            }

            PlayAction.HOME_RUN -> {
                bases.first?.let { destinations[it.playerId] = Base.HOME }
                bases.second?.let { destinations[it.playerId] = Base.HOME }
                bases.third?.let { destinations[it.playerId] = Base.HOME }
                destinations[play.batterId] = Base.HOME
            }

            PlayAction.WALK,
            PlayAction.INTENTIONAL_WALK,
            PlayAction.HIT_BY_PITCH -> {
                applyForcedAdvance(bases, destinations)
                destinations[play.batterId] = Base.FIRST
            }

            PlayAction.ERROR -> {
                moveExistingRunners(bases, destinations, 1)
                destinations[play.batterId] = Base.FIRST
            }

            PlayAction.STRIKEOUT,
            PlayAction.GROUND_OUT,
            PlayAction.FLY_OUT,
            PlayAction.SACRIFICE,
            PlayAction.SACRIFICE_BUNT,
            PlayAction.SACRIFICE_FLY -> {
                // Existing runners stay by default. Scorer may override advancement.
            }

            PlayAction.FIELDERS_CHOICE -> {
                val retiredRunner = bases.first ?: bases.second ?: bases.third
                retiredRunner?.let { destinations[it.playerId] = null }
                destinations[play.batterId] = Base.FIRST
            }

            PlayAction.DOUBLE_PLAY -> {
                val retiredRunner = bases.first ?: bases.second ?: bases.third
                retiredRunner?.let { destinations[it.playerId] = null }
                // Batter is retired and therefore is intentionally not placed on a base.
            }

            PlayAction.TRIPLE_PLAY -> {
                listOfNotNull(bases.first, bases.second).forEach { destinations[it.playerId] = null }
                if (bases.first == null || bases.second == null) {
                    bases.third?.let { destinations[it.playerId] = null }
                }
                // Batter is retired and therefore is intentionally not placed on a base.
            }
        }

        return destinations
    }

    private fun currentDestinations(bases: Bases): MutableMap<Int, Base?> {
        val destinations = linkedMapOf<Int, Base?>()
        bases.first?.let { destinations[it.playerId] = Base.FIRST }
        bases.second?.let { destinations[it.playerId] = Base.SECOND }
        bases.third?.let { destinations[it.playerId] = Base.THIRD }
        return destinations
    }

    private fun moveExistingRunners(
        bases: Bases,
        destinations: MutableMap<Int, Base?>,
        steps: Int
    ) {
        bases.first?.let { destinations[it.playerId] = moveFrom(it.base, steps) }
        bases.second?.let { destinations[it.playerId] = moveFrom(it.base, steps) }
        bases.third?.let { destinations[it.playerId] = moveFrom(it.base, steps) }
    }

    private fun applyForcedAdvance(
        bases: Bases,
        destinations: MutableMap<Int, Base?>
    ) {
        val first = bases.first
        val second = bases.second
        val third = bases.third
        if (first != null) {
            destinations[first.playerId] = Base.SECOND
            if (second != null) {
                destinations[second.playerId] = Base.THIRD
                if (third != null) destinations[third.playerId] = Base.HOME
            }
        }
    }

    private fun moveFrom(currentBase: Base, steps: Int): Base {
        val destinationNumber = baseNumber(currentBase) + steps
        return when {
            destinationNumber >= 4 -> Base.HOME
            destinationNumber == 3 -> Base.THIRD
            destinationNumber == 2 -> Base.SECOND
            else -> Base.FIRST
        }
    }

    private fun baseNumber(base: Base): Int = when (base) {
        Base.HOME -> 4
        Base.FIRST -> 1
        Base.SECOND -> 2
        Base.THIRD -> 3
    }

    private fun buildResult(
        bases: Bases,
        play: Play,
        destinations: Map<Int, Base?>
    ): RunnerResult {
        val sourceRunners = buildMap<Int, Runner> {
            bases.first?.let { put(it.playerId, it) }
            bases.second?.let { put(it.playerId, it) }
            bases.third?.let { put(it.playerId, it) }
            if (play.batterId != -1) {
                put(
                    play.batterId,
                    Runner(
                        playerId = play.batterId,
                        base = Base.FIRST,
                        responsiblePitcherName = play.responsiblePitcherName,
                        earnedRunEligible = play.action != PlayAction.ERROR
                    )
                )
            }
        }

        var first: Runner? = null
        var second: Runner? = null
        var third: Runner? = null
        val scored = mutableListOf<Runner>()

        destinations.forEach { (playerId, destination) ->
            val source = sourceRunners[playerId] ?: Runner(
                playerId = playerId,
                base = destination ?: Base.FIRST,
                responsiblePitcherName = if (playerId == play.batterId) play.responsiblePitcherName else null,
                earnedRunEligible = play.action != PlayAction.ERROR
            )
            when (destination) {
                Base.FIRST -> {
                    check(first == null) { "Two runners cannot occupy first base." }
                    first = source.copy(base = Base.FIRST)
                }
                Base.SECOND -> {
                    check(second == null) { "Two runners cannot occupy second base." }
                    second = source.copy(base = Base.SECOND)
                }
                Base.THIRD -> {
                    check(third == null) { "Two runners cannot occupy third base." }
                    third = source.copy(base = Base.THIRD)
                }
                Base.HOME -> scored += source.copy(base = Base.HOME)
                null -> Unit
            }
        }

        return RunnerResult(
            bases = Bases(first = first, second = second, third = third),
            scoredRunners = scored
        )
    }
}
