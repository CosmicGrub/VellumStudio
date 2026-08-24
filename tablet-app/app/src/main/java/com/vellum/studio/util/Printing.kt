package com.vellum.studio.util

import android.content.Context
import android.graphics.Bitmap
import androidx.print.PrintHelper
import kotlin.math.sqrt

/**
 * Hands a bitmap to Android's system print dialog — Wi-Fi printers, "Save as PDF", any print
 * service the user has installed. This is deliberately how printing is scoped here: everything
 * this app can print (a finished piece, a blank coloring page, a lesson diagram) already exists
 * as a flattened Bitmap for other reasons (export, thumbnails, templates), so one small wrapper
 * around [PrintHelper] covers all of it rather than needing a per-feature print pipeline.
 */
object Printing {

    /** The two print-quality choices EditorScreen's Print flow offers. Deliberately just these
     * two, real ones -- not a color-management system Android's print framework doesn't actually
     * expose control over. */
    enum class PrintPreset(val label: String, val description: String) {
        STANDARD(
            label = "Standard",
            description = "Prints exactly what's on the canvas, at its native resolution.",
        ),
        HIGH_DPI_ARCHIVAL(
            label = "High-DPI / Archival",
            description = "Renders the artwork at extra resolution first, for a sharper physical " +
                "print, if the canvas has memory headroom left to do that safely.",
        ),
    }

    /** Result of [preparePrintBitmap]: the bitmap to actually hand to [printBitmap], plus whether
     * [PrintPreset.HIGH_DPI_ARCHIVAL]'s upscale genuinely applied. [archivalUpscaleApplied] is
     * false both when [PrintPreset.STANDARD] was requested and when HIGH_DPI_ARCHIVAL was
     * requested but the source bitmap had no headroom left under
     * [DeviceCapabilities.maxSafeCanvasPixels] -- callers should tell the user honestly when that
     * happens rather than silently printing at standard resolution while implying otherwise. */
    data class PreparedPrint(val bitmap: Bitmap, val archivalUpscaleApplied: Boolean)

    /**
     * Applies [preset]'s resolution treatment to [bitmap]. A genuine resize pass over a
     * potentially large bitmap is real CPU/memory work -- same reasoning as
     * [com.vellum.studio.canvas.CanvasEngine.flatten] itself, callers should run this off the main
     * thread (see EditorScreen's Print flow for the pattern) rather than inline with the call that
     * actually shows the system print UI.
     */
    fun preparePrintBitmap(bitmap: Bitmap, preset: PrintPreset): PreparedPrint {
        if (preset == PrintPreset.STANDARD) return PreparedPrint(bitmap, archivalUpscaleApplied = false)

        val currentPixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (currentPixels <= 0L) return PreparedPrint(bitmap, archivalUpscaleApplied = false)

        // Archival target: double the LINEAR resolution (4x the pixel count) -- a real, meaningful
        // sharpness bump for a physical print, not a token gesture. Clamped to whatever headroom is
        // actually left under the device's safe-pixel ceiling (see DeviceCapabilities.
        // maxSafeCanvasPixels, the same "how many full-size ARGB_8888 bitmaps might realistically
        // coexist" budget the New Canvas resolution picker already trusts) rather than applied
        // unconditionally, so this can never OOM the print pipeline on a canvas that's already
        // large relative to the device's declared heap.
        val desiredLinearScale = 2f
        val ceiling = DeviceCapabilities.maxSafeCanvasPixels()
        val maxLinearScaleByCeiling = sqrt(ceiling.toDouble() / currentPixels.toDouble()).toFloat()
        val scale = minOf(desiredLinearScale, maxLinearScaleByCeiling)

        // Under 5% linear growth isn't a meaningful "archival" bump -- be honest that there was no
        // real headroom left, rather than silently upscaling by a token amount and calling it done.
        if (scale <= 1.05f) return PreparedPrint(bitmap, archivalUpscaleApplied = false)

        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        if (scaled !== bitmap) bitmap.recycle()
        return PreparedPrint(scaled, archivalUpscaleApplied = true)
    }

    /** Shows Android's system print UI for [bitmap] -- call this on the main thread (it starts an
     * Activity), after any resolution prep from [preparePrintBitmap] has already happened off it. */
    fun printBitmap(context: Context, jobName: String, bitmap: Bitmap) {
        val helper = PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
        helper.printBitmap(jobName, bitmap)
    }
}
