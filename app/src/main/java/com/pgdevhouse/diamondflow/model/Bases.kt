package com.pgdevhouse.diamondflow.model

data class Bases(
    val first: Runner? = null,
    val second: Runner? = null,
    val third: Runner? = null
) {
    fun isEmpty(): Boolean {
        return first == null &&
                second == null &&
                third == null
    }

    fun isLoaded(): Boolean {
        return first != null &&
                second != null &&
                third != null
    }
}