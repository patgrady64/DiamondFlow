package com.pgdevhouse.diamondflow.model

data class FieldingPlay(
    val putoutPlayerName: String? = null,
    val assistPlayerNames: List<String> = emptyList(),
    val errorPlayerName: String? = null,
    val doublePlay: Boolean = false,
    val triplePlay: Boolean = false
)
