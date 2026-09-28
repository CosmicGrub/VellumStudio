package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.vellum.studio.VellumApp

/**
 * The layer-stack composite ([CanvasEngine.flatten]'s body), split out so it can run over bitmaps
 * that are NOT the live layer bitmaps: the save pipeline composites its off-main snapshots (and
 * small pre-scaled copies for the gallery thumbnail) with exactly the same opacity / blend-mode /
 * paper-texture rules the live flatten uses, without ever touching an engine that the UI thread is
 * still mutating. [CanvasEngine.flatten] delegates here, so there is one compositing recipe, not two.
 */
object LayerFlattener {

    /** One layer's compositing inputs, bottom-to-top order. Callers pre-filter invisible layers. */
    class Input(val bitmap: Bitmap, val opacity: Float, val blendMode: LayerBlendMode)

    /**
     * Composites [inputs] onto white into a fresh [outWidth] x [outHeight] bitmap. Each input bitmap
     * is drawn stretched to the output size (a no-op when it already matches, which is the
     * full-resolution [CanvasEngine.flatten] case), so a thumbnail can be built from inputs that
     * were already downscaled. The paper texture is laid over the [canvasWidth] x [canvasHeight]
     * canvas space and scaled with it, so a thumbnail's grain matches "flatten, then downscale".
     */
    fun flatten(outWidth: Int, outHeight: Int, canvasWidth: Int, canvasHeight: Int, inputs: List<Input>): Bitmap {
        val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val dst = Rect(0, 0, outWidth, outHeight)
        for (input in inputs) {
            paint.alpha = (input.opacity.coerceIn(0f, 1f) * 255).toInt()
            paint.blendMode = input.blendMode.blendMode
            if (input.bitmap.width == outWidth && input.bitmap.height == outHeight) {
                canvas.drawBitmap(input.bitmap, 0f, 0f, paint)
            } else {
                paint.isFilterBitmap = true
                canvas.drawBitmap(input.bitmap, null, dst, paint)
                paint.isFilterBitmap = false
            }
            paint.blendMode = null
        }
        val settings = VellumApp.instance.settingsRepository
        if (settings.paperTextureEnabled) {
            paint.shader = PaperTexture.shader
            paint.blendMode = BlendMode.MULTIPLY
            paint.alpha = (PaperTexture.clampStrength(settings.paperTextureStrength) * 255).toInt()
            canvas.save()
            if (outWidth != canvasWidth || outHeight != canvasHeight) {
                canvas.scale(outWidth.toFloat() / canvasWidth, outHeight.toFloat() / canvasHeight)
            }
            canvas.drawRect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat(), paint)
            canvas.restore()
            paint.shader = null
            paint.blendMode = null
        }
        return out
    }
}
