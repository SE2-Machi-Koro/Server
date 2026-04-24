package org.machikoro.server.domain.utils

object LobbyCodeGenerator {

    private const val LENGTH = 7
    private val CHAR_POOL = ('A'..'Z') + ('0'..'9')

    fun generate(): String {
        return (1..LENGTH)
            .map { CHAR_POOL.random() }
            .joinToString("")
    }
}