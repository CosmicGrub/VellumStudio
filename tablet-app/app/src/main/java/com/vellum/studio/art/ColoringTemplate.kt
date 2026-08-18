package com.vellum.studio.art

import android.graphics.Canvas

/**
 * One coloring-book page. [draw] renders line-art *strokes only* (never filled shapes) onto a
 * transparent [size]x[size] canvas, leaving every enclosed region open for the bucket-fill tool
 * or a brush to color in. Implementations should leave roughly 6-8% margin from the edges and use
 * a stroke width proportional to [size] so the art holds up at any canvas resolution.
 */
data class ColoringTemplate(
    val id: String,
    val name: String,
    val category: String,
    val draw: (canvas: Canvas, size: Int) -> Unit,
    /**
     * For templates derived from a real reference image (see ColoringTemplatesMasterworksReal):
     * the bundled full-color asset path (relative to assets/), so the UI can offer a "View
     * Original" look at the fully-realized piece alongside the blank line art. Null for every
     * procedurally-drawn template, which has no such "finished" reference to show.
     */
    val referenceAssetPath: String? = null,
    /**
     * Private-storage counterpart to [referenceAssetPath], for templates generated on-device from
     * a user's own photo (see canvas/PhotoConverter.kt + model/UserPhotoTemplateRepository.kt):
     * an *absolute file path* to the full-color reference bitmap, loaded via
     * [com.vellum.studio.util.FileBitmapCache] (BitmapFactory.decodeFile) instead of
     * [com.vellum.studio.util.AssetBitmapCache]'s context.assets stream, since these bitmaps live
     * in app-private storage rather than the bundled assets/ folder and can be created/deleted at
     * runtime rather than being fixed at build time. A template should set at most one of
     * [referenceAssetPath] / [referenceFilePath] -- both null means no "View Original" reference
     * to show (every procedurally-drawn template).
     */
    val referenceFilePath: String? = null,
)
