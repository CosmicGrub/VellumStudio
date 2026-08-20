package com.vellum.studio.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Decode-once cache for bitmaps living in app-private storage, keyed by absolute file path --
 * the private-storage counterpart to [AssetBitmapCache] (bundled assets/ files, decoded via
 * context.assets). Backing store for user-generated photo templates (see
 * com.vellum.studio.canvas.PhotoConverter / com.vellum.studio.model.UserPhotoTemplateRepository):
 * a template's `draw` closure re-decoding its line-art PNG from disk on every gallery thumbnail
 * render, rasterize, and print job would be wasteful, same reasoning as AssetBitmapCache.
 *
 * Unlike AssetBitmapCache's fixed, never-evicted set of a handful of bundled masterworks, user
 * photo templates can be created *and deleted* at runtime -- so this cache exposes [invalidate],
 * which the repository's delete path calls before removing the backing file, so a stale decoded
 * bitmap can never outlive the file it came from. There is still no size-based eviction: the
 * expected count (a user's own imported photos, browsed on one tablet) stays small enough that an
 * unbounded cache is the same reasonable trade AssetBitmapCache makes.
 */
object FileBitmapCache {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun get(absolutePath: String): Bitmap =
        cache.getOrPut(absolutePath) {
            BitmapFactory.decodeFile(absolutePath)
                ?: error("Failed to decode private-storage bitmap: $absolutePath")
        }

    /** Drops any cached decode of [absolutePath] -- call before deleting the backing file. */
    fun invalidate(absolutePath: String) {
        cache.remove(absolutePath)
    }
}
