package com.vellum.studio.model

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.provider.MediaStore
import com.vellum.studio.art.ColoringTemplate
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.Layer
import com.vellum.studio.canvas.LayerBlendMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Owns on-disk project storage under `<app-external-files>/projects/<id>/`:
 *   metadata.json      – [ProjectMeta]
 *   layers/<layerId>.png
 *   thumbnail.png       – flattened preview for the gallery grid
 *
 * App-specific external storage needs no runtime permission on API 29+ and isn't visible to other
 * apps, which is why it (rather than shared storage) is the project format's home; [exportToGallery]
 * is the deliberate, explicit bridge out to the user's Pictures.
 */
class ProjectRepository(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val projectsRoot: File
        get() = File(appContext.getExternalFilesDir(null), "projects").apply { mkdirs() }

    private fun dirFor(id: String) = File(projectsRoot, id)
    private fun metaFile(id: String) = File(dirFor(id), "metadata.json")
    private fun layersDir(id: String) = File(dirFor(id), "layers").apply { mkdirs() }
    private fun thumbFile(id: String) = File(dirFor(id), "thumbnail.png")

    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val dirs = projectsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        dirs.mapNotNull { dir ->
            val mf = File(dir, "metadata.json")
            if (!mf.exists()) return@mapNotNull null
            runCatching {
                val meta = json.decodeFromString<ProjectMeta>(mf.readText())
                ProjectSummary(meta.id, meta.name, meta.widthPx, meta.heightPx, meta.updatedAt, thumbFile(meta.id).takeIf { it.exists() })
            }.getOrNull()
        }.sortedByDescending { it.updatedAt }
    }

    suspend fun createProject(name: String, widthPx: Int, heightPx: Int): Pair<ProjectMeta, CanvasEngine> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val engine = CanvasEngine(widthPx, heightPx)
        engine.addLayer("Layer 1")
        val now = System.currentTimeMillis()
        val meta = ProjectMeta(
            id = id,
            name = name,
            widthPx = widthPx,
            heightPx = heightPx,
            createdAt = now,
            updatedAt = now,
            layers = engine.layers.mapIndexed { i, l -> LayerMeta(l.id, l.name, l.opacity, l.visible, l.blendMode.label, i, l.locked, l.isReferenceImage) },
            activeLayerIndex = engine.activeLayerIndex,
        )
        dirFor(id).mkdirs()
        persist(meta, engine)
        meta to engine
    }

    /**
     * A coloring-book page: a blank, active "Coloring" layer underneath a locked "Line Art" layer
     * rendered from [template]. Bucket fill and brushes on the Coloring layer are naturally bounded
     * by whatever's on Line Art — see [CanvasEngine.boundaryMaskAbove] — with no special-case
     * plumbing needed beyond "line art sits above the layer you're coloring on."
     */
    suspend fun createFromTemplate(template: ColoringTemplate, canvasSize: Int = 2048): Pair<ProjectMeta, CanvasEngine> = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val engine = CanvasEngine(canvasSize, canvasSize)

        engine.addLayer("Coloring")

        val lineArtBitmap = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        template.draw(Canvas(lineArtBitmap), canvasSize)
        engine.layers.add(Layer(name = "Line Art", bitmap = lineArtBitmap, locked = true))
        engine.activeLayerIndex = 0

        val now = System.currentTimeMillis()
        val meta = ProjectMeta(
            id = id,
            name = template.name,
            widthPx = canvasSize,
            heightPx = canvasSize,
            createdAt = now,
            updatedAt = now,
            layers = engine.layers.mapIndexed { i, l -> LayerMeta(l.id, l.name, l.opacity, l.visible, l.blendMode.label, i, l.locked, l.isReferenceImage) },
            activeLayerIndex = engine.activeLayerIndex,
        )
        dirFor(id).mkdirs()
        persist(meta, engine)
        meta to engine
    }

    suspend fun loadProject(id: String): Pair<ProjectMeta, CanvasEngine>? = withContext(Dispatchers.IO) {
        val mf = metaFile(id)
        if (!mf.exists()) return@withContext null
        val meta = json.decodeFromString<ProjectMeta>(mf.readText())
        val engine = CanvasEngine(meta.widthPx, meta.heightPx)
        for (lm in meta.layers.sortedBy { it.order }) {
            val file = File(layersDir(id), "${lm.id}.png")
            // BitmapFactory.decode* returns an IMMUTABLE bitmap unless inMutable is set — miss this
            // and every reopened project crashes the instant you draw, since strokes construct a
            // Canvas directly around the layer bitmap. inMutable requires software decoding (no
            // hardware Bitmap.Config), which is what we want anyway since we mutate these in place.
            val bmp = if (file.exists()) {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 })
                    ?: Bitmap.createBitmap(meta.widthPx, meta.heightPx, Bitmap.Config.ARGB_8888)
            } else {
                Bitmap.createBitmap(meta.widthPx, meta.heightPx, Bitmap.Config.ARGB_8888)
            }
            engine.layers.add(
                Layer(
                    id = lm.id,
                    name = lm.name,
                    bitmap = bmp,
                    opacity = lm.opacity,
                    visible = lm.visible,
                    blendMode = LayerBlendMode.fromLabel(lm.blendMode),
                    locked = lm.locked,
                    isReferenceImage = lm.isReferenceImage,
                ),
            )
        }
        if (engine.layers.isEmpty()) engine.addLayer("Layer 1")
        engine.activeLayerIndex = meta.activeLayerIndex.coerceIn(0, engine.layers.size - 1)
        meta to engine
    }

    suspend fun saveProject(meta: ProjectMeta, engine: CanvasEngine): ProjectMeta = withContext(Dispatchers.IO) {
        val updated = meta.copy(
            updatedAt = System.currentTimeMillis(),
            layers = engine.layers.mapIndexed { i, l -> LayerMeta(l.id, l.name, l.opacity, l.visible, l.blendMode.label, i, l.locked, l.isReferenceImage) },
            activeLayerIndex = engine.activeLayerIndex,
        )
        persist(updated, engine)
        updated
    }

    suspend fun renameProject(meta: ProjectMeta, newName: String): ProjectMeta = withContext(Dispatchers.IO) {
        val updated = meta.copy(name = newName, updatedAt = System.currentTimeMillis())
        metaFile(updated.id).writeText(json.encodeToString(updated))
        updated
    }

    private fun persist(meta: ProjectMeta, engine: CanvasEngine) {
        dirFor(meta.id).mkdirs()
        val ldir = layersDir(meta.id)
        val validNames = engine.layers.map { "${it.id}.png" }.toSet()
        ldir.listFiles()?.forEach { f -> if (f.name !in validNames) f.delete() }
        for (layer in engine.layers) {
            FileOutputStream(File(ldir, "${layer.id}.png")).use { out ->
                layer.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        metaFile(meta.id).writeText(json.encodeToString(meta))
        val thumb = createThumbnail(engine)
        FileOutputStream(thumbFile(meta.id)).use { out -> thumb.compress(Bitmap.CompressFormat.PNG, 90, out) }
        thumb.recycle()
    }

    private fun createThumbnail(engine: CanvasEngine, maxDim: Int = 512): Bitmap {
        val flat = engine.flatten()
        val scale = maxDim.toFloat() / maxOf(flat.width, flat.height)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(flat, (flat.width * scale).toInt().coerceAtLeast(1), (flat.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            flat
        }
        if (bmp !== flat) flat.recycle()
        return bmp
    }

    suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        dirFor(id).deleteRecursively()
        Unit
    }

    /** Flattens and inserts a PNG into the device gallery under Pictures/Vellum Studio. */
    suspend fun exportToGallery(meta: ProjectMeta, engine: CanvasEngine): Uri? = withContext(Dispatchers.IO) {
        val flat = engine.flatten()
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${meta.name}_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Vellum Studio")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
        // openOutputStream can legitimately return null (a provider/storage hiccup) - without this
        // check the pending row still gets finalized and a "success" Uri returned, leaving a
        // permanent 0-byte PNG in the user's gallery while the UI reports the export succeeded.
        val wrote = resolver.openOutputStream(uri)?.use { out -> flat.compress(Bitmap.CompressFormat.PNG, 100, out) } ?: false
        flat.recycle()
        if (!wrote) {
            resolver.delete(uri, null, null)
            return@withContext null
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri
    }

    /** Zips the project folder (metadata + layer PNGs) for the LAN sync server / share sheet. */
    suspend fun exportProjectZip(id: String): File = withContext(Dispatchers.IO) {
        val src = dirFor(id)
        val zipFile = File(appContext.cacheDir, "export_$id.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(src).path))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        zipFile
    }

    fun projectDir(id: String): File = dirFor(id)
}
