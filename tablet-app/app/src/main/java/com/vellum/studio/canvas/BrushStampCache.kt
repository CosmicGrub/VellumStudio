package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * Pre-rendered alpha-mask "brush tip" textures, one per distinct [Brush.hardness]/[Brush.id],
 * generated once and reused for the lifetime of the process.
 *
 * The original design built a brand-new [RadialGradient] shader for every single dab — fine for
 * a handful of stamps, but a stroke can lay down hundreds of dabs (spacing is a small fraction of
 * dab diameter), and each `RadialGradient` allocates real native Skia shader state. Doing that on
 * the main thread inside `onTouchEvent`, every dab, on every stroke, is exactly the kind of
 * per-frame allocation churn that shows up as stutter under a fast-moving S Pen — GC pressure and
 * shader setup cost compounding with every sample. An ALPHA_8 stamp texture, tinted at draw time
 * via `Paint.color`/`Paint.alpha` and positioned via a reused [android.graphics.Matrix], turns each
 * dab into a single cheap `drawBitmap` call with zero allocation.
 */
object BrushStampCache {
    private const val TEXTURE_SIZE = 256
    private val cache = HashMap<String, Bitmap>()

    fun stampFor(brush: Brush): Bitmap = cache.getOrPut(brush.id) { renderStamp(brush.hardness) }

    private fun renderStamp(hardness: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(TEXTURE_SIZE, TEXTURE_SIZE, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        val center = TEXTURE_SIZE / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val stop = hardness.coerceIn(0f, 0.98f)
        paint.shader = RadialGradient(
            center,
            center,
            center,
            intArrayOf(WHITE_OPAQUE, WHITE_TRANSPARENT),
            floatArrayOf(stop, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(center, center, center, paint)
        return bitmap
    }

    private const val WHITE_OPAQUE = -0x1 // 0xFFFFFFFF
    private const val WHITE_TRANSPARENT = 0x00FFFFFF
}
