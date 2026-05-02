package org.machikoro.server.domain.utils

import org.machikoro.server.domain.enums.CardType

fun CardType.displayName(): String =
    name.split("_").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }