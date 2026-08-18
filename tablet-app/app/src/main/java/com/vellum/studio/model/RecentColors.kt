package com.vellum.studio.model

import androidx.compose.runtime.mutableStateListOf

/**
 * Most-recently-used colors, newest first, deduped. Process-lifetime only (not persisted) — this
 * is a low-stakes convenience list, not something worth a disk round-trip on every color pick.
 */
object RecentColors {
    val colors = mutableStateListOf<Int>()
    private const val MAX = 12

    fun note(argb: Int) {
        colors.remove(argb)
        colors.add(0, argb)
        while (colors.size > MAX) colors.removeAt(colors.size - 1)
    }
}
