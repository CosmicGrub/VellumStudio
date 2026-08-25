package com.vellum.studio.model

import com.vellum.studio.util.DeviceCapabilities
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    // Default false, same reasoning as `locked` above -- an older on-disk project saved before
    // this field existed still deserializes fine (kotlinx.serialization falls back to the default
    // for a key missing from the JSON) and simply reports every layer as not-a-reference-image.
    // See Layer.isReferenceImage's own doc for what this drives (the Pose Reference Overlay's
    // "Show Pose Guide" entry point in LayersPanel).
    val isReferenceImage: Boolean = false,
)

/** On-disk project record. Layer pixel data lives alongside as `layers/<id>.png`, not inline here. */
@OptIn(ExperimentalSerializationApi::class)
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
    // On-disk format version. Every project saved before this field existed has no `schemaVersion`
    // key in its metadata.json at all -- ProjectRepository always runs the raw JSON through
    // ProjectSchemaMigrator.migrate() before decoding, which is what actually resolves that missing
    // key to version 1 (see ProjectSchemaMigrator.versionOf). The default here only matters for code
    // that builds a ProjectMeta directly in memory (createProject/createFromTemplate) or in tests.
    //
    // @EncodeDefault is required and not just belt-and-suspenders: ProjectRepository's Json is
    // configured with the library's own default of `encodeDefaults = false`, which omits any field
    // left at its declared default -- and this field's default is *defined* as "whatever
    // CURRENT_SCHEMA_VERSION currently is", so a freshly-saved, genuinely-current file would
    // otherwise ALWAYS have a value equal to its own default and silently never get a
    // `schemaVersion` key at all, regardless of how many times CURRENT_SCHEMA_VERSION is bumped in
    // the future -- indistinguishable on disk from a file saved before this field existed. Confirmed
    // this the hard way against a real save on R52X101MB6W before adding this annotation.
    @EncodeDefault
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        /**
         * Bump this by exactly 1 and append one matching [ProjectSchemaMigrator.Step] whenever this
         * on-disk shape changes -- that pair is the entire cost of a future format change, instead
         * of an ad-hoc special case scattered through load paths.
         */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

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
