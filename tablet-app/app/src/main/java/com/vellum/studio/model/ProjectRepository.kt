package com.vellum.studio.model

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.VisibleForTesting
import com.vellum.studio.art.ColoringTemplate
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.Layer
import com.vellum.studio.canvas.LayerBlendMode
import com.vellum.studio.canvas.LayerFlattener
import com.vellum.studio.util.DiagnosticLog
import com.vellum.studio.util.DurableFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
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
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.zip.CRC32
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
 *
 * DURABILITY MODEL (see [SaveCoordinator] for the concurrency half): every file is replaced via
 * [DurableFile] (tmp + fsync + atomic rename), never truncated in place. A save writes the changed
 * layer PNGs first, then metadata.json as the COMMIT POINT, and only then deletes layer files the
 * new metadata no longer references -- so a kill at any instant leaves either the previous project
 * or the new one, never a metadata file pointing at a deleted PNG. metadata.json keeps a
 * `metadata.json.bak` of the previous good version, and a layer PNG that can't be decoded is set
 * aside as `<id>.png.corrupt` (reported to the caller) instead of being replaced by a blank bitmap
 * that the next save would then write over the only surviving bytes. None of this changes the
 * on-disk format: `.bak`/`.corrupt` are extra sibling files older builds simply ignore, so no schema
 * migration is needed.
 *
 * OPEN FAILURES: [loadProject] returns a [LoadResult] and never throws for a project it cannot
 * open (missing, damaged canvas size, out of memory, unreadable file, newer schema). A failed open
 * produces no engine, so nothing in the editor can autosave over the project on disk, and the read
 * path creates and writes nothing on any of those outcomes.
 *
 * NEWER-SCHEMA REFUSAL: a project whose metadata.json declares a `schemaVersion` above
 * [ProjectMeta.CURRENT_SCHEMA_VERSION] was written by a newer build (the tablet/Fold device
 * branches can lag one another on the same hardware). It is listed read-only
 * ([ProjectSummary.newerSchemaVersion]), and [loadProject], [requestSave] and [renameProject] refuse
 * it with [ProjectTooNewException] without touching a file -- it is never fed to the "recovery" path
 * that would replace it with a lossy stand-in. Delete is still allowed: it is the user's explicit act.
 *
 * SOFT DELETE: [deleteProject] never destroys anything. It renames the project folder into
 * `<app-external-files>/.trash/<id>__<epochMs>/` ([TRASH_DIR_NAME]) -- same volume, so one atomic
 * rename and the bytes are untouched -- and [restoreProject] renames it back, which is why Undo can
 * promise a byte-identical project. The trash is a SIBLING of `projects/`, not a dot-folder inside
 * it, on purpose: everything that walks `projects/` ([listProjects], [findProjectBySourceTemplateId],
 * and through them the LAN SyncServer) then cannot see it, and above all cannot feed a trashed
 * folder to [recoverFromLayerFiles], which would resurrect it as a phantom "Recovered Project" (a
 * trashed folder whose metadata is missing still has layer PNGs). Those walks additionally skip any
 * dot-prefixed entry as a second, independent guard. [purgeExpiredTrash] is the only thing that
 * ever destroys trashed data automatically, after [TRASH_RETENTION_MS]; "Delete forever" is the
 * user's explicit act.
 */
class ProjectRepository internal constructor(private val appContext: Context, private val hooks: SaveHooks) {

    constructor(appContext: Context) : this(appContext, SaveHooks())

    private val coordinator = SaveCoordinator()

    private val _libraryRevision = MutableStateFlow(0)

    /**
     * Ticks whenever what the gallery would list changed: a save committed (new thumbnail and
     * updatedAt/sort position), a project was deleted, restored or renamed (and, for the Recently
     * deleted view, whenever the trash itself changed). The gallery re-lists on every tick
     * instead of once per composition, so a card can never keep showing the thumbnail from before
     * the save that finished after the gallery loaded (Back used to navigate ahead of the write).
     * Ticked at the end of the write, before its waiters are released, so anything that awaited a
     * save and then reads this (or lists projects) sees the fresh state.
     */
    val libraryRevision: StateFlow<Int> = _libraryRevision.asStateFlow()

    private fun libraryChanged() {
        _libraryRevision.update { it + 1 }
    }

    // Editors currently open, so the process-wide trim-memory callback can reach their autosavers.
    private val openAutosavers: MutableSet<EditorAutosaver> = CopyOnWriteArraySet()

    /**
     * The autosave policy for an editor that just loaded [meta]/[engine] (see [EditorAutosaver]).
     * Registered here so [onTrimMemory] reaches it; the editor must [EditorAutosaver.close] it.
     */
    fun openAutosaver(meta: ProjectMeta, engine: CanvasEngine): EditorAutosaver {
        val saver = EditorAutosaver(
            initialMeta = meta,
            engine = engine,
            save = { m, e -> requestSave(m, e) },
            log = { DiagnosticLog.log(appContext, "Autosave", "${meta.id}: $it") },
            onClose = { openAutosavers.remove(it) },
        )
        openAutosavers += saver
        return saver
    }

    /**
     * Forwarded from the Application's trim-memory callback (main thread): from UI_HIDDEN up, every
     * open editor flushes. A no-op for an editor with nothing unsaved.
     */
    fun onTrimMemory(level: Int) {
        openAutosavers.forEach { it.onTrimMemory(level) }
    }

    private val _saveFailures = MutableStateFlow<List<SaveFailureNotice>>(emptyList())

    /**
     * Save failures nobody has been told about yet, oldest first -- the app-level home for the
     * "your save failed" message. It lives here (app-scoped, like [coordinator]) rather than in
     * the editor because Back is the most valuable save trigger and it navigates away in the same
     * click: the editor's composition scope, and the SnackbarHost hanging off it, are gone before a
     * disk-full encode can finish failing, so a snackbar launched from there was silently dropped.
     * A STATE (not a replay-less event flow) on purpose: a failure that lands while no host happens
     * to be collecting (mid-navigation transition, Activity recreation) is still there when one
     * appears, and stays until [acknowledgeSaveFailure] says it was actually shown. One notice per
     * (project, kind): an autosave loop hitting the same full disk must not queue a snackbar per
     * stroke.
     */
    val saveFailures: StateFlow<List<SaveFailureNotice>> = _saveFailures.asStateFlow()

    /** Called by the host once [notice] was shown (or dismissed) so it is not shown again. */
    fun acknowledgeSaveFailure(notice: SaveFailureNotice) {
        _saveFailures.update { list -> list - notice }
    }

    private fun publishFailure(outcome: SaveOutcome): SaveOutcome {
        val failure = outcome.failure ?: return outcome
        _saveFailures.update { list ->
            list.filterNot { it.projectId == outcome.meta.id && it.failure.kind == failure.kind } +
                SaveFailureNotice(outcome.meta.id, outcome.meta.name, failure)
        }
        return outcome
    }

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

    /**
     * The trash: a sibling of [projectsRoot], deliberately outside it (see the class doc). Unlike
     * [projectsRoot] the getter creates nothing -- reading an absent trash (the start-up purge, the
     * Recently deleted view) must not leave a folder behind; only a delete makes it.
     */
    private val trashRoot: File
        get() = File(appContext.getExternalFilesDir(null), TRASH_DIR_NAME)

    /** Serializes every trash-folder mutation (delete-into, restore, delete-forever, purge) so none can act on an entry another is moving. Always taken INSIDE a project lock, never around one. */
    private val trashLock = Any()

    /**
     * The project folders a listing may treat as projects: directories under `projects/` minus any
     * dot-prefixed one. Ids are UUIDs, so a dot-prefixed name is never a project; skipping them is
     * the second guard (after the trash living outside `projects/`) against a trash-like folder
     * being "recovered" into a phantom project by [loadOrRecoverMeta].
     */
    private fun projectDirs(): Array<File> =
        projectsRoot.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()

    /**
     * The one choke point every id -> directory mapping goes through, so no caller (SyncServer today,
     * whatever comes next) can reach outside [projectsRoot] with a crafted id. An id of `..` used to
     * make `File(projectsRoot, id)` the parent -- the whole external files dir, which also holds
     * photo_templates/, palettes.json, custom_brushes.json and academy_progress.json -- and
     * [deleteProject] would have recursively deleted it. The check is on the CANONICAL path (resolves
     * `..`, `.` and symlinks) rather than a string filter, and it deliberately does NOT require the
     * UUID shape: [listProjects] feeds real directory names (including ones this app didn't mint,
     * e.g. a stray dot-prefixed folder) through here, and a strict-shape check would make those throw. The
     * UUID-shape gate for anything network-facing lives in [resolveProjectDir].
     */
    private fun dirFor(id: String): File {
        val root = projectsRoot
        val candidate = File(root, id)
        val escapes = try {
            candidate.canonicalFile.parentFile != root.canonicalFile
        } catch (e: java.io.IOException) {
            true // e.g. a NUL in the id -- canonicalization itself refuses it
        }
        require(!escapes) { "Project id does not name a direct child of the projects directory" }
        return candidate
    }
    private fun metaFile(id: String) = File(dirFor(id), "metadata.json")
    private fun layersDir(id: String) = File(dirFor(id), "layers").apply { mkdirs() }
    private fun thumbFile(id: String) = File(dirFor(id), "thumbnail.png")

    /**
     * What reading one project's metadata found. [TooNew] is deliberately its own case and NOT a
     * flavor of "unreadable": it is a perfectly good file written by a newer build, and every
     * fallback below ([decodeLeniently], `.bak`, [recoverFromLayerFiles]) would turn it into a
     * lossy stand-in that the next save then writes over the real thing.
     */
    private sealed interface MetaRead {
        class Found(val meta: ProjectMeta) : MetaRead

        /** The raw file declared [version] > [ProjectMeta.CURRENT_SCHEMA_VERSION]; the rest is read best-effort for the gallery card only. */
        class TooNew(val version: Int, val name: String?, val widthPx: Int, val heightPx: Int, val updatedAt: Long?) : MetaRead

        /**
         * A metadata file that is there but cannot produce an openable project: [UnreadableReason.INVALID_CANVAS_SIZE]
         * (decoded fine, but 0x0 or absurd, with no layer PNG to infer a real size from) or
         * [UnreadableReason.IO_ERROR] (the file exists and could not be read). Unlike "absent" this is
         * still a project the user owns: listed flagged, and never replaced by a lossy stand-in.
         */
        class Damaged(val reason: UnreadableReason, val detail: String, val name: String?, val updatedAt: Long?) : MetaRead
    }

    private fun MetaRead.TooNew.toException() = ProjectTooNewException(version, ProjectMeta.CURRENT_SCHEMA_VERSION)

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
     *
     * A fourth, cheapest-of-all layer sits in front of layer 3: if metadata.json is missing or won't
     * parse as JSON, `metadata.json.bak` (the previous good save, kept by [DurableFile]) is tried
     * first. It has the real layer order, names, opacity, blend modes and locks -- everything
     * [recoverFromLayerFiles] can only guess at -- so falling straight to the layer-file rebuild
     * (which sorts by UUID filename and renames the project "Recovered Project") is now the last
     * resort rather than the first.
     *
     * A file (metadata.json, or the `.bak` when metadata.json is unusable) whose `schemaVersion` is
     * NEWER than this build understands short-circuits all of the above as [MetaRead.TooNew]: no
     * recovery, no fallback to an older `.bak`, and nothing here ever writes -- reading is
     * byte-for-byte non-destructive, which is what lets the caller refuse cleanly.
     */
    private fun loadOrRecoverMeta(id: String): MetaRead? {
        val mf = metaFile(id)
        val primary = readMetaFile(id, mf)
        if (primary is MetaRead.Found || primary is MetaRead.TooNew) return primary
        // The file exists but could not be READ (not "could not be parsed"): the disk, not the
        // data, is the problem, so the .bak and the layer-file rebuild are no safer -- and a lossy
        // "Recovered Project" would overwrite the real metadata on the next save.
        if (primary is MetaRead.Damaged && primary.reason == UnreadableReason.IO_ERROR) return primary
        val bak = readMetaFile(id, DurableFile.bakFor(mf))
        if (bak is MetaRead.Found || bak is MetaRead.TooNew) {
            if (bak is MetaRead.Found) DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: metadata.json missing, unreadable or without a usable canvas size; restored from metadata.json.bak")
            return bak
        }
        recoverFromLayerFiles(id)?.let { return MetaRead.Found(it) }
        // Nothing recoverable: a damaged-but-present file is reported as such, "absent" only if nothing was there.
        return primary ?: bak
    }

    /** Parses+migrates+decodes one metadata file (layers 1 and 2 above); null if it's absent or not parseable JSON. */
    private fun readMetaFile(id: String, file: File): MetaRead? {
        if (!file.exists()) return null
        return try {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val migrated = try {
                ProjectSchemaMigrator.migrate(root)
            } catch (e: ProjectTooNewException) {
                DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: ${file.name} is schema v${e.projectVersion}, newer than this build's v${e.supportedVersion}; refusing to open or overwrite it")
                return newerSchemaPreview(e.projectVersion, root)
            }
            withUsableCanvasSize(
                id,
                runCatching { json.decodeFromJsonElement<ProjectMeta>(migrated) }
                    .getOrElse { decodeLeniently(id, migrated) },
                root,
            )
        } catch (e: IOException) {
            DiagnosticLog.log(appContext, "ProjectRepository", "${file.name} could not be read for project $id (${e.javaClass.simpleName}: ${e.message})")
            MetaRead.Damaged(UnreadableReason.IO_ERROR, "${file.name}: ${e.javaClass.simpleName}: ${e.message}", name = null, updatedAt = null)
        } catch (e: Exception) {
            DiagnosticLog.log(appContext, "ProjectRepository", "${file.name} unreadable for project $id (${e.message})")
            null
        }
    }

    /** The few display fields of a newer-schema file, tolerantly and without decoding it into [ProjectMeta]. */
    private fun newerSchemaPreview(version: Int, root: JsonObject): MetaRead.TooNew {
        fun prim(key: String) = root[key] as? JsonPrimitive
        return MetaRead.TooNew(
            version = version,
            name = prim("name")?.contentOrNull?.takeIf { it.isNotBlank() },
            widthPx = prim("widthPx")?.intOrNull ?: 0,
            heightPx = prim("heightPx")?.intOrNull ?: 0,
            updatedAt = prim("updatedAt")?.longOrNull,
        )
    }

    /**
     * A decoded [meta] whose canvas size cannot build an engine (0x0 from garbled or missing
     * dimensions -- `CanvasEngine(0, 0)` throws in `Bitmap.createBitmap` -- or a corrupted huge
     * number) is repaired from the first of its own layer PNGs that still reports a real size, so
     * the artwork opens with every other field intact; with no such PNG it is [MetaRead.Damaged].
     * Nothing is written here: the repaired size only reaches disk if the user then edits and saves.
     * [raw] is only for the card's timestamp on the damaged path.
     */
    private fun withUsableCanvasSize(id: String, meta: ProjectMeta, raw: JsonObject): MetaRead {
        if (meta.hasValidCanvasSize) return MetaRead.Found(meta)
        // Not layersDir(): that creates the directory, and a read must not.
        val ldir = File(dirFor(id), "layers")
        val inferred = meta.layers.firstNotNullOfOrNull { peekPngDimensions(File(ldir, "${it.id}.png")) }
        if (inferred != null) {
            val repaired = meta.copy(widthPx = inferred.first, heightPx = inferred.second)
            if (repaired.hasValidCanvasSize) {
                DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: metadata canvas size ${meta.widthPx}x${meta.heightPx} is unusable; using ${inferred.first}x${inferred.second} from a layer image")
                return MetaRead.Found(repaired)
            }
        }
        val detail = "canvas size ${meta.widthPx}x${meta.heightPx} is unusable and no layer image reports a real size"
        DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: $detail")
        return MetaRead.Damaged(
            UnreadableReason.INVALID_CANVAS_SIZE,
            detail,
            name = meta.name.takeIf { it.isNotBlank() },
            updatedAt = (raw["updatedAt"] as? JsonPrimitive)?.longOrNull,
        )
    }

    /**
     * Throws [ProjectTooNewException] if what is on disk for [id] was written by a newer build.
     * The write paths call this under the project lock BEFORE touching a single file: a project that
     * loaded fine can still be replaced underneath us (a newer build side-loaded on the same
     * hardware saving over it), and overwriting that with this build's older shape is exactly the
     * loss the refusal exists to prevent. Same precedence as reading: metadata.json decides if it
     * parses, otherwise the `.bak`. Absent/unparseable files pass -- there is nothing newer to protect.
     */
    private fun requireNotNewerOnDisk(id: String) {
        val mf = metaFile(id)
        for (file in listOf(mf, DurableFile.bakFor(mf))) {
            if (!file.exists()) continue
            val version = runCatching { ProjectSchemaMigrator.versionOf(json.parseToJsonElement(file.readText()).jsonObject) }.getOrNull() ?: continue
            if (version > ProjectMeta.CURRENT_SCHEMA_VERSION) {
                DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: refusing to write; ${file.name} is schema v$version, newer than this build's v${ProjectMeta.CURRENT_SCHEMA_VERSION}")
                throw ProjectTooNewException(version, ProjectMeta.CURRENT_SCHEMA_VERSION)
            }
            return
        }
    }

    /** True if [file] is at least parseable JSON -- the bar for being allowed to become the `.bak`. */
    private fun isParseableMeta(file: File): Boolean =
        runCatching { json.parseToJsonElement(file.readText()).jsonObject.isNotEmpty() }.getOrDefault(false)

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
        val orphanLayers = (File(dirFor(id), "layers").listFiles { f -> f.extension == "png" && f.name !in referenced } ?: emptyArray())
            .sortedBy { it.name }
            .mapIndexed { i, f ->
                LayerMeta(
                    id = f.nameWithoutExtension,
                    name = "Recovered Layer",
                    opacity = 1f,
                    visible = true,
                    blendMode = LayerBlendMode.NORMAL.wireName,
                    order = decodedLayers.size + i,
                )
            }
        val layers = decodedLayers + orphanLayers
        val inferredDim = layers.firstNotNullOfOrNull { peekPngDimensions(File(File(dirFor(id), "layers"), "${it.id}.png")) }

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
        // Not layersDir(): that creates the directory, and probing an id that isn't a project must not leave one behind.
        val files = (File(dirFor(id), "layers").listFiles { f -> f.extension == "png" } ?: emptyArray()).sortedBy { it.name }
        if (files.isEmpty()) return null
        val dim = files.firstNotNullOfOrNull { peekPngDimensions(it) } ?: return null
        DiagnosticLog.log(appContext, "ProjectRepository", "Rebuilding project $id from ${files.size} orphaned layer file(s); metadata.json was missing or unreadable")
        val layers = files.mapIndexed { i, f ->
            LayerMeta(id = f.nameWithoutExtension, name = "Recovered Layer ${i + 1}", opacity = 1f, visible = true, blendMode = LayerBlendMode.NORMAL.wireName, order = i)
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
        projectDirs().mapNotNull { dir ->
            when (val read = loadOrRecoverMeta(dir.name)) {
                null -> null
                is MetaRead.Found -> {
                    val meta = read.meta
                    ProjectSummary(meta.id, meta.name, meta.widthPx, meta.heightPx, meta.updatedAt, thumbFile(meta.id).takeIf { it.exists() })
                }
                // Listed (so the user can see it exists and delete it) but flagged read-only; the id is
                // the directory name because that is what every path here is built from.
                is MetaRead.TooNew -> ProjectSummary(
                    id = dir.name,
                    name = read.name ?: "Untitled",
                    widthPx = read.widthPx,
                    heightPx = read.heightPx,
                    updatedAt = read.updatedAt ?: metaFile(dir.name).lastModified(),
                    thumbnailFile = thumbFile(dir.name).takeIf { it.exists() },
                    newerSchemaVersion = read.version,
                )
                // Listed flagged, not hidden: hiding it would strand files nobody can see, and the card
                // is the way to the editor's error card (diagnostic log) or to Delete.
                is MetaRead.Damaged -> ProjectSummary(
                    id = dir.name,
                    name = read.name ?: "Untitled",
                    widthPx = 0,
                    heightPx = 0,
                    updatedAt = read.updatedAt ?: metaFile(dir.name).lastModified(),
                    thumbnailFile = thumbFile(dir.name).takeIf { it.exists() },
                    damage = read.reason,
                )
            }
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
            layers = engine.layers.mapIndexed { i, l -> l.toMeta(i) },
            activeLayerIndex = engine.activeLayerIndex,
            schemaVersion = ProjectMeta.CURRENT_SCHEMA_VERSION,
        )
        dirFor(id).mkdirs()
        persistNew(meta, engine) to engine
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
            layers = engine.layers.mapIndexed { i, l -> l.toMeta(i) },
            activeLayerIndex = engine.activeLayerIndex,
            schemaVersion = ProjectMeta.CURRENT_SCHEMA_VERSION,
            // Stamped for every template-created project, bundled or user-photo-backed alike --
            // recording it costs nothing here and it's what lets openOrCreateFromTemplate find this
            // project again later. Only the user-photo-backed side of that pair actually queries it
            // today; bundled templates keep creating a fresh project on every tap, unchanged.
            sourceTemplateId = template.id,
        )
        dirFor(id).mkdirs()
        persistNew(meta, engine) to engine
    }

    /**
     * Finds the id of an existing project stamped with [ProjectMeta.sourceTemplateId] == [templateId],
     * if any -- a full scan of [listProjects]-style metadata rather than an index, same cost profile
     * as [listProjects] itself (this app's project counts are gallery-sized, not database-sized).
     */
    suspend fun findProjectBySourceTemplateId(templateId: String): String? = withContext(Dispatchers.IO) {
        // A newer-schema project is skipped (its template link is not something this build can trust
        // to read); it is not opened either way, so a repeat tap makes a fresh project instead.
        projectDirs().firstNotNullOfOrNull { dir -> (loadOrRecoverMeta(dir.name) as? MetaRead.Found)?.meta?.takeIf { it.sourceTemplateId == templateId }?.id }
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

    /** A project opened from disk plus what the open had to work around, for the caller to tell the user about. */
    class LoadedProject(
        val meta: ProjectMeta,
        val engine: CanvasEngine,
        /** Names of layers whose PNG could not be decoded; each was set aside as `<id>.png.corrupt` and opened blank. */
        val quarantinedLayerNames: List<String>,
    )

    /**
     * Opens project [id] from disk as a [LoadResult]; never throws for a project it cannot open
     * (only coroutine cancellation propagates). Runs under the project lock so it can never observe
     * (or rename files out from under) a save that is mid-flight from the previous editor session.
     *
     * Every failure is decided before the caller has an engine, so there is nothing an autosave
     * could later write: a refused/damaged/out-of-memory open leaves the project directory as it
     * was found (a newer-schema refusal or a damaged-metadata verdict happens before anything is
     * swept, renamed or created; an OOM mid-decode happens before the saved-version baseline is
     * replaced and gives the partly built engine's heap back). The cause is written to the
     * diagnostic log, which the editor's error card offers to export.
     */
    suspend fun loadProject(id: String): LoadResult = withContext(Dispatchers.IO) {
        try {
            coordinator.withProjectLock(id) { loadBlocking(id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            openFailed(id, UnreadableReason.OUT_OF_MEMORY, e)
        } catch (e: IOException) {
            openFailed(id, UnreadableReason.IO_ERROR, e)
        } catch (e: Exception) {
            openFailed(id, UnreadableReason.UNEXPECTED, e)
        }
    }

    private fun openFailed(id: String, reason: UnreadableReason, t: Throwable): LoadResult.Unreadable {
        DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: open FAILED ($reason): ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(1500)}")
        return LoadResult.Unreadable(reason, "${t.javaClass.simpleName}: ${t.message}")
    }

    private fun loadBlocking(id: String): LoadResult {
        val meta = when (val read = loadOrRecoverMeta(id)) {
            null -> {
                DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: nothing on disk to open")
                return LoadResult.NotFound
            }
            is MetaRead.TooNew -> return LoadResult.TooNew(read.toException())
            is MetaRead.Damaged -> {
                DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: not opened (${read.reason}): ${read.detail}")
                return LoadResult.Unreadable(read.reason, read.detail)
            }
            is MetaRead.Found -> read.meta
        }
        val state = coordinator.stateFor(id)
        val ldir = layersDir(id)
        // An interrupted save's leftovers; the lock is held, so nothing is writing one right now.
        DurableFile.sweepTmp(dirFor(id))
        DurableFile.sweepTmp(ldir)
        val engine = CanvasEngine(meta.widthPx, meta.heightPx)
        val quarantined = mutableListOf<String>()
        val loadedVersions = HashMap<String, Int>()
        try {
            decodeLayersInto(engine, meta, id, ldir, quarantined, loadedVersions)
        } catch (t: Throwable) {
            // OOM on layer 3 of 5 must give the heap back (it is what the user needs to retry) and
            // must not leave a half-built engine behind; state.savedVersions has not been touched.
            engine.recycleAll()
            throw t
        }
        if (engine.layers.isEmpty()) engine.addLayer("Layer 1")
        engine.activeLayerIndex = meta.activeLayerIndex.coerceIn(0, engine.layers.size - 1)
        // Fresh baseline for dirty tracking: a new Layer's contentVersion restarts at 0, so versions
        // remembered from any earlier engine for this project would be meaningless.
        state.savedVersions.clear()
        state.savedVersions.putAll(loadedVersions)
        state.lastCapturedLayerIds = null
        state.clearThumbs()
        DiagnosticLog.log(appContext, "ProjectRepository", "Project opened (id=$id, name=${meta.name}, layers=${engine.layers.size})")
        return LoadResult.Ok(LoadedProject(meta, engine, quarantined))
    }

    private fun decodeLayersInto(engine: CanvasEngine, meta: ProjectMeta, id: String, ldir: File, quarantined: MutableList<String>, loadedVersions: MutableMap<String, Int>) {
        for (lm in meta.layers.sortedBy { it.order }) {
            val file = File(ldir, "${lm.id}.png")
            // BitmapFactory.decode* returns an IMMUTABLE bitmap unless inMutable is set — miss this
            // and every reopened project crashes the instant you draw, since strokes construct a
            // Canvas directly around the layer bitmap. inMutable requires software decoding (no
            // hardware Bitmap.Config), which is what we want anyway since we mutate these in place.
            // (Set in SaveHooks.decodeLayer, the seam a test uses to make a decode run out of heap.)
            var decoded: Bitmap? = null
            if (file.exists()) {
                decoded = hooks.decodeLayer(lm.id, file)
                if (decoded == null) {
                    // Used to become a silent blank layer whose next save overwrote the only
                    // surviving bytes. Set the file aside instead, so a truncated PNG stays
                    // recoverable, and tell the caller.
                    val aside = DurableFile.quarantine(file)
                    DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: layer '${lm.name}' (${lm.id}) could not be decoded; ${if (aside != null) "kept as ${aside.name}" else "COULD NOT be set aside"}, opening it blank")
                    quarantined += lm.name
                }
            } else {
                DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: layer file for '${lm.name}' (${lm.id}) is missing; opening it blank")
            }
            val bmp = decoded ?: Bitmap.createBitmap(meta.widthPx, meta.heightPx, Bitmap.Config.ARGB_8888)
            val layer = lm.toLayer(bmp) { unknown ->
                DiagnosticLog.log(appContext, "ProjectRepository", "Project $id: layer '${lm.name}' has unknown blend mode \"$unknown\"; opening it as Normal")
            }
            engine.layers.add(layer)
            // Only a layer that really came off disk counts as "already saved at this version"; a
            // blank stand-in must stay dirty so the next save writes a real file for it.
            if (decoded != null) loadedVersions[lm.id] = layer.contentVersion
        }
    }

    /**
     * Saves [engine]'s current state as project [meta], durably and without ever throwing at the
     * caller. Existing call shape kept: returns the [ProjectMeta] that was saved (or, if the save
     * failed, the one that was attempted -- use [saveProjectDurably] / [requestSave] to learn about
     * a failure). The caller's thread must be the one that mutates [engine] (the editor's main
     * thread): the layer capture below runs on it, synchronously, before this first suspends.
     */
    suspend fun saveProject(meta: ProjectMeta, engine: CanvasEngine): ProjectMeta = saveProjectDurably(meta, engine).meta

    /** [saveProject] that also reports failure (disk full, IO error, ...) as a [SaveOutcome] instead of hiding it. */
    suspend fun saveProjectDurably(meta: ProjectMeta, engine: CanvasEngine): SaveOutcome = requestSave(meta, engine).await()

    /**
     * Captures [engine] NOW, on the calling thread, and queues the write on the app-scoped
     * [SaveCoordinator]; returns a [Deferred] for the result. Deliberately not `suspend`: a caller
     * that is about to leave (Back pressed, ON_STOP) can call this synchronously, so the capture
     * has already happened before its own coroutine scope can be cancelled -- and even if the
     * caller never awaits, the save still runs to completion.
     *
     * Capture (cheap, main thread): the layer list/metadata as a value, plus one bitmap copy per
     * layer whose [Layer.contentVersion] differs from what is already on disk. Everything after
     * that (PNG encode, fsync, rename, thumbnail) touches only those copies, so a layer delete
     * (which recycles the live bitmap), undo (erase + redraw in place) or the next stroke mid-encode
     * can neither crash the save nor tear the file.
     */
    fun requestSave(meta: ProjectMeta, engine: CanvasEngine): Deferred<SaveOutcome> =
        requestSave(meta, engine, reportFailure = true)

    /**
     * [reportFailure] publishes a failed outcome on [saveFailures] -- synchronously for a capture
     * failure, from the coordinator's thread for a write failure -- so it is observable no matter
     * whether the requester is still around to await the [Deferred]. Off only for [persistNew],
     * whose caller gets the failure as a thrown exception and must not also see a stray snackbar.
     */
    private fun requestSave(meta: ProjectMeta, engine: CanvasEngine, reportFailure: Boolean): Deferred<SaveOutcome> {
        val state = coordinator.stateFor(meta.id)
        val plan = try {
            capture(meta, engine, state)
        } catch (t: Throwable) {
            // e.g. OOM copying a big layer, or a layer whose bitmap is already recycled.
            return CompletableDeferred(failureOutcome(meta, t, reportFailure))
        }
        return coordinator.submit(state, plan, run = { runSave(state, it) }, onFailure = { m, t -> failureOutcome(m, t, reportFailure) })
    }

    private fun capture(meta: ProjectMeta, engine: CanvasEngine, state: ProjectState): SavePlan {
        val layers = engine.layers.toList()
        val updated = meta.copy(
            updatedAt = System.currentTimeMillis(),
            layers = layers.mapIndexed { i, l -> l.toMeta(i) },
            activeLayerIndex = engine.activeLayerIndex,
        )
        val (thumbW, thumbH) = thumbSize(engine.widthPx, engine.heightPx)
        val captures = ArrayList<LayerCapture>(layers.size)
        try {
            val droppedBefore = state.lastCapturedLayerIds
            for (l in layers) {
                val version = l.contentVersion
                var full: Bitmap? = null
                var small: Bitmap? = null
                // Comes back after the previous captured plan dropped it (undo of a delete): that
                // plan's commit may prune this layer's saved version and sweep its PNG before THIS
                // plan runs, so "saved version matches" can't be trusted -- snapshot it (see
                // ProjectState.lastCapturedLayerIds).
                val cameBack = droppedBefore != null && l.id !in droppedBefore
                if (cameBack || state.savedVersions[l.id] != version) {
                    // PNG out of date (or never written): the one memcpy we can't avoid. The
                    // thumbnail piece is derived from this copy later, off the main thread.
                    full = l.snapshot()
                } else if (state.thumbs[l.id]?.version != version) {
                    // PNG current, but the gallery thumbnail has no cached piece for this layer yet
                    // (first save after opening): a ~1MB scaled copy, not a full-resolution one.
                    small = downscale(l.bitmap, thumbW, thumbH)
                }
                captures += LayerCapture(l.id, version, l.visible, l.opacity, l.blendMode, full, small)
            }
        } catch (t: Throwable) {
            captures.forEach { it.full?.recycle(); it.small?.recycle() }
            throw t
        }
        state.lastCapturedLayerIds = layers.mapTo(HashSet()) { it.id }
        return SavePlan(updated, captures)
    }

    /** Runs on the coordinator's IO thread, under the project lock. Any exception becomes a failed [SaveOutcome] upstream. */
    private fun runSave(state: ProjectState, plan: SavePlan): SaveOutcome {
        val meta = plan.meta
        // Before ANY file is touched (layer PNGs of a newer project would be overwritten too, not
        // just its metadata): refuse to save over a project a newer build wrote.
        requireNotNewerOnDisk(meta.id)
        dirFor(meta.id).mkdirs()
        val ldir = layersDir(meta.id)

        // 1. Changed layer PNGs, each replaced atomically. A layer whose version an EARLIER queued
        //    save already committed is skipped (the plan was captured before that save finished).
        val written = ArrayList<LayerCapture>()
        for (cap in plan.layers) {
            val full = cap.full ?: continue
            if (state.savedVersions[cap.id] == cap.version) continue
            DurableFile.write(File(ldir, "${cap.id}.png")) { out ->
                if (!hooks.encodeLayer(cap.id, full, out)) throw IOException("PNG encode failed for layer ${cap.id}")
            }
            written += cap
        }

        // 2. COMMIT POINT: metadata.json, with the previous good one rotated to .bak. Until this
        //    rename lands, a reader (or a restart after a kill) sees the previous project.
        hooks.beforeMetadataCommit(meta.id)
        DurableFile.writeText(metaFile(meta.id), json.encodeToString(meta), keepBackup = true, backupIsValid = ::isParseableMeta)
        for (cap in written) state.savedVersions[cap.id] = cap.version
        val referenced = meta.layers.map { it.id }.toSet()
        state.savedVersions.keys.filter { it !in referenced }.forEach { state.savedVersions.remove(it) }

        // 3. Only now that the new state is committed: drop layer files it no longer references and
        //    any interrupted write's tmp files. (Used to happen FIRST, so a kill between the delete
        //    and the metadata write left old metadata pointing at a PNG that no longer existed.)
        val keepNames = referenced.map { "$it.png" }.toSet()
        ldir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".tmp") || (f.name.endsWith(".png") && f.name !in keepNames)) runCatching { f.delete() }
        }
        DurableFile.sweepTmp(dirFor(meta.id))

        // 4. The gallery thumbnail is a convenience, not project data: its failure is logged, never fails the save.
        try {
            writeThumbnail(state, plan)
        } catch (t: Throwable) {
            DiagnosticLog.log(appContext, "ProjectRepository", "Thumbnail update failed for project ${meta.id} (${t.javaClass.simpleName}: ${t.message}); project itself saved fine")
        }
        DiagnosticLog.log(appContext, "ProjectRepository", "Project saved (id=${meta.id}, name=${meta.name}, layers=${meta.layers.size}, reencoded=${written.size})")
        // Last, after the thumbnail: this is what tells the gallery its card is stale.
        libraryChanged()
        return SaveOutcome(meta)
    }

    /**
     * Builds thumbnail.png from per-layer thumbnail-resolution pieces instead of a full-resolution
     * flatten of every live layer (which read the live bitmaps off-main AND allocated a canvas-sized
     * bitmap just to shrink it). A piece is derived from this save's own snapshot when the layer
     * changed, and reused from [ProjectState.thumbs] when it did not -- which is what lets unchanged
     * layers skip snapshotting entirely.
     */
    private fun writeThumbnail(state: ProjectState, plan: SavePlan) {
        val meta = plan.meta
        val (thumbW, thumbH) = thumbSize(meta.widthPx, meta.heightPx)
        for (cap in plan.layers) {
            if (cap.full != null && state.thumbs[cap.id]?.version == cap.version) continue
            val piece = cap.small ?: cap.full?.let { downscale(it, thumbW, thumbH) } ?: continue
            cap.small = null // ownership moves to the cache; the plan must not recycle it
            state.thumbs.put(cap.id, ThumbEntry(cap.version, piece))?.bitmap?.recycle()
        }
        val ids = plan.layers.map { it.id }.toSet()
        state.thumbs.keys.filter { it !in ids }.forEach { state.thumbs.remove(it)?.bitmap?.recycle() }

        val inputs = plan.layers
            .filter { it.visible && it.opacity > 0f }
            .mapNotNull { cap -> state.thumbs[cap.id]?.let { LayerFlattener.Input(it.bitmap, cap.opacity, cap.blendMode) } }
        val thumb = LayerFlattener.flatten(thumbW, thumbH, meta.widthPx, meta.heightPx, inputs)
        try {
            DurableFile.write(thumbFile(meta.id)) { out ->
                if (!thumb.compress(Bitmap.CompressFormat.PNG, 90, out)) throw IOException("thumbnail encode failed")
            }
        } finally {
            thumb.recycle()
        }
    }

    private fun thumbSize(widthPx: Int, heightPx: Int, maxDim: Int = 512): Pair<Int, Int> {
        val scale = minOf(1f, maxDim.toFloat() / maxOf(widthPx, heightPx, 1))
        return (widthPx * scale).toInt().coerceAtLeast(1) to (heightPx * scale).toInt().coerceAtLeast(1)
    }

    /** Always a NEW bitmap (never [source] itself, unlike Bitmap.createScaledBitmap at equal size), so the caller can recycle it freely. */
    private fun downscale(source: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun failureOutcome(meta: ProjectMeta, t: Throwable, reportFailure: Boolean): SaveOutcome {
        val failure = classifyFailure(t, runCatching { projectsRoot.usableSpace }.getOrNull())
        DiagnosticLog.log(appContext, "ProjectRepository", "Save FAILED for project ${meta.id} (${failure.kind}): ${failure.detail}\n${t.stackTraceToString().take(1500)}")
        val outcome = SaveOutcome(meta, failure)
        return if (reportFailure) publishFailure(outcome) else outcome
    }

    /** First save of a brand-new project (create/createFromTemplate): the engine is private to the caller, so this awaits the durable write and throws if it failed -- a project that could not be created must not look created. */
    private suspend fun persistNew(meta: ProjectMeta, engine: CanvasEngine): ProjectMeta {
        val outcome = requestSave(meta, engine, reportFailure = false).await()
        outcome.failure?.let { throw IOException("Couldn't create project: ${it.kind} ${it.detail}") }
        return outcome.meta
    }

    /** @throws ProjectTooNewException if the project on disk was saved by a newer build (it is left untouched). */
    suspend fun renameProject(meta: ProjectMeta, newName: String): ProjectMeta = withContext(Dispatchers.IO) {
        val updated = meta.copy(name = newName, updatedAt = System.currentTimeMillis())
        coordinator.withProjectLock(updated.id) {
            requireNotNewerOnDisk(updated.id)
            DurableFile.writeText(metaFile(updated.id), json.encodeToString(updated), keepBackup = true, backupIsValid = ::isParseableMeta)
        }
        libraryChanged()
        updated
    }

    /**
     * Renames the project on disk as [id], for callers (the gallery card menu) that hold only a
     * [ProjectSummary] and so must not write a stale [ProjectMeta] back. Reads the current
     * metadata.json under the project lock and rewrites it with just the name (and updatedAt)
     * changed. Returns false, touching nothing, when there is no readable metadata.json to rename:
     * a damaged or absent one is never "renamed" into a recovered stand-in. A project the editor
     * currently has open must not be renamed this way (its autosaver would write the old name back);
     * the gallery cannot be showing one.
     *
     * @throws ProjectTooNewException if the project on disk was saved by a newer build (it is left untouched).
     */
    suspend fun renameProjectById(id: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        require(isPlainProjectId(id)) { "Not a project id: $id" }
        val renamed = coordinator.withProjectLock(id) {
            requireNotNewerOnDisk(id)
            val current = (readMetaFile(id, metaFile(id)) as? MetaRead.Found)?.meta
            if (current == null) {
                false
            } else {
                val updated = current.copy(name = newName, updatedAt = System.currentTimeMillis())
                DurableFile.writeText(metaFile(id), json.encodeToString(updated), keepBackup = true, backupIsValid = ::isParseableMeta)
                true
            }
        }
        if (renamed) libraryChanged()
        renamed
    }

    /**
     * Soft delete: moves project [id]'s folder into the trash and returns the trash id to hand to
     * [restoreProject] (the gallery's Undo), or null if there was no such project. Nothing is
     * copied, re-encoded or removed -- one atomic same-volume rename -- so a restore is
     * byte-identical. Runs under the project lock, so it never lands between a save's layer writes
     * and its metadata commit.
     *
     * If the rename fails the project stays exactly where it was and this throws [IOException]; it
     * deliberately does NOT fall back to deleting, because "delete failed, nothing lost" is the only
     * acceptable failure mode for a call the user made by tapping a card menu.
     *
     * @throws IOException if the folder could not be moved into the trash.
     */
    suspend fun deleteProject(id: String): String? = withContext(Dispatchers.IO) {
        // "" resolves to the projects root itself and ".." to its parent: never a project folder.
        require(isPlainProjectId(id)) { "Not a project id: $id" }
        val trashId = coordinator.withProjectLock(id) {
            val src = dirFor(id)
            val moved = if (src.isDirectory) moveIntoTrash(id, src) else null
            val state = coordinator.stateFor(id)
            state.savedVersions.clear()
            state.lastCapturedLayerIds = null
            state.clearThumbs()
            moved
        }
        if (trashId != null) DiagnosticLog.log(appContext, "ProjectRepository", "Project $id moved to trash as $trashId")
        libraryChanged()
        trashId
    }

    private fun moveIntoTrash(id: String, src: File): String = synchronized(trashLock) {
        val root = trashRoot
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("Couldn't create the trash folder ${root.path}")
        // The timestamp is the purge clock and the uniqueness suffix: deleting, restoring and deleting
        // the same id again yields a new entry (a same-millisecond repeat just takes the next tick).
        //
        // The while(dest.exists()) loop below only guards against a stamp that is CURRENTLY occupied
        // in the trash -- it says nothing about a stamp this method already handed out for `id` and
        // that was since vacated by a restore. Delete, restore, delete again inside the same
        // millisecond (routine on a fast, otherwise-idle CI runner; caught there, not locally) then
        // reissues that exact same trash id: the restore's rename frees the old destination before
        // the second delete's exists() check ever sees it. lastTrashStamp closes that gap by
        // remembering the highest stamp ever issued for this id (across restores, for the lifetime of
        // this ProjectRepository) and refusing to go back at or below it.
        var stamp = maxOf(hooks.nowMs(), (lastTrashStamp[id] ?: 0L) + 1)
        var dest = File(root, "$id$TRASH_SEPARATOR$stamp")
        while (dest.exists()) dest = File(root, "$id$TRASH_SEPARATOR${++stamp}")
        if (!src.renameTo(dest)) throw IOException("Couldn't move project $id into the trash (${src.path} -> ${dest.path}); it was left where it was")
        lastTrashStamp[id] = stamp
        dest.name
    }

    /** Highest trash-folder stamp [moveIntoTrash] has issued for each id this process has seen, so a
     * delete that follows a restore of the same id can never reissue a stamp already handed out (see
     * [moveIntoTrash]). Read and written only inside [trashLock]. */
    private val lastTrashStamp = HashMap<String, Long>()

    /** Everything in the trash, most recently deleted first. Reads only: nothing is created, recovered or repaired. */
    suspend fun listTrash(): List<TrashedProject> = withContext(Dispatchers.IO) {
        (trashRoot.listFiles { f -> f.isDirectory } ?: emptyArray()).mapNotNull { dir ->
            // Anything that is not one of ours (no `__<epochMs>` suffix) is ignored, and so is never purged.
            val (projectId, deletedAt) = parseTrashName(dir.name) ?: return@mapNotNull null
            TrashedProject(dir.name, projectId, readTrashedName(dir), deletedAt, File(dir, "thumbnail.png").takeIf { it.exists() })
        }.sortedByDescending { it.deletedAt }
    }

    /** The name in a trashed project's metadata.json (or its `.bak`), tolerantly; never goes through [loadOrRecoverMeta]. */
    private fun readTrashedName(dir: File): String {
        val mf = File(dir, "metadata.json")
        for (file in listOf(mf, DurableFile.bakFor(mf))) {
            val name = runCatching {
                (json.parseToJsonElement(file.readText()).jsonObject["name"] as? JsonPrimitive)?.contentOrNull
            }.getOrNull()
            if (!name.isNullOrBlank()) return name
        }
        return "Untitled"
    }

    /**
     * Undo: renames trash entry [trashId] back to `projects/<id>`. False (entry left in the trash)
     * if it is gone or a project with that id exists again -- restoring never overwrites or merges.
     */
    suspend fun restoreProject(trashId: String): Boolean = withContext(Dispatchers.IO) {
        val projectId = parseTrashName(trashId)?.takeIf { isPlainProjectId(trashId) }?.first ?: return@withContext false
        val restored = coordinator.withProjectLock(projectId) {
            synchronized(trashLock) {
                val src = File(trashRoot, trashId)
                val dest = dirFor(projectId)
                when {
                    !src.isDirectory -> false
                    dest.exists() -> {
                        DiagnosticLog.log(appContext, "ProjectRepository", "Not restoring $trashId: project $projectId already exists")
                        false
                    }
                    else -> src.renameTo(dest).also {
                        if (!it) DiagnosticLog.log(appContext, "ProjectRepository", "Restore of $trashId failed: rename to ${dest.path} was refused")
                    }
                }
            }
        }
        if (restored) libraryChanged()
        restored
    }

    /** "Delete forever": permanently removes trash entry [trashId]. Only ever the user's explicit act; never called by anything automatic. */
    suspend fun deleteTrashedProject(trashId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isPlainProjectId(trashId) || parseTrashName(trashId) == null) return@withContext false
        val gone = synchronized(trashLock) { File(trashRoot, trashId).let { !it.exists() || it.deleteRecursively() } }
        libraryChanged()
        gone
    }

    /**
     * Permanently removes trash entries deleted [maxAgeMs] or more before [nowMs] (30 days by
     * default); returns how many went. Only entries this class named (`<id>__<epochMs>`) are
     * considered. The age is the timestamp in the name -- not the folder's mtime, which a rename
     * does not touch -- and an entry stamped in the future (clock set back) is simply not old.
     * Creates nothing, so a first-ever launch with no trash does no writes.
     */
    suspend fun purgeExpiredTrash(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = TRASH_RETENTION_MS): Int = withContext(Dispatchers.IO) {
        val purged = synchronized(trashLock) {
            (trashRoot.listFiles { f -> f.isDirectory } ?: emptyArray()).count { dir ->
                val deletedAt = parseTrashName(dir.name)?.second ?: return@count false
                nowMs - deletedAt >= maxAgeMs && dir.deleteRecursively().also {
                    DiagnosticLog.log(appContext, "ProjectRepository", "Trash: ${dir.name} older than the retention window; ${if (it) "purged" else "COULD NOT be purged (will retry)"}")
                }
            }
        }
        if (purged > 0) libraryChanged()
        purged
    }

    /** `<id>__<epochMs>` -> (id, epochMs); null for a name this class did not produce. UUIDs contain no underscore, so the LAST separator is the split. */
    private fun parseTrashName(name: String): Pair<String, Long>? {
        val at = name.lastIndexOf(TRASH_SEPARATOR)
        if (at <= 0) return null
        val stamp = name.substring(at + TRASH_SEPARATOR.length).toLongOrNull() ?: return null
        return name.substring(0, at) to stamp
    }

    companion object {
        /** Dot-prefixed so nothing that lists a directory for projects, media or backups mistakes it for content. */
        const val TRASH_DIR_NAME = ".trash"

        /** How long a deleted project stays restorable before [purgeExpiredTrash] removes it. */
        const val TRASH_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        private const val TRASH_SEPARATOR = "__"

        /** Where [exportProjectZipTo] stages its per-request snapshot, under cacheDir so the system may reclaim it. */
        private const val EXPORT_SNAPSHOT_DIR = "export_snapshots"

        // Kotlin's Regex.matches() is a FULL match, so a trailing CR/LF (which `$` would let through
        // in some engines) fails here.
        val PROJECT_ID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        /**
         * True for a name that is a single, plain path segment and not dot-prefixed -- the shape of
         * every project id and trash id this class produces. Guards the paths built from a caller- or
         * network-supplied id (`projects/<id>`), where "" or ".." would otherwise resolve to the
         * projects root, its parent (which holds the trash) or a sibling.
         */
        fun isPlainProjectId(id: String): Boolean =
            id.isNotEmpty() && !id.startsWith(".") && id.none { it == '/' || it == '\\' || it == '\u0000' }
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

    /**
     * Streams the project folder (metadata + layer PNGs) as a zip into [out], for the LAN sync
     * server. Returns false (and writes nothing) if [id] isn't a real project.
     *
     * No zip temp file: the previous version built `cacheDir/export_<id>.zip` fully before the first
     * response byte (which is what tripped the PC client's 10 s header timeout on big projects),
     * never deleted it, and named it from the raw id. Writing straight to [out] means time-to-first-
     * byte is immediate and no archive is left behind.
     *
     * Consistency: the project files are first COPIED into a private snapshot directory while
     * holding the project's save lock, and only then zipped and streamed with the lock released.
     * The lock is what makes the snapshot one committed state (new layer PNGs are never paired with
     * the old metadata). Holding it across the download itself would instead stall every save of
     * this project for as long as a slow Wi-Fi client takes to read, whereas copying under it costs
     * only local disk speed. Because the zip is written from the snapshot, a later autosave can't
     * rewrite a PNG between the size/CRC pass and the copy pass either (that used to abort the
     * download with a bad entry CRC). The durability side-files (.tmp/.bak/.corrupt) are left out
     * so the archive keeps exactly the shape the PC companion has always received. The snapshot
     * directory is named from a fresh UUID, never from the (peer-supplied) [id], and is always
     * removed afterwards.
     *
     * PNG entries are STORED, not deflated -- they're already deflate-compressed, so deflating them
     * again burns CPU for ~0% size win. STORED needs size + CRC before the entry header, hence the
     * cheap read-only first pass over each (now stable) snapshot file.
     *
     * Blocking: call from a worker thread, never the main thread (it waits on the save lock).
     */
    fun exportProjectZipTo(id: String, out: OutputStream): Boolean {
        val src = resolveProjectDir(id) ?: return false
        val snapshot = File(File(appContext.cacheDir, EXPORT_SNAPSHOT_DIR), UUID.randomUUID().toString())
        try {
            runBlocking { coordinator.withProjectLock(id) { copyProjectFilesForExport(src, snapshot) } }
            writeExportZip(snapshot, out)
        } finally {
            snapshot.deleteRecursively()
            // delete() on a directory only succeeds when it is empty, so this tidies the staging
            // folder once the last export finishes without ever disturbing a concurrent export's
            // snapshot; nothing is left behind in cacheDir.
            snapshot.parentFile?.delete()
        }
        return true
    }

    private fun copyProjectFilesForExport(src: File, dest: File) {
        dest.mkdirs()
        src.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".bak") && !it.name.contains(".corrupt") }
            .forEach { f ->
                val target = File(dest, f.relativeTo(src).path)
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
            }
    }

    private fun writeExportZip(dir: File, out: OutputStream) {
        ZipOutputStream(out).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                // invariantSeparatorsPath: zip entry names must use '/', whatever the host File uses.
                val entry = ZipEntry(f.relativeTo(dir).invariantSeparatorsPath)
                if (f.extension.equals("png", ignoreCase = true)) {
                    val crc = CRC32()
                    var size = 0L
                    f.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            crc.update(buf, 0, n)
                            size += n
                        }
                    }
                    entry.method = ZipEntry.STORED
                    entry.size = size
                    entry.compressedSize = size
                    entry.crc = crc.value
                }
                zos.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    /**
     * In-module test access to a project's directory, for tests that inspect or damage on-disk state
     * directly. Still traversal-safe (it goes through [dirFor]) but skips [resolveProjectDir]'s UUID
     * gate and existence check on purpose. Not public API: anything network- or peer-facing MUST use
     * [resolveProjectDir]; the audit removed the raw-id accessor from the public surface, and
     * `otherwise = PRIVATE` makes lint flag a production caller sneaking it back in.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun projectDir(id: String): File = dirFor(id)

    /**
     * Network-facing id -> project directory lookup: null unless [id] is a canonical UUID (the only
     * shape [createProject]/[createFromTemplate] ever mint), resolves to a direct child of
     * [projectsRoot] (belt and braces on top of the regex, via [dirFor]) and actually exists as a
     * directory. Because non-UUID names never match, the `.trash` folder and every other non-project
     * sibling are unreachable through here too.
     */
    fun resolveProjectDir(id: String): File? {
        if (!PROJECT_ID_REGEX.matches(id)) return null
        val dir = try {
            dirFor(id)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return dir.takeIf { it.isDirectory }
    }
}
