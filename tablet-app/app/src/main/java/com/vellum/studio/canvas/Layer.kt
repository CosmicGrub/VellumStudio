package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

/** Compositing modes exposed in the layers panel, backed by [android.graphics.BlendMode] (API 29+). */
enum class LayerBlendMode(val label: String, val blendMode: BlendMode?) {
    NORMAL("Normal", null),
    MULTIPLY("Multiply", BlendMode.MULTIPLY),
    SCREEN("Screen", BlendMode.SCREEN),
    OVERLAY("Overlay", BlendMode.OVERLAY),
    DARKEN("Darken", BlendMode.DARKEN),
    LIGHTEN("Lighten", BlendMode.LIGHTEN),
    COLOR_DODGE("Color Dodge", BlendMode.COLOR_DODGE),
    COLOR_BURN("Color Burn", BlendMode.COLOR_BURN),
    HARD_LIGHT("Hard Light", BlendMode.HARD_LIGHT),
    SOFT_LIGHT("Soft Light", BlendMode.SOFT_LIGHT),
    DIFFERENCE("Difference", BlendMode.DIFFERENCE),
    EXCLUSION("Exclusion", BlendMode.EXCLUSION),
    HUE("Hue", BlendMode.HUE),
    SATURATION("Saturation", BlendMode.SATURATION),
    COLOR("Color", BlendMode.COLOR),
    LUMINOSITY("Luminosity", BlendMode.LUMINOSITY);

    companion object {
        fun fromLabel(label: String): LayerBlendMode = entries.firstOrNull { it.label == label } ?: NORMAL
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
