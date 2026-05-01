package org.machikoro.server.domain.utils

import org.machikoro.server.domain.enums.LandmarkType

fun LandmarkType.displayName(): String =
    name.split("_").joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }