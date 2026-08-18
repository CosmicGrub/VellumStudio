package com.vellum.studio.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import com.vellum.studio.art.ColoringTemplate
import com.vellum.studio.canvas.PhotoConverter
import com.vellum.studio.util.FileBitmapCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * One user-generated photo template's metadata -- the reference/line-art bitmaps themselves live
 * as sibling files (see [UserPhotoTemplateRepository.referenceFile]/[lineArtFile]), this is just
 * the small index record. [preset] is stored as [PhotoConverter.Preset.name] (plain string, not
 * the enum itself, so a future preset rename/reorder can't silently corrupt old saved records the
 * way an ordinal-based encoding would).
 */
@Serializable
data class UserPhotoTemplate(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val preset: String,
    val isPaintByNumberEligible: Boolean,
    val regionCount: Int,
    val referenceFileName: String,
    val lineArtFileName: String,
)

/**
 * Persists [PhotoConverter] output -- one small JSON metadata index (same load-all/mutate/
 * save-all-under-a-lock pattern as [CustomBrushRepository]) plus the reference/line-art bitmaps
 * as sibling files under a private "photo_templates" subdirectory of app-external storage. Unlike
 * the bundled masterworks (fixed at build time, shipped in assets/), these are created and
 * deleted by the user at runtime, so this is a real repository with save/delete, not just a
 * static list.
 *
 * [toColoringTemplate] is what makes a saved photo template a drop-in match for the existing
 * gallery, not a parallel system: it returns the exact same [ColoringTemplate] shape
 * [com.vellum.studio.art.ColoringTemplatesMasterworksReal] already produces for bundled
 * masterworks, just backed by [ColoringTemplate.referenceFilePath] + [FileBitmapCache]
 * (BitmapFactory.decodeFile) instead of [ColoringTemplate.referenceAssetPath] +
 * [com.vellum.studio.util.AssetBitmapCache] (context.assets) -- every other call site that
 * already consumes `ColoringTemplate` generically (gallery thumbnailing, rasterize-to-layer,
 * paint-by-number's region detector, printing) needs no changes to also handle these.
 */
class UserPhotoTemplateRepository(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val writeLock = Mutex()

    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "photo_templates").apply { mkdirs() }

    private val indexFile: File
        get() = File(baseDir, "index.json")

    fun referenceFile(template: UserPhotoTemplate): File = File(baseDir, template.referenceFileName)
    fun lineArtFile(template: UserPhotoTemplate): File = File(baseDir, template.lineArtFileName)

    suspend fun list(): List<UserPhotoTemplate> = withContext(Dispatchers.IO) { loadBlocking() }

    private fun loadBlocking(): List<UserPhotoTemplate> {
        if (!indexFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<UserPhotoTemplate>>(indexFile.readText()) }.getOrElse { emptyList() }
    }

    private fun saveBlocking(templates: List<UserPhotoTemplate>) {
        indexFile.writeText(json.encodeToString(templates))
    }

    /**
     * Writes [result]'s bitmaps to private storage (reference as JPEG q82 matching
     * make_reference()'s REFERENCE_QUALITY, line-art as lossless PNG matching make_lineart()) and
     * appends a new metadata record for them.
     */
    suspend fun save(name: String, preset: PhotoConverter.Preset, result: PhotoConverter.PhotoConversionResult): UserPhotoTemplate =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                baseDir.mkdirs()
                val id = "photo_${UUID.randomUUID()}"
                val referenceFileName = "${id}_reference.jpg"
                val lineArtFileName = "${id}_lineart.png"

                File(baseDir, referenceFileName).outputStream().use { out ->
                    result.reference.compress(Bitmap.CompressFormat.JPEG, 82, out)
                }
                File(baseDir, lineArtFileName).outputStream().use { out ->
                    result.lineArt.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val entry = UserPhotoTemplate(
                    id = id,
                    name = name,
                    createdAtMillis = System.currentTimeMillis(),
                    preset = preset.name,
                    isPaintByNumberEligible = result.isPaintByNumberEligible,
                    regionCount = result.regionCount,
                    referenceFileName = referenceFileName,
                    lineArtFileName = lineArtFileName,
                )
                saveBlocking(loadBlocking() + entry)
                entry
            }
        }

    suspend fun delete(id: String): List<UserPhotoTemplate> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val current = loadBlocking()
            val target = current.find { it.id == id }
            val remaining = current.filterNot { it.id == id }
            saveBlocking(remaining)
            if (target != null) {
                val refFile = referenceFile(target)
                val lineFile = lineArtFile(target)
                FileBitmapCache.invalidate(refFile.absolutePath)
                FileBitmapCache.invalidate(lineFile.absolutePath)
                refFile.delete()
                lineFile.delete()
            }
            remaining
        }
    }

    /**
     * Adapts a saved [UserPhotoTemplate] into the same [ColoringTemplate] shape every other
     * gallery entry uses -- `draw` letterbox-fits the decoded line-art into the square project
     * canvas exactly like [com.vellum.studio.art.ColoringTemplatesMasterworksReal.realTemplate]
     * does for bundled masterworks.
     */
    fun toColoringTemplate(template: UserPhotoTemplate): ColoringTemplate {
        val lineArtPath = lineArtFile(template).absolutePath
        val referencePath = referenceFile(template).absolutePath
        return ColoringTemplate(
            id = template.id,
            name = template.name,
            category = "My Photos",
            referenceFilePath = referencePath,
            draw = { canvas, size ->
                val bitmap = FileBitmapCache.get(lineArtPath)
                val s = size.toFloat()
                val margin = s * 0.05f
                val available = s - margin * 2f
                val scale = minOf(available / bitmap.width, available / bitmap.height)
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                val left = (s - drawW) / 2f
                val top = (s - drawH) / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
            },
        )
    }
}
