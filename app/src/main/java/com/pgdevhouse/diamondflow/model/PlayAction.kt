package com.pgdevhouse.diamondflow.model

enum class PlayAction(
    val defaultOuts: Int,
    val countsAsAtBat: Boolean = true
) {
    SINGLE(0),
    DOUBLE(0),
    TRIPLE(0),
    HOME_RUN(0),

    WALK(0, countsAsAtBat = false),
    INTENTIONAL_WALK(0, countsAsAtBat = false),
    HIT_BY_PITCH(0, countsAsAtBat = false),
    STRIKEOUT(1),

    GROUND_OUT(1),
    FLY_OUT(1),
    FIELDERS_CHOICE(1),
    SACRIFICE(1, countsAsAtBat = false),
    SACRIFICE_BUNT(1, countsAsAtBat = false),
    SACRIFICE_FLY(1, countsAsAtBat = false),
    ERROR(0),
    DOUBLE_PLAY(2),
    TRIPLE_PLAY(3)
}
