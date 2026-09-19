package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.Bases
import com.pgdevhouse.diamondflow.model.Runner

data class RunnerResult(
    val bases: Bases,
    val scoredRunners: List<Runner> = emptyList()
) {
    val runsScored: Int get() = scoredRunners.size
}
