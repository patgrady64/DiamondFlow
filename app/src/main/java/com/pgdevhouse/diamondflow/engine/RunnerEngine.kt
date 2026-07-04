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

        val destinations = createDefaultDestinations(
            bases = bases,
            play = play
        )

        // Manual destinations replace automatic destinations.
        play.manualRunnerDestinations.forEach { (playerId, destination) ->
            destinations[playerId] = destination
        }

        return buildResult(destinations)
    }

    private fun createDefaultDestinations(
        bases: Bases,
        play: Play
    ): MutableMap<Int, Base?> {

        val destinations = currentDestinations(bases)

        when (play.action) {
            PlayAction.SINGLE -> {
                moveExistingRunners(
                    bases = bases,
                    destinations = destinations,
                    steps = 1
                )

                destinations[play.batterId] = Base.FIRST
            }

            PlayAction.DOUBLE -> {
                moveExistingRunners(
                    bases = bases,
                    destinations = destinations,
                    steps = 2
                )

                destinations[play.batterId] = Base.SECOND
            }

            PlayAction.TRIPLE -> {
                moveExistingRunners(
                    bases = bases,
                    destinations = destinations,
                    steps = 3
                )

                destinations[play.batterId] = Base.THIRD
            }

            PlayAction.HOME_RUN -> {
                bases.first?.let {
                    destinations[it.playerId] = Base.HOME
                }

                bases.second?.let {
                    destinations[it.playerId] = Base.HOME
                }

                bases.third?.let {
                    destinations[it.playerId] = Base.HOME
                }

                destinations[play.batterId] = Base.HOME
            }

            PlayAction.WALK,
            PlayAction.HIT_BY_PITCH -> {
                applyForcedAdvance(
                    bases = bases,
                    destinations = destinations
                )

                destinations[play.batterId] = Base.FIRST
            }

            PlayAction.ERROR -> {
                moveExistingRunners(
                    bases = bases,
                    destinations = destinations,
                    steps = 1
                )

                destinations[play.batterId] = Base.FIRST
            }

            PlayAction.STRIKEOUT,
            PlayAction.GROUND_OUT,
            PlayAction.FLY_OUT,
            PlayAction.SACRIFICE -> {
                // Existing runners stay where they are by default.
                // Any movement can be supplied through manualRunnerDestinations.
            }

            PlayAction.FIELDERS_CHOICE -> {
                /*
                 * A fielder's choice can have several different outcomes.
                 * The caller supplies the batter and runner destinations
                 * through manualRunnerDestinations.
                 */
            }
        }

        return destinations
    }

    private fun currentDestinations(
        bases: Bases
    ): MutableMap<Int, Base?> {

        val destinations = linkedMapOf<Int, Base?>()

        bases.first?.let { runner ->
            destinations[runner.playerId] = Base.FIRST
        }

        bases.second?.let { runner ->
            destinations[runner.playerId] = Base.SECOND
        }

        bases.third?.let { runner ->
            destinations[runner.playerId] = Base.THIRD
        }

        return destinations
    }

    private fun moveExistingRunners(
        bases: Bases,
        destinations: MutableMap<Int, Base?>,
        steps: Int
    ) {
        bases.first?.let { runner ->
            destinations[runner.playerId] = moveFrom(
                currentBase = runner.base,
                steps = steps
            )
        }

        bases.second?.let { runner ->
            destinations[runner.playerId] = moveFrom(
                currentBase = runner.base,
                steps = steps
            )
        }

        bases.third?.let { runner ->
            destinations[runner.playerId] = moveFrom(
                currentBase = runner.base,
                steps = steps
            )
        }
    }

    private fun applyForcedAdvance(
        bases: Bases,
        destinations: MutableMap<Int, Base?>
    ) {
        val runnerOnFirst = bases.first
        val runnerOnSecond = bases.second
        val runnerOnThird = bases.third

        if (runnerOnFirst != null) {
            destinations[runnerOnFirst.playerId] = Base.SECOND

            if (runnerOnSecond != null) {
                destinations[runnerOnSecond.playerId] = Base.THIRD

                if (runnerOnThird != null) {
                    destinations[runnerOnThird.playerId] = Base.HOME
                }
            }
        }
    }

    private fun moveFrom(
        currentBase: Base,
        steps: Int
    ): Base {

        val destinationNumber = baseNumber(currentBase) + steps

        return when {
            destinationNumber >= 4 -> Base.HOME
            destinationNumber == 3 -> Base.THIRD
            destinationNumber == 2 -> Base.SECOND
            else -> Base.FIRST
        }
    }

    private fun baseNumber(base: Base): Int {
        return when (base) {
            Base.HOME -> 4
            Base.FIRST -> 1
            Base.SECOND -> 2
            Base.THIRD -> 3
        }
    }

    private fun buildResult(
        destinations: Map<Int, Base?>
    ): RunnerResult {

        var first: Runner? = null
        var second: Runner? = null
        var third: Runner? = null
        var runsScored = 0

        destinations.forEach { (playerId, destination) ->
            when (destination) {
                Base.FIRST -> {
                    check(first == null) {
                        "Two runners cannot occupy first base."
                    }

                    first = Runner(
                        playerId = playerId,
                        base = Base.FIRST
                    )
                }

                Base.SECOND -> {
                    check(second == null) {
                        "Two runners cannot occupy second base."
                    }

                    second = Runner(
                        playerId = playerId,
                        base = Base.SECOND
                    )
                }

                Base.THIRD -> {
                    check(third == null) {
                        "Two runners cannot occupy third base."
                    }

                    third = Runner(
                        playerId = playerId,
                        base = Base.THIRD
                    )
                }

                Base.HOME -> {
                    runsScored++
                }

                null -> {
                    // Runner was removed from the bases.
                }
            }
        }

        return RunnerResult(
            bases = Bases(
                first = first,
                second = second,
                third = third
            ),
            runsScored = runsScored
        )
    }
}