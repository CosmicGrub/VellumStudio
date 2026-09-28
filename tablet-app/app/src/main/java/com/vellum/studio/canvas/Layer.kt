package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/**
 * Compositing modes exposed in the layers panel, backed by [android.graphics.BlendMode] (API 29+).
 *
 * Two strings per mode, deliberately separate because they have opposite change policies:
 *  - [label] is DISPLAY text (layers panel). It is free to be reworded or moved to a string
 *    resource / translated at any time.
 *  - [wireName] is what `metadata.json` stores, so it is FROZEN. It is the exact English label the
 *    app has always written (v0.2.x, the device branches and PC exports all share that format), which
 *    keeps every existing project loading with no schema bump. It is independent of both [label] and
 *    the enum constant's own name: renaming a constant or relabeling/localizing a mode must not
 *    reset saved layers to Normal, and the next autosave would then make that loss permanent.
 *    LayerBlendModeWireNameTest pins every entry to its historical string, so drift fails a test
 *    instead of silently corrupting projects. Never edit an existing [wireName]; a new mode gets a
 *    new, never-reused one.
 */
enum class LayerBlendMode(val wireName: String, val label: String, val blendMode: BlendMode?) {
    NORMAL("Normal", "Normal", null),
    MULTIPLY("Multiply", "Multiply", BlendMode.MULTIPLY),
    SCREEN("Screen", "Screen", BlendMode.SCREEN),
    OVERLAY("Overlay", "Overlay", BlendMode.OVERLAY),
    DARKEN("Darken", "Darken", BlendMode.DARKEN),
    LIGHTEN("Lighten", "Lighten", BlendMode.LIGHTEN),
    COLOR_DODGE("Color Dodge", "Color Dodge", BlendMode.COLOR_DODGE),
    COLOR_BURN("Color Burn", "Color Burn", BlendMode.COLOR_BURN),
    HARD_LIGHT("Hard Light", "Hard Light", BlendMode.HARD_LIGHT),
    SOFT_LIGHT("Soft Light", "Soft Light", BlendMode.SOFT_LIGHT),
    DIFFERENCE("Difference", "Difference", BlendMode.DIFFERENCE),
    EXCLUSION("Exclusion", "Exclusion", BlendMode.EXCLUSION),
    HUE("Hue", "Hue", BlendMode.HUE),
    SATURATION("Saturation", "Saturation", BlendMode.SATURATION),
    COLOR("Color", "Color", BlendMode.COLOR),
    LUMINOSITY("Luminosity", "Luminosity", BlendMode.LUMINOSITY);

    companion object {
        /**
         * The mode persisted as [wireName], or null for a string no mode has ever used (a hand-edit,
         * a mode added by a newer build). Null rather than a silent NORMAL so the caller can log it;
         * ProjectRepository falls back to NORMAL there.
         */
        fun fromWireName(wireName: String): LayerBlendMode? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * One paintable layer. [bitmap] is the full-resolution ARGB_8888 backing store; all strokes are
 * rasterized directly into it. Compose-observable fields ([opacity], [visible], [blendMode], [name])
 * drive the layers panel; [contentVersion] is bumped on every stroke so thumbnails/redraws know to refresh
 * without needing to diff bitmap contents.
 */
class Layer(
    val id: String = UUID.randomUUID().toString(),
    name: String,
    bitmap: Bitmap,
    opacity: Float = 1f,
    visible: Boolean = true,
    blendMode: LayerBlendMode = LayerBlendMode.NORMAL,
    locked: Boolean = false,
    // True only for a layer created via CanvasEngine.addImageLayer (an imported reference photo),
    // never for an ordinary drawing layer. Explicit and constructor-set rather than inferred from
    // the layer's name ("Reference") -- see PoseOverlay's "Show Pose Guide" gating in LayersPanel,
    // which needs to know unambiguously whether pose detection against this layer's bitmap is even
    // meaningful, without depending on a display name the user is free to rename.
    val isReferenceImage: Boolean = false,
) {
    /**
     * Always mutable. A stroke constructs `Canvas(bitmap)` directly around this on every touch, and
     * `Canvas()` throws `IllegalStateException` on an immutable bitmap — which is exactly what
     * `BitmapFactory.decode*` hands back unless `inMutable` is set. [ProjectRepository] sets that
     * correctly, but guarding here too means any future bitmap source can't reintroduce the crash.
     */
    var bitmap: Bitmap = ensureMutable(bitmap)
        set(value) {
            field = ensureMutable(value)
        }

    var name by mutableStateOf(name)
    var opacity by mutableStateOf(opacity.coerceIn(0f, 1f))
    var visible by mutableStateOf(visible)
    var blendMode by mutableStateOf(blendMode)
    var locked by mutableStateOf(locked)
    var contentVersion by mutableIntStateOf(0)
        private set

    fun bumpVersion() {
        contentVersion++
    }

    fun snapshot(): Bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

    fun restore(snapshot: Bitmap) {
        bitmap.eraseColor(0)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawBitmap(snapshot, 0f, 0f, null)
        bumpVersion()
    }
}

private fun ensureMutable(source: Bitmap): Bitmap =
    if (source.isMutable) source else source.copy(Bitmap.Config.ARGB_8888, true)
