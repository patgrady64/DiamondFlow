package com.pgdevhouse.diamondflow.engine

import com.pgdevhouse.diamondflow.model.Bases

data class RunnerResult(
    val bases: Bases,
    val runsScored: Int
)