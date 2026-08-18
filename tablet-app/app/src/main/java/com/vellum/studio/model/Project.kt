package com.vellum.studio.model

import com.vellum.studio.util.DeviceCapabilities
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class LayerMeta(
    val id: String,
    val name: String,
    val opacity: Float,
    val visible: Boolean,
    val blendMode: String,
    val order: Int,
    val locked: Boolean = false,
)

/** On-disk project record. Layer pixel data lives alongside as `layers/<id>.png`, not inline here. */
@Serializable
data class ProjectMeta(
    val id: String,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val layers: List<LayerMeta>,
    val activeLayerIndex: Int = 0,
)

/** Lightweight row for the gallery grid — no bitmaps loaded until a project is actually opened. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val updatedAt: Long,
    val thumbnailFile: File?,
)

/** A named starting size for the "New Canvas" dialog. */
data class CanvasSizePreset(val label: String, val widthPx: Int, val heightPx: Int)

object CanvasSizePresets {
    val all = listOf(
        CanvasSizePreset("Square · 2048", 2048, 2048),
        CanvasSizePreset("Portrait · 1536×2048", 1536, 2048),
        CanvasSizePreset("Landscape · 2048×1536", 2048, 1536),
        CanvasSizePreset("Tablet Screen · 1440×2304", 1440, 2304),
        CanvasSizePreset("Print A4 @150dpi · 1240×1754", 1240, 1754),
        CanvasSizePreset("Wide · 2560×1440", 2560, 1440),
        // Higher-resolution "Studio" tier, added once the app actually had a device-aware ceiling
        // to gate them behind (see availablePresets()) -- previously 2560x1440 was the flat cap
        // for every device regardless of how much more headroom the hardware actually had.
        CanvasSizePreset("Studio Square · 4096", 4096, 4096),
        CanvasSizePreset("Studio Portrait · 3072×4096", 3072, 4096),
        CanvasSizePreset("Studio Landscape · 4096×3072", 4096, 3072),
    )

    /**
     * [all], filtered to what [DeviceCapabilities.maxSafeCanvasPixels] says this specific running
     * device can safely offer — a capability-aware ceiling instead of every device seeing the
     * same flat preset list regardless of available memory. Always returns at least one preset
     * (the smallest) even on a very constrained device, so the New Canvas dialog is never empty.
     */
    fun availablePresets(): List<CanvasSizePreset> {
        val maxPixels = DeviceCapabilities.maxSafeCanvasPixels()
        val fits = all.filter { it.widthPx.toLong() * it.heightPx.toLong() <= maxPixels }
        return fits.ifEmpty { listOf(all.minBy { it.widthPx.toLong() * it.heightPx.toLong() }) }
    }
}
