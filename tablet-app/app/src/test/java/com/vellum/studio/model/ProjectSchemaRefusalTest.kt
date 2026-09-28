package com.vellum.studio.model

import android.graphics.Bitmap
import android.graphics.Color
import com.vellum.studio.VellumApp
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.Layer
import com.vellum.studio.canvas.LayerBlendMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What [ProjectRepository] does with a project a NEWER build saved, and the on-disk blend-mode
 * format. Before this, a `schemaVersion` above [ProjectMeta.CURRENT_SCHEMA_VERSION] made
 * [ProjectSchemaMigrator.migrate] throw inside a `runCatching` that fell through to
 * `recoverFromLayerFiles`: the gallery listed a "Recovered Project" (names, order, opacity and
 * blend modes all reset), the editor opened it, and the next autosave/Back overwrote the real
 * metadata.json with that lossy record. The migrator's own test only proved `migrate()` throws;
 * nothing tested what the repository did with the refusal -- these do, against real files.
 *
 * `@GraphicsMode(NATIVE)` + [VellumApp] for the same reasons as [ProjectDurabilityTest] (real PNG
 * bytes; the thumbnail step reads VellumApp.instance). A "restart" is a fresh [ProjectRepository]
 * over the same directory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = VellumApp::class)
class ProjectSchemaRefusalTest {

    private val app = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }

    private fun repo() = ProjectRepository(app, SaveHooks())

    private fun metaFile(repo: ProjectRepository, id: String) = File(repo.projectDir(id), "metadata.json")

    /** Every file (path -> bytes) and directory (path -> null) under [dir]: the whole "is anything different?" question. */
    private fun snapshot(dir: File): Map<String, List<Byte>?> =
        dir.walkTopDown().filter { it != dir }.associate { f ->
            f.relativeTo(dir).path to (if (f.isFile) f.readBytes().toList() else null)
        }

    /** Rewrites [id]'s metadata.json exactly as a newer build would have left it: a higher schemaVersion plus a field this build has never heard of. */
    private fun rewriteAsFromTheFuture(repo: ProjectRepository, id: String, version: Int = 99) {
        val mf = metaFile(repo, id)
        val root = json.parseToJsonElement(mf.readText()).jsonObject
        val future = buildJsonObject {
            root.forEach { (k, v) -> if (k != "schemaVersion") put(k, v) }
            put("schemaVersion", JsonPrimitive(version))
            put("futureOnlyField", JsonPrimitive("v$version data this build cannot interpret"))
        }
        mf.writeText(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), future))
    }

    private fun paint(layer: Layer, color: Int) {
        layer.bitmap.eraseColor(color)
        layer.bumpVersion()
    }

    /** A saved two-layer project named [name], returned as (meta, engine) of the still-open editor session. */
    private fun savedProject(repo: ProjectRepository, name: String = "From The Future"): Pair<ProjectMeta, CanvasEngine> = runBlocking {
        val (meta0, engine) = repo.createProject(name, 64, 64)
        engine.addLayer("Ink")
        engine.layers[0].opacity = 0.4f
        engine.layers[1].blendMode = LayerBlendMode.MULTIPLY
        engine.layers.forEach { paint(it, Color.BLUE) }
        val outcome = repo.saveProjectDurably(meta0, engine)
        assertTrue(outcome.failure?.detail, outcome.saved)
        outcome.meta to engine
    }

    // ------------------------------------------------------------------ refusal: read paths

    @Test
    fun `a newer-schema project is byte-unchanged after list and load and both leave the directory exactly as it was`() = runBlocking {
        val (meta, _) = savedProject(repo())
        rewriteAsFromTheFuture(repo(), meta.id)
        val dir = repo().projectDir(meta.id)
        // A stale tmp file a normal load would sweep; a refused load must not touch even that.
        File(dir, "layers/leftover.png.tmp").writeBytes(byteArrayOf(1, 2, 3))
        val mf = metaFile(repo(), meta.id)
        val metadataBefore = mf.readBytes()
        val before = snapshot(dir)

        val fresh = repo() // a new process: no in-memory state to lean on
        val listed = fresh.listProjects()
        assertEquals(1, listed.size)
        try {
            fresh.loadProject(meta.id)
            fail("loading a newer-schema project must be refused")
        } catch (e: ProjectTooNewException) {
            assertEquals(99, e.projectVersion)
            assertEquals(ProjectMeta.CURRENT_SCHEMA_VERSION, e.supportedVersion)
        }
        try {
            fresh.loadProjectReporting(meta.id)
            fail("loadProjectReporting must refuse too")
        } catch (_: ProjectTooNewException) {
        }

        assertTrue("metadata.json must be byte-identical", metadataBefore.contentEquals(mf.readBytes()))
        assertEquals("no file added, removed or altered anywhere in the project", before, snapshot(dir))
    }

    @Test
    fun `the gallery lists a newer-schema project by its real name flagged read-only instead of as a Recovered Project`() = runBlocking {
        val (meta, _) = savedProject(repo(), name = "Sunset Study")
        rewriteAsFromTheFuture(repo(), meta.id, version = 7)

        val summary = repo().listProjects().single()

        assertEquals(meta.id, summary.id)
        assertEquals("Sunset Study", summary.name)
        assertEquals(64, summary.widthPx)
        assertEquals(7, summary.newerSchemaVersion)
        assertFalse(summary.isOpenable)
        assertNotNull("the existing thumbnail still shows on the card", summary.thumbnailFile)
    }

    @Test
    fun `an ordinary project still lists as openable with no newer version`() = runBlocking {
        savedProject(repo(), name = "Regular")
        val summary = repo().listProjects().single()
        assertTrue(summary.isOpenable)
        assertNull(summary.newerSchemaVersion)
    }

    @Test
    fun `a newer metadata json is not shadowed by an older bak or by recovery from layer files`() = runBlocking {
        val (meta, engine) = savedProject(repo())
        // A second save rotates the v1 metadata into .bak, so an older-schema .bak exists on disk.
        paint(engine.layers[0], Color.RED)
        assertTrue(repo().let { r -> r.loadProjectReporting(meta.id)!!.let { r.saveProjectDurably(it.meta, it.engine).saved } })
        val dir = repo().projectDir(meta.id)
        assertTrue(File(dir, "metadata.json.bak").exists())
        rewriteAsFromTheFuture(repo(), meta.id)
        val before = snapshot(dir)

        val summary = repo().listProjects().single()
        assertEquals("the older .bak must not be silently loaded in its place", 99, summary.newerSchemaVersion)
        assertEquals("Recovered Project must never appear", "From The Future", summary.name)
        try {
            repo().loadProject(meta.id)
            fail()
        } catch (_: ProjectTooNewException) {
        }
        assertEquals(before, snapshot(dir))
    }

    @Test
    fun `a newer bak with an unparseable metadata json is refused rather than rebuilt from layer files`() = runBlocking {
        val (meta, engine) = savedProject(repo())
        paint(engine.layers[0], Color.RED)
        repo().let { r -> r.loadProjectReporting(meta.id)!!.let { r.saveProjectDurably(it.meta, it.engine) } }
        val dir = repo().projectDir(meta.id)
        val bak = File(dir, "metadata.json.bak")
        assertTrue(bak.exists())
        // metadata.json torn mid-write; the .bak was written by the newer build.
        bak.writeText(metaFile(repo(), meta.id).readText().replace("\"schemaVersion\": 1", "\"schemaVersion\": 42"))
        metaFile(repo(), meta.id).writeText("{ \"id\": \"tor")
        val before = snapshot(dir)

        assertEquals(42, repo().listProjects().single().newerSchemaVersion)
        try {
            repo().loadProject(meta.id)
            fail()
        } catch (_: ProjectTooNewException) {
        }
        assertEquals(before, snapshot(dir))
    }

    @Test
    fun `genuinely unreadable metadata with no newer schema still recovers from layer files`() = runBlocking {
        val (meta, _) = savedProject(repo())
        val dir = repo().projectDir(meta.id)
        metaFile(repo(), meta.id).writeText("not json at all")
        File(dir, "metadata.json.bak").delete()

        val loaded = repo().loadProject(meta.id)

        assertNotNull("recovery is kept for damaged files", loaded)
        assertEquals("Recovered Project", loaded!!.first.name)
        assertEquals(2, loaded.second.layers.size)
    }

    // ------------------------------------------------------------------ refusal: write paths

    @Test
    fun `saving over a project a newer build wrote is refused before any file is touched and reported`() = runBlocking {
        val repo = repo()
        val (meta, engine) = savedProject(repo)
        // The editor already has the project open; a newer build then saves it on the same tablet.
        rewriteAsFromTheFuture(repo(), meta.id)
        val dir = repo.projectDir(meta.id)
        paint(engine.layers[0], Color.RED) // a dirty layer: an unguarded save would rewrite its PNG
        val before = snapshot(dir)

        val outcome = repo.saveProjectDurably(meta, engine)

        assertFalse(outcome.saved)
        assertEquals(SaveFailure.Kind.NEWER_VERSION, outcome.failure!!.kind)
        assertEquals("not a single layer PNG, metadata.json or .bak was written", before, snapshot(dir))
        val notice = repo.saveFailures.value.single()
        assertEquals(SaveFailure.Kind.NEWER_VERSION, notice.failure.kind)
        assertTrue(notice.message.contains("newer version"))
    }

    @Test
    fun `renaming a project a newer build wrote is refused and leaves it untouched`() = runBlocking {
        val repo = repo()
        val (meta, _) = savedProject(repo)
        rewriteAsFromTheFuture(repo(), meta.id)
        val before = snapshot(repo.projectDir(meta.id))

        try {
            repo.renameProject(meta, "Renamed")
            fail()
        } catch (_: ProjectTooNewException) {
        }
        assertEquals(before, snapshot(repo.projectDir(meta.id)))
    }

    @Test
    fun `a current-schema project still saves and renames normally`() = runBlocking {
        val repo = repo()
        val (meta, engine) = savedProject(repo)
        paint(engine.layers[0], Color.RED)
        assertTrue(repo.saveProjectDurably(meta, engine).saved)
        repo.renameProject(meta, "Renamed")
        assertEquals("Renamed", repo.listProjects().single().name)
        assertTrue(repo.saveFailures.value.isEmpty())
    }

    // ------------------------------------------------------------------ blend-mode wire format

    /** The historical labels, in the order the enum declares them: what every existing project on disk contains. */
    private val historicalLabels = listOf(
        "Normal", "Multiply", "Screen", "Overlay", "Darken", "Lighten", "Color Dodge", "Color Burn",
        "Hard Light", "Soft Light", "Difference", "Exclusion", "Hue", "Saturation", "Color", "Luminosity",
    )

    @Test
    fun `an old project written with the historical blend labels loads each layer's mode and saves the same strings back`() = runBlocking {
        val repo = repo()
        val (meta, _) = savedProject(repo)
        val labels = historicalLabels + "Vivid Light" // a value no build has ever written: opens as Normal
        val layersJson = labels.mapIndexed { i, label ->
            """{"id": "layer-$i", "name": "L$i", "opacity": 1.0, "visible": true, "blendMode": "$label", "order": $i}"""
        }.joinToString(",")
        // No schemaVersion key at all: the shape of a project saved before the field existed.
        metaFile(repo, meta.id).writeText(
            """{"id": "${meta.id}", "name": "Legacy", "widthPx": 64, "heightPx": 64, "createdAt": 1, "updatedAt": 2, "layers": [$layersJson]}""",
        )

        val reader = repo()
        val loaded = reader.loadProject(meta.id)!!
        assertEquals(
            LayerBlendMode.entries.toList() + LayerBlendMode.NORMAL,
            loaded.second.layers.map { it.blendMode },
        )

        assertTrue(reader.saveProjectDurably(loaded.first, loaded.second).saved)
        val written = json.parseToJsonElement(metaFile(repo, meta.id).readText()).jsonObject
        assertEquals(
            "on-disk strings must stay byte-compatible with v0.2.x",
            historicalLabels + "Normal",
            written["layers"]!!.jsonArray.map { it.jsonObject["blendMode"]!!.jsonPrimitive.content },
        )
        assertEquals(ProjectMeta.CURRENT_SCHEMA_VERSION, written["schemaVersion"]!!.jsonPrimitive.content.toInt())
    }

    // ------------------------------------------------------------------ Layer <-> LayerMeta

    @OptIn(ExperimentalSerializationApi::class) // SerialDescriptor.elementsCount/getElementName
    @Test
    fun `LayerMeta covers exactly the persisted layer fields, so a new field forces this file to be updated`() {
        assertEquals(
            listOf("id", "name", "opacity", "visible", "blendMode", "order", "locked", "isReferenceImage"),
            LayerMeta.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } },
        )
    }

    @Test
    fun `every Layer property survives save and load for every blend mode`() = runBlocking {
        val repo = repo()
        val (meta0, engine) = repo.createProject("Round Trip", 64, 64)
        engine.layers.clear()
        // One layer per blend mode, with the other properties varied so a swapped or dropped field shows up.
        LayerBlendMode.entries.forEachIndexed { i, mode ->
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(i * 15, 255 - i * 15, 7)) }
            engine.layers.add(
                Layer(
                    id = "layer-id-$i",
                    name = "Layer \"$i\" é",
                    bitmap = bmp,
                    opacity = 0.05f + i * 0.06f,
                    visible = i % 2 == 0,
                    blendMode = mode,
                    locked = i % 3 == 0,
                    isReferenceImage = i % 4 == 1,
                ),
            )
        }
        engine.activeLayerIndex = 5
        val saved = repo.saveProjectDurably(meta0, engine)
        assertTrue(saved.failure?.detail, saved.saved)

        val reloaded = repo().loadProject(meta0.id)!!

        assertEquals(5, reloaded.first.activeLayerIndex)
        assertEquals(engine.layers.size, reloaded.second.layers.size)
        engine.layers.zip(reloaded.second.layers).forEachIndexed { i, (want, got) ->
            val what = "layer $i (${want.blendMode})"
            assertEquals(what, want.id, got.id)
            assertEquals(what, want.name, got.name)
            assertEquals(what, want.opacity, got.opacity, 0f)
            assertEquals(what, want.visible, got.visible)
            assertEquals(what, want.blendMode, got.blendMode)
            assertEquals(what, want.locked, got.locked)
            assertEquals(what, want.isReferenceImage, got.isReferenceImage)
            assertEquals("$what pixels", want.bitmap.getPixel(3, 3), got.bitmap.getPixel(3, 3))
        }
        // Stack order is what `order` encodes: it must be the list index, never a re-sort by id.
        assertEquals(engine.layers.indices.toList(), reloaded.first.layers.map { it.order })
    }

    @Test
    fun `toMeta and toLayer are inverses for a single layer`() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val layer = Layer(id = "abc", name = "Ref", bitmap = bmp, opacity = 0.25f, visible = false, blendMode = LayerBlendMode.HARD_LIGHT, locked = true, isReferenceImage = true)

        val meta = layer.toMeta(order = 3)
        assertEquals("Hard Light", meta.blendMode)
        assertEquals(3, meta.order)
        val back = meta.toLayer(bmp)

        assertEquals("abc", back.id)
        assertEquals("Ref", back.name)
        assertEquals(0.25f, back.opacity, 0f)
        assertFalse(back.visible)
        assertEquals(LayerBlendMode.HARD_LIGHT, back.blendMode)
        assertTrue(back.locked)
        assertTrue(back.isReferenceImage)
    }

    @Test
    fun `toLayer reports an unknown blend mode and falls back to Normal`() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val reported = mutableListOf<String>()
        val layer = LayerMeta("x", "X", 1f, true, "Linear Burn", 0).toLayer(bmp) { reported += it }
        assertEquals(LayerBlendMode.NORMAL, layer.blendMode)
        assertEquals(listOf("Linear Burn"), reported)
    }
}
