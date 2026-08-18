package com.vellum.studio.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Small decode-once, cache-forever helper for bitmaps bundled under assets/. Backing store for
 * the real-masterwork coloring templates and Academy reference images -- both need the same
 * bundled JPEG/PNG decoded exactly once and reused across every gallery thumbnail render,
 * project-creation rasterize, and print job, rather than re-decoding from assets on every call.
 *
 * Process-lifetime cache (never evicted): the whole point is these bundled assets are small,
 * fixed, and few (a handful of masterworks, not a user-generated gallery), so the memory cost of
 * keeping them all decoded is trivial next to a single canvas layer bitmap.
 */
object AssetBitmapCache {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun get(context: Context, assetPath: String): Bitmap =
        cache.getOrPut(assetPath) {
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
                    ?: error("Failed to decode bundled asset: $assetPath")
            }
        }
}
