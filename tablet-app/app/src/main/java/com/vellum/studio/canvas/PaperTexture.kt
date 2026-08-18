package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Shader
import kotlin.math.min
import kotlin.random.Random

/**
 * A small, seamlessly tileable paper-grain bitmap, generated procedurally so the app ships no
 * texture asset at all. Built once (see [bitmap]) and reused for the lifetime of the process —
 * every canvas at every zoom level tiles the same 256x256 tile via [shader]'s REPEAT mode, so
 * memory cost stays fixed regardless of how large the artwork's own canvas is.
 *
 * The grain itself is layered value noise at three octaves (coarse fiber clumps down to fine
 * speckle) rather than pure per-pixel white noise — flat white noise reads as video static, not
 * paper; overlapping a few smoothed frequencies is what makes it read as "tooth" instead.
 * Seamlessness comes from sampling each octave's lattice with wrap-around neighbors (`% size`)
 * so the left/right and top/bottom edges interpolate into each other with no seam.
 */
object PaperTexture {

    private const val TILE_SIZE = 256

    val bitmap: Bitmap by lazy { generate(TILE_SIZE, Random(20260817)) }

    /** A shader tiling [bitmap] with REPEAT in both axes — ready to hand to a [android.graphics.Paint]. */
    val shader: BitmapShader by lazy { BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }

    private fun generate(size: Int, random: Random): Bitmap {
        val octaves = listOf(
            Octave(cells = 6, weight = 0.55f),
            Octave(cells = 17, weight = 0.30f),
            Octave(cells = 47, weight = 0.15f),
        )
        val lattices = octaves.map { octave -> octave to buildLattice(octave.cells, random) }

        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var value = 0f
                for ((octave, lattice) in lattices) {
                    value += octave.weight * sampleLattice(lattice, octave.cells, x, y, size)
                }
                // Center on mid-gray with a modest spread so MULTIPLY blending (paint applies
                // this pre-scaled by "strength", see DrawingCanvasView/CanvasEngine callers)
                // darkens the fiber clumps slightly and barely touches the speckle floor,
                // instead of washing the whole canvas one uniform shade darker.
                val gray = (170 + value * 70f).toInt().coerceIn(0, 255)
                pixels[y * size + x] = Color.rgb(gray, gray, gray)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private data class Octave(val cells: Int, val weight: Float)

    /** One value per lattice point, in -1f..1f, on a `cells x cells` grid. */
    private fun buildLattice(cells: Int, random: Random): FloatArray =
        FloatArray(cells * cells) { random.nextFloat() * 2f - 1f }

    /** Bilinear-interpolated, wrap-around sample of [lattice] at pixel (x, y) of a `size x size` image. */
    private fun sampleLattice(lattice: FloatArray, cells: Int, x: Int, y: Int, size: Int): Float {
        val fx = x.toFloat() / size * cells
        val fy = y.toFloat() / size * cells
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val tx = fx - x0
        val ty = fy - y0
        val x1 = (x0 + 1) % cells
        val y1 = (y0 + 1) % cells
        val x0w = x0 % cells
        val y0w = y0 % cells

        fun at(cx: Int, cy: Int) = lattice[cy * cells + cx]

        val top = at(x0w, y0w) * (1 - tx) + at(x1, y0w) * tx
        val bottom = at(x0w, y1) * (1 - tx) + at(x1, y1) * tx
        return top * (1 - ty) + bottom * ty
    }

    /** Clamp helper shared by both flatten() and the live canvas overlay for a consistent look. */
    fun clampStrength(strength: Float): Float = min(1f, strength.coerceAtLeast(0f))
}
