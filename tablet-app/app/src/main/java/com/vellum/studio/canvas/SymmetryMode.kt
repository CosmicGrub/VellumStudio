package com.vellum.studio.canvas

import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draw-time mirror/radial symmetry: while active, every dab a stroke stamps is ALSO stamped at
 * its mirrored/rotated position(s), live, through the same brush pipeline — not a post-hoc
 * "flip and merge" step. Pairs naturally with the app's existing Mandala/geometric coloring
 * content and the Foundations/Perspective courses' symmetry-adjacent exercises.
 *
 * [transforms] returns the *additional* copies only (not the identity/original point) — callers
 * that need "every copy including the original" should prepend identity themselves. Each function
 * takes canvas-space (x, y) and the canvas's own center, returning the transformed point.
 */
enum class SymmetryMode(val label: String) {
    NONE("Off"),
    VERTICAL("Vertical mirror"),
    HORIZONTAL("Horizontal mirror"),
    QUAD("4-way mirror"),
    RADIAL_4("Radial x4"),
    RADIAL_6("Radial x6"),
    RADIAL_8("Radial x8"),
    RADIAL_12("Radial x12");

    /** How many total copies (including the original) this mode draws per dab. */
    val copyCount: Int
        get() = when (this) {
            NONE -> 1
            VERTICAL, HORIZONTAL -> 2
            QUAD -> 4
            RADIAL_4 -> 4
            RADIAL_6 -> 6
            RADIAL_8 -> 8
            RADIAL_12 -> 12
        }

    fun mirrorTransforms(centerX: Float, centerY: Float): List<(Float, Float) -> PointF> = when (this) {
        NONE -> emptyList()
        VERTICAL -> listOf { x, y -> PointF(2 * centerX - x, y) }
        HORIZONTAL -> listOf { x, y -> PointF(x, 2 * centerY - y) }
        QUAD -> listOf(
            { x, y -> PointF(2 * centerX - x, y) },
            { x, y -> PointF(x, 2 * centerY - y) },
            { x, y -> PointF(2 * centerX - x, 2 * centerY - y) },
        )
        RADIAL_4, RADIAL_6, RADIAL_8, RADIAL_12 -> {
            val n = copyCount
            (1 until n).map { k ->
                val angle = (2.0 * Math.PI * k / n).toFloat()
                val cosA = cos(angle)
                val sinA = sin(angle)
                val fn: (Float, Float) -> PointF = { x, y ->
                    val dx = x - centerX
                    val dy = y - centerY
                    PointF(centerX + dx * cosA - dy * sinA, centerY + dx * sinA + dy * cosA)
                }
                fn
            }
        }
    }
}
