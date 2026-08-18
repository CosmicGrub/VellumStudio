package com.vellum.studio.util

import android.content.Context
import android.graphics.Bitmap
import androidx.print.PrintHelper

/**
 * Hands a bitmap to Android's system print dialog — Wi-Fi printers, "Save as PDF", any print
 * service the user has installed. This is deliberately how printing is scoped here: everything
 * this app can print (a finished piece, a blank coloring page, a lesson diagram) already exists
 * as a flattened Bitmap for other reasons (export, thumbnails, templates), so one small wrapper
 * around [PrintHelper] covers all of it rather than needing a per-feature print pipeline.
 */
object Printing {
    fun printBitmap(context: Context, jobName: String, bitmap: Bitmap) {
        val helper = PrintHelper(context).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
        helper.printBitmap(jobName, bitmap)
    }
}
