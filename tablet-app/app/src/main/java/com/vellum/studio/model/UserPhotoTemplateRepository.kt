package com.vellum.studio.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import com.vellum.studio.art.ColoringTemplate
import com.vellum.studio.canvas.PhotoConverter
import com.vellum.studio.util.DiagnosticLog
import com.vellum.studio.util.DurableFile
import com.vellum.studio.util.FileBitmapCache
import com.vellum.studio.util.JsonFileStore
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
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
 *
 * DATA SAFETY: the source photo is NOT kept, so the two converted files are the only copy of what
 * the user imported -- an index that reads as empty would orphan them all. So the index goes through
 * [JsonFileStore] (atomic writes; a damaged index is set aside as `index.json.corrupt`, never
 * overwritten), and because file names are deterministic (`photo_<uuid>_lineart.png` +
 * `_reference.jpg`) a lost or damaged index is REBUILT by pairing the files still on disk
 * ([recoverOrphans]) -- the same last-resort idea as ProjectRepository.recoverFromLayerFiles. Image
 * files are written atomically too and a failed encode (Bitmap.compress returns false on a full
 * disk) fails the save and removes the partial files instead of indexing a truncated image.
 * [delete] is a soft delete (files move to a `trash` folder) so the gallery can offer Undo.
 */
class UserPhotoTemplateRepository(private val appContext: Context, recovery: RecoveryNotices = RecoveryNotices()) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }
    private val writeLock = Mutex()

    private val baseDir: File
        get() = File(appContext.getExternalFilesDir(null), "photo_templates").apply { mkdirs() }

    private val trashDir: File
        get() = File(baseDir, TRASH_DIR)

    private val store = JsonFileStore(
        fileProvider = { File(baseDir, "index.json") },
        json = json,
        label = "My Photos",
        log = { DiagnosticLog.log(appContext, "UserPhotoTemplateRepository", it) },
        onRecovery = recovery::report,
    )

    // A fresh process cannot have an Undo snackbar pending, so the first load sweeps whatever a
    // previous run left in trash (deleted, not undone, then the app was killed) plus stray tmp files.
    private var startupSwept = false

    fun referenceFile(template: UserPhotoTemplate): File = File(baseDir, template.referenceFileName)
    fun lineArtFile(template: UserPhotoTemplate): File = File(baseDir, template.lineArtFileName)

    suspend fun list(): List<UserPhotoTemplate> = withContext(Dispatchers.IO) {
        writeLock.withLock { loadLocked().items }
    }

    private class Loaded(val items: List<UserPhotoTemplate>, val safeToWrite: Boolean)

    /**
     * Reads the index and reconciles it with the image files on disk. Caller holds [writeLock].
     * Reconciles only when the index can't be fully trusted (missing, unreadable, or entries
     * skipped) -- a healthy index is returned as is, with no directory scan.
     */
    private fun loadLocked(): Loaded {
        if (!startupSwept) {
            startupSwept = true
            runCatching { trashDir.deleteRecursively() }
            DurableFile.sweepTmp(baseDir)
        }
        val read = store.readList(UserPhotoTemplate.serializer())
        val trusted = read.state == JsonFileStore.ListRead.State.OK && read.skipped == 0
        if (trusted) return Loaded(read.items, read.safeToWrite)
        val known = read.items.map { it.id }.toSet()
        val recovered = recoverOrphans().filter { it.id !in known }
        if (recovered.isEmpty()) return Loaded(read.items, read.safeToWrite)
        val merged = read.items + recovered
        DiagnosticLog.log(appContext, "UserPhotoTemplateRepository", "Re-indexed ${recovered.size} photo template(s) found on disk without an index entry")
        if (read.safeToWrite) {
            runCatching { store.writeList(UserPhotoTemplate.serializer(), merged) }
                .onFailure { DiagnosticLog.log(appContext, "UserPhotoTemplateRepository", "Could not persist the rebuilt index (${it.message}); will rebuild again next load") }
        }
        return Loaded(merged, read.safeToWrite)
    }

    /**
     * Rebuilds index records from `photo_<uuid>_lineart.png` + `photo_<uuid>_reference.jpg` pairs.
     * What the index carried beyond the file names is gone, so a recovered entry gets a generic name
     * ("Recovered photo N", oldest first), the file's modified time, no preset, and is NOT marked
     * paint-by-number eligible (that needs a region analysis of the photo we no longer have; the
     * template is still fully usable to trace or color freely).
     */
    private fun recoverOrphans(): List<UserPhotoTemplate> {
        val lineArts = baseDir.listFiles { f -> f.isFile && f.name.startsWith("photo_") && f.name.endsWith(LINEART_SUFFIX) }
            ?: return emptyList()
        return lineArts
            .mapNotNull { lineArt ->
                val id = lineArt.name.removeSuffix(LINEART_SUFFIX)
                val reference = File(baseDir, id + REFERENCE_SUFFIX)
                if (reference.isFile) Triple(id, lineArt, reference) else null
            }
            .sortedBy { (_, lineArt, _) -> lineArt.lastModified() }
            .mapIndexed { i, (id, lineArt, reference) ->
                UserPhotoTemplate(
                    id = id,
                    name = "Recovered photo ${i + 1}",
                    createdAtMillis = lineArt.lastModified(),
                    preset = "RECOVERED",
                    isPaintByNumberEligible = false,
                    regionCount = 0,
                    referenceFileName = reference.name,
                    lineArtFileName = lineArt.name,
                )
            }
    }

    /**
     * Writes [result]'s bitmaps to private storage (reference as JPEG q82 matching
     * make_reference()'s REFERENCE_QUALITY, line-art as lossless PNG matching make_lineart()) and
     * appends a new metadata record for them.
     *
     * @throws IOException if either image could not be fully written, or the index is damaged and
     *   could not be set aside (adding to it would overwrite it). On any failure the partial files
     *   are removed, so nothing half-written is ever indexed.
     */
    @Throws(IOException::class)
    suspend fun save(name: String, preset: PhotoConverter.Preset, result: PhotoConverter.PhotoConversionResult): UserPhotoTemplate =
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                baseDir.mkdirs()
                val loaded = loadLocked()
                if (!loaded.safeToWrite) throw IOException("My Photos index is damaged and could not be set aside; not overwriting it")

                val id = "photo_${UUID.randomUUID()}"
                val referenceFileName = id + REFERENCE_SUFFIX
                val lineArtFileName = id + LINEART_SUFFIX
                val referenceFile = File(baseDir, referenceFileName)
                val lineArtFile = File(baseDir, lineArtFileName)

                try {
                    // Bitmap.compress returns false (it does not throw) when the encode fails, e.g. a
                    // full disk; DurableFile.write turns a thrown exception into "target untouched, tmp
                    // removed", so the false has to become one.
                    DurableFile.write(referenceFile) { out ->
                        if (!result.reference.compress(Bitmap.CompressFormat.JPEG, 82, out)) throw IOException("Reference image encode failed")
                    }
                    DurableFile.write(lineArtFile) { out ->
                        if (!result.lineArt.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("Line-art image encode failed")
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
                    store.writeList(UserPhotoTemplate.serializer(), loaded.items + entry)
                    entry
                } catch (t: Throwable) {
                    runCatching { referenceFile.delete() }
                    runCatching { lineArtFile.delete() }
                    throw t
                }
            }
        }

    /**
     * Removes [id] from the index and moves its files to the trash folder rather than deleting
     * them, so [restore] can bring it back (the gallery's Undo). The files are only really gone
     * after [purgeDeleted] or the next app start. A damaged, unmovable index means nothing is
     * changed and the current list is returned.
     */
    suspend fun delete(id: String): List<UserPhotoTemplate> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val loaded = loadLocked()
            if (!loaded.safeToWrite) return@withLock loaded.items
            val target = loaded.items.find { it.id == id }
            val remaining = loaded.items.filterNot { it.id == id }
            store.writeList(UserPhotoTemplate.serializer(), remaining)
            if (target != null) {
                val refFile = referenceFile(target)
                val lineFile = lineArtFile(target)
                FileBitmapCache.invalidate(refFile.absolutePath)
                FileBitmapCache.invalidate(lineFile.absolutePath)
                trashDir.mkdirs()
                moveInto(trashDir, refFile)
                moveInto(trashDir, lineFile)
            }
            remaining
        }
    }

    /** Undo of [delete]: moves [template]'s files back and re-adds its index record. */
    suspend fun restore(template: UserPhotoTemplate): List<UserPhotoTemplate> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val loaded = loadLocked()
            if (!loaded.safeToWrite) return@withLock loaded.items
            moveInto(baseDir, File(trashDir, template.referenceFileName))
            moveInto(baseDir, File(trashDir, template.lineArtFileName))
            if (loaded.items.any { it.id == template.id }) return@withLock loaded.items
            // Only re-add it if its files really made it back; an entry pointing at nothing would be worse than none.
            if (!referenceFile(template).isFile || !lineArtFile(template).isFile) return@withLock loaded.items
            val restored = loaded.items + template
            store.writeList(UserPhotoTemplate.serializer(), restored)
            restored
        }
    }

    /** Permanently removes a [delete]d template's files once its Undo window has passed. */
    suspend fun purgeDeleted(template: UserPhotoTemplate) {
        withContext(Dispatchers.IO) {
            writeLock.withLock {
                File(trashDir, template.referenceFileName).delete()
                File(trashDir, template.lineArtFileName).delete()
            }
        }
    }

    private fun moveInto(dir: File, file: File) {
        if (!file.exists()) return
        val dest = File(dir, file.name)
        if (!file.renameTo(dest)) {
            runCatching { file.copyTo(dest, overwrite = true); file.delete() }
                .onFailure { DiagnosticLog.log(appContext, "UserPhotoTemplateRepository", "Could not move ${file.name} to ${dir.name} (${it.message})") }
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

    private companion object {
        const val TRASH_DIR = "trash"
        const val LINEART_SUFFIX = "_lineart.png"
        const val REFERENCE_SUFFIX = "_reference.jpg"
    }
}
