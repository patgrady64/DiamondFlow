package com.pgdevhouse.diamondflow.model

enum class PlayAction(
    val defaultOuts: Int
) {
    SINGLE(0),
    DOUBLE(0),
    TRIPLE(0),
    HOME_RUN(0),

    WALK(0),
    STRIKEOUT(1),

    GROUND_OUT(1),
    FLY_OUT(1),
    FIELDERS_CHOICE(1),
    SACRIFICE(1),
    ERROR(0),
    HIT_BY_PITCH(0)
}