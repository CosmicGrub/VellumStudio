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
import com.vellum.studio.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
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

    // ignoreUnknownKeys: an unrecognized field (e.g. saved by a newer build) is dropped, not fatal.
    // coerceInputValues: a field whose value doesn't match its type (wrong JSON type, or `null` for
    // a non-nullable type) falls back to that field's Kotlin default instead of failing the whole
    // decode -- the same discipline CustomBrushRepository/PaletteRepository already use, just with
    // this extra flag, which matters more here because ProjectMeta/LayerMeta actually have defaulted
    // fields (locked, isReferenceImage, activeLayerIndex, schemaVersion) worth falling back on. This
    // still can't save a field with no default (id, widthPx, layers itself, ...) -- that's what
    // loadOrRecoverMeta's manual fallback below is for.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }

    private val projectsRoot: File
        get() = File(appContext.getExternalFilesDir(null), "projects").apply { mkdirs() }

    private fun dirFor(id: String) = File(projectsRoot, id)
    private fun metaFile(id: String) = File(dirFor(id), "metadata.json")
    private fun layersDir(id: String) = File(dirFor(id), "layers").apply { mkdirs() }
    private fun thumbFile(id: String) = File(dirFor(id), "thumbnail.png")

    /**
     * Reads project [id]'s metadata as defensively as possible, in three widening layers -- each
     * one only kicks in if the layer before it wasn't enough to produce a valid [ProjectMeta]:
     *
     * 1. Parse metadata.json, run it through [ProjectSchemaMigrator], decode normally. This is the
     *    fast path and handles every project on disk today.
     * 2. If that decode throws (a field present with a value the current model can't accept, and
     *    with no usable default for [json]'s `coerceInputValues` to fall back on), reconstruct
     *    [ProjectMeta] field-by-field in [decodeLeniently], dropping only the individual layer
     *    entries that don't decode -- not the whole project.
     * 3. If metadata.json is missing or too damaged to parse as JSON at all, [recoverFromLayerFiles]
     *    rebuilds a minimal project directly from whatever `layers/&lt;id&gt;.png` files still exist -- the
     *    actual artwork survives even when every byte of metadata describing it is gone.
     *
     * Returns null only when none of the three has anything to recover (no metadata AND no layer
     * files) -- i.e. there is genuinely no project here.
     */
    private fun loadOrRecoverMeta(id: String): ProjectMeta? {
        val mf = metaFile(id)
        if (!mf.exists()) return recoverFromLayerFiles(id)
        return runCatching {
            val root = json.parseToJsonElement(mf.readText()).jsonObject
            val migrated = ProjectSchemaMigrator.migrate(root)
            runCatching { json.decodeFromJsonElement<ProjectMeta>(migrated) }
                .getOrElse { decodeLeniently(id, migrated) }
        }.getOrElse { e ->
            DiagnosticLog.log(appContext, "ProjectRepository", "metadata.json unreadable for project $id (${e.message}); recovering from layer files")
            recoverFromLayerFiles(id)
        }
    }

    /**
     * Manually pulls [ProjectMeta]'s fields out of [obj] one at a time instead of one atomic
     * `decodeFromJsonElement` call, so a single corrupted field can be replaced with a sane
     * fallback instead of failing the entire project. [layers] gets the same treatment one level
     * down: each element is decoded independently, and any that doesn't decode is dropped (logged,
     * never silently) rather than taking every other layer down with it. Any layer PNG on disk that
     * survived but isn't referenced by a decoded [LayerMeta] is folded back in as a recovered layer
     * so its pixels are never orphaned by a metadata problem alone.
     */
    private fun decodeLeniently(id: String, obj: JsonObject): ProjectMeta {
        fun JsonElement?.str(fallback: String) = (this as? JsonPrimitive)?.contentOrNull ?: fallback
        fun JsonElement?.int(fallback: Int) = (this as? JsonPrimitive)?.intOrNull ?: fallback
        fun JsonElement?.long(fallback: Long) = (this as? JsonPrimitive)?.longOrNull ?: fallback

        val decodedLayers = (obj["layers"] as? JsonArray).orEmpty().mapIndexedNotNull { idx, element ->
            runCatching { json.decodeFromJsonElement<LayerMeta>(element) }
                .onFailure { DiagnosticLog.log(appContext, "ProjectRepository", "Dropping unreadable layer #$idx for project $id (${it.message})") }
                .getOrNull()
        }
        val referenced = decodedLayers.map { "${it.id}.png" }.toSet()
        val orphanLayers = (layersDir(id).listFiles { f -> f.extension == "png" && f.name !in referenced } ?: emptyArray())
            .sortedBy { it.name }
            .mapIndexed { i, f ->
                LayerMeta(
                    id = f.nameWithoutExtension,
                    name = "Recovered Layer",
                    opacity = 1f,
                    visible = true,
                    blendMode = LayerBlendMode.NORMAL.label,
                    order = decodedLayers.size + i,
                )
            }
        val layers = decodedLayers + orphanLayers
        val inferredDim = layers.firstNotNullOfOrNull { peekPngDimensions(File(layersDir(id), "${it.id}.png")) }

        return ProjectMeta(
            id = obj["id"].str(id),
            name = obj["name"].str("Recovered Project"),
            widthPx = obj["widthPx"].int(inferredDim?.first ?: 0),
            heightPx = obj["heightPx"].int(inferredDim?.second ?: 0),
            createdAt = obj["createdAt"].long(System.currentTimeMillis()),
            updatedAt = obj["updatedAt"].long(System.currentTimeMillis()),
            layers = layers,
            activeLayerIndex = obj["activeLayerIndex"].int(0).coerceIn(0, (layers.size - 1).coerceAtLeast(0)),
            schemaVersion = ProjectMeta.CURRENT_SCHEMA_VERSION,
        )
    }

    /**
     * Last resort: metadata.json is missing or wasn't even parseable as JSON. Rebuilds a minimal,
     * openable [ProjectMeta] directly from whatever `layers/&lt;id&gt;.png` files remain on disk -- the
     * artwork itself -- inferring canvas dimensions from one of those bitmaps. Returns null only
     * when there are no layer files either, i.e. nothing survives to recover.
     */
    private fun recoverFromLayerFiles(id: String): ProjectMeta? {
        val files = (layersDir(id).listFiles { f -> f.extension == "png" } ?: emptyArray()).sortedBy { it.name }
        if (files.isEmpty()) return null
        val dim = files.firstNotNullOfOrNull { peekPngDimensions(it) } ?: return null
        DiagnosticLog.log(appContext, "ProjectRepository", "Rebuilding project $id from ${files.size} orphaned layer file(s); metadata.json was missing or unreadable")
        val layers = files.mapIndexed { i, f ->
            LayerMeta(id = f.nameWithoutExtension, name = "Recovered Layer ${i + 1}", opacity = 1f, visible = true, blendMode = LayerBlendMode.NORMAL.label, order = i)
        }
        val now = System.currentTimeMillis()
        return ProjectMeta(
            id = id,
            name = "Recovered Project",
            widthPx = dim.first,
            heightPx = dim.second,
            createdAt = now,
            updatedAt = now,
            layers = layers,
            activeLayerIndex = 0,
            schemaVersion = ProjectMeta.CURRENT_SCHEMA_VERSION,
        )
    }

    /** Reads only a PNG's dimensions without decoding its pixels, or null if it isn't a readable PNG. */
    private fun peekPngDimensions(file: File): Pair<Int, Int>? {
        if (!file.exists()) return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        val dirs = projectsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        dirs.mapNotNull { dir ->
            val meta = loadOrRecoverMeta(dir.name) ?: return@mapNotNull null
            ProjectSummary(meta.id, meta.name, meta.widthPx, meta.heightPx, meta.updatedAt, thumbFile(meta.id).takeIf { it.exists() })
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
            schemaVersion = ProjectMeta.CURRENT_SCHEMA_VERSION,
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
            schemaVersion = ProjectMeta.CURRENT_SCHEMA_VERSION,
            // Stamped for every template-created project, bundled or user-photo-backed alike --
            // recording it costs nothing here and it's what lets openOrCreateFromTemplate find this
            // project again later. Only the user-photo-backed side of that pair actually queries it
            // today; bundled templates keep creating a fresh project on every tap, unchanged.
            sourceTemplateId = template.id,
        )
        dirFor(id).mkdirs()
        persist(meta, engine)
        meta to engine
    }

    /**
     * Finds the id of an existing project stamped with [ProjectMeta.sourceTemplateId] == [templateId],
     * if any -- a full scan of [listProjects]-style metadata rather than an index, same cost profile
     * as [listProjects] itself (this app's project counts are gallery-sized, not database-sized).
     */
    suspend fun findProjectBySourceTemplateId(templateId: String): String? = withContext(Dispatchers.IO) {
        val dirs = projectsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        dirs.firstNotNullOfOrNull { dir -> loadOrRecoverMeta(dir.name)?.takeIf { it.sourceTemplateId == templateId }?.id }
    }

    /**
     * The Coloring Book tap-handler's actual entry point (see [com.vellum.studio.ui.coloringbook.ColoringBookScreen]'s
     * `startProject`) -- deliberately not just [createFromTemplate] directly, because the two kinds
     * of [ColoringTemplate] this app has are meant to behave differently on a *repeat* tap:
     *
     * - A bundled template ([ColoringTemplate.referenceFilePath] == null) always creates a fresh
     *   project, exactly like [createFromTemplate] alone would -- like grabbing a new physical copy
     *   of the page. No behavior change from before this function existed.
     * - A user-photo-backed template (`referenceFilePath != null`, see
     *   [UserPhotoTemplateRepository.toColoringTemplate]) behaves like reopening a document instead:
     *   if a project already exists whose [ProjectMeta.sourceTemplateId] matches [template]'s id
     *   ([findProjectBySourceTemplateId]), that project's id is returned directly and nothing new is
     *   created. Only the first tap on a given "My Photos" card creates a project; every tap after
     *   that reopens the same one.
     */
    suspend fun openOrCreateFromTemplate(template: ColoringTemplate, canvasSize: Int = 2048): String {
        if (template.referenceFilePath != null) {
            findProjectBySourceTemplateId(template.id)?.let { return it }
        }
        val (meta, engine) = createFromTemplate(template, canvasSize)
        engine.layers.forEach { it.bitmap.recycle() }
        return meta.id
    }

    suspend fun loadProject(id: String): Pair<ProjectMeta, CanvasEngine>? = withContext(Dispatchers.IO) {
        val meta = loadOrRecoverMeta(id) ?: return@withContext null
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
        DiagnosticLog.log(appContext, "ProjectRepository", "Project opened (id=$id, name=${meta.name}, layers=${engine.layers.size})")
        meta to engine
    }

    suspend fun saveProject(meta: ProjectMeta, engine: CanvasEngine): ProjectMeta = withContext(Dispatchers.IO) {
        val updated = meta.copy(
            updatedAt = System.currentTimeMillis(),
            layers = engine.layers.mapIndexed { i, l -> LayerMeta(l.id, l.name, l.opacity, l.visible, l.blendMode.label, i, l.locked, l.isReferenceImage) },
            activeLayerIndex = engine.activeLayerIndex,
        )
        persist(updated, engine)
        DiagnosticLog.log(appContext, "ProjectRepository", "Project saved (id=${updated.id}, name=${updated.name}, layers=${updated.layers.size})")
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
