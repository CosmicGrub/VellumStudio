package com.vellum.studio.model

import android.graphics.Bitmap
import android.graphics.Color
import com.vellum.studio.VellumApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What opening a project does when it cannot open. Before this, [ProjectRepository.loadProject]
 * returned null (an editor spinner forever) or threw: a metadata.json whose dimensions decoded to
 * 0 made `CanvasEngine(0, 0)` throw `IllegalArgumentException` from `Bitmap.createBitmap(0, 0)`,
 * and an OOM decoding a big layer propagated too -- the app crashed on every tap of that card,
 * and Delete (permanent) was the only way out. Now every one of those is a [LoadResult] the editor
 * turns into an error card, and none of them touches the files: the tests compare a full directory
 * snapshot before and after, because "a failed open can never lead to a save that clobbers the real
 * project" starts with "the failed open itself wrote nothing".
 *
 * `@GraphicsMode(NATIVE)` + [VellumApp] for the same reasons as [ProjectDurabilityTest] (real PNG
 * bytes; the thumbnail step reads VellumApp.instance). A "restart" is a fresh [ProjectRepository]
 * over the same directory.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = VellumApp::class)
class ProjectOpenFailureTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun repo(hooks: SaveHooks = SaveHooks()) = ProjectRepository(app, hooks)

    private fun metaFile(repo: ProjectRepository, id: String) = File(repo.projectDir(id), "metadata.json")

    /** Every file (path -> bytes) and directory (path -> null) under [dir]: the whole "is anything different?" question. */
    private fun snapshot(dir: File): Map<String, List<Byte>?> =
        dir.walkTopDown().filter { it != dir }.associate { f ->
            f.relativeTo(dir).path to (if (f.isFile) f.readBytes().toList() else null)
        }

    /** A project directory holding ONLY a hand-written metadata.json: no layers/ folder, no PNG, nothing to infer a size from. */
    private fun metadataOnlyProject(id: String, metadataJson: String): File {
        val dir = repo().projectDir(id)
        dir.mkdirs()
        File(dir, "metadata.json").writeText(metadataJson)
        return dir
    }

    private fun zeroSizedMetadata(id: String, name: String = "Zeroed") =
        """{"id": "$id", "name": "$name", "widthPx": 0, "heightPx": 0, "createdAt": 1, "updatedAt": 2,
            "layers": [{"id": "layer-a", "name": "Layer 1", "opacity": 1.0, "visible": true, "blendMode": "Normal", "order": 0}]}"""

    /** A saved 64x64 project with one red layer; returns its id. */
    private fun savedProject(name: String = "Real Art"): String = runBlocking {
        val repo = repo()
        val (meta0, engine) = repo.createProject(name, 64, 64)
        engine.layers[0].bitmap.eraseColor(Color.RED)
        engine.layers[0].bumpVersion()
        val outcome = repo.saveProjectDurably(meta0, engine)
        assertTrue(outcome.failure?.detail, outcome.saved)
        meta0.id
    }

    // ------------------------------------------------------------------ zero / garbage canvas size

    @Test
    fun `metadata with a zero canvas size and no layer files opens to Unreadable instead of throwing from Bitmap createBitmap`() = runBlocking {
        val id = "zero-no-layers"
        val dir = metadataOnlyProject(id, zeroSizedMetadata(id))
        val before = snapshot(dir)

        val result = repo().loadProject(id)

        assertTrue("expected Unreadable, was $result", result is LoadResult.Unreadable)
        result as LoadResult.Unreadable
        assertEquals(UnreadableReason.INVALID_CANVAS_SIZE, result.reason)
        assertEquals("the failed open wrote/created nothing (no layers/ dir, no sweep, no .corrupt)", before, snapshot(dir))
    }

    @Test
    fun `the gallery lists a zero-canvas project flagged damaged under its real name and it stays tappable`() = runBlocking {
        val id = "zero-listed"
        metadataOnlyProject(id, zeroSizedMetadata(id, name = "Sketchbook"))

        val summary = repo().listProjects().single()

        assertEquals(id, summary.id)
        assertEquals("Sketchbook", summary.name)
        assertTrue(summary.isDamaged)
        assertEquals(UnreadableReason.INVALID_CANVAS_SIZE, summary.damage)
        assertTrue("still tappable, so the editor's error card can explain and offer the log", summary.isOpenable)
    }

    @Test
    fun `metadata with no dimensions at all and no layer files is damaged not a zero-by-zero project`() = runBlocking {
        val id = "no-dims"
        // widthPx/heightPx absent: strict decode fails, decodeLeniently used to fall back to 0.
        metadataOnlyProject(id, """{"id": "$id", "name": "Lost Size", "layers": []}""")

        assertTrue(repo().loadProject(id) is LoadResult.Unreadable)
        assertTrue(repo().listProjects().single().isDamaged)
    }

    @Test
    fun `a corrupted absurd canvas size is damaged, not an attempt to allocate it`() = runBlocking {
        val id = "huge"
        metadataOnlyProject(id, zeroSizedMetadata(id).replace("\"widthPx\": 0", "\"widthPx\": 2000000000"))

        val result = repo().loadProject(id)

        assertTrue("was $result", result is LoadResult.Unreadable && result.reason == UnreadableReason.INVALID_CANVAS_SIZE)
    }

    @Test
    fun `a zero canvas size is repaired from the project's own layer image so the artwork still opens`() = runBlocking {
        val id = savedProject("Rescued")
        val mf = metaFile(repo(), id)
        mf.writeText(mf.readText().replace("\"widthPx\": 64", "\"widthPx\": 0").replace("\"heightPx\": 64", "\"heightPx\": 0"))
        File(repo().projectDir(id), "metadata.json.bak").delete()

        val loaded = repo().loadOk(id)

        assertEquals("real name kept, not a Recovered Project", "Rescued", loaded.meta.name)
        assertEquals(64, loaded.engine.widthPx)
        assertEquals(64, loaded.engine.heightPx)
        assertEquals(Color.RED, loaded.engine.layers[0].bitmap.getPixel(3, 3))
        assertFalse(repo().listProjects().single().isDamaged)
    }

    @Test
    fun `a zero canvas size falls back to the good bak before giving up`() = runBlocking {
        val id = savedProject("Has Backup")
        // A second save rotates the good metadata into .bak; then the live file is garbled to 0x0 and its layer image lost.
        runBlocking {
            val r = repo()
            val loaded = r.loadOk(id)
            loaded.engine.layers[0].bumpVersion()
            assertTrue(r.saveProjectDurably(loaded.meta, loaded.engine).saved)
        }
        val dir = repo().projectDir(id)
        assertTrue(File(dir, "metadata.json.bak").exists())
        val mf = metaFile(repo(), id)
        mf.writeText(mf.readText().replace("\"widthPx\": 64", "\"widthPx\": 0").replace("\"heightPx\": 64", "\"heightPx\": 0"))
        File(dir, "layers").listFiles()!!.forEach { it.delete() }

        val loaded = repo().loadOk(id)

        assertEquals("Has Backup", loaded.meta.name)
        assertEquals(64, loaded.engine.widthPx)
    }

    // ------------------------------------------------------------------ missing

    @Test
    fun `an id with nothing on disk is NotFound and leaves no directory behind`() = runBlocking {
        val repo = repo()
        val id = "ghost"

        val result = repo.loadProject(id)

        assertTrue("was $result", result is LoadResult.NotFound)
        assertFalse("probing must not create projects/<id>", repo.projectDir(id).exists())
    }

    // ------------------------------------------------------------------ out of memory

    private class OomHooks : SaveHooks() {
        override fun decodeLayer(layerId: String, file: File): Bitmap? = throw OutOfMemoryError("Failed to allocate a 268435456 byte allocation")
    }

    @Test
    fun `running out of memory decoding a layer is reported as not enough memory and touches nothing on disk`() = runBlocking {
        val id = savedProject("Big One")
        val dir = repo().projectDir(id)
        val before = snapshot(dir)

        val result = repo(OomHooks()).loadProject(id)

        assertTrue("was $result", result is LoadResult.Unreadable)
        result as LoadResult.Unreadable
        assertEquals(UnreadableReason.OUT_OF_MEMORY, result.reason)
        assertTrue(result.message, result.message.contains("not enough memory to open", ignoreCase = true))
        assertEquals("an OOM is not corruption: no .corrupt, nothing rewritten", before, snapshot(dir))
        // Nothing was wrong with the project: on a device with the heap back, it opens fine and intact.
        assertEquals(Color.RED, repo().loadOk(id).engine.layers[0].bitmap.getPixel(3, 3))
    }

    // ------------------------------------------------------------------ unreadable file

    @Test
    fun `a metadata file that cannot be read is an IO error, not a Recovered Project rebuilt from layer files`() = runBlocking {
        val id = savedProject("Flaky Disk")
        val dir = repo().projectDir(id)
        // A directory where the file should be: exists() is true and reading it throws IOException on every platform.
        val mf = File(dir, "metadata.json")
        val realMeta = mf.readBytes()
        assertTrue(mf.delete())
        assertTrue(mf.mkdir())
        val before = snapshot(dir)

        val result = repo().loadProject(id)

        assertTrue("was $result", result is LoadResult.Unreadable)
        result as LoadResult.Unreadable
        assertEquals(UnreadableReason.IO_ERROR, result.reason)
        assertEquals("nothing rewritten, so the real metadata is not replaced by a lossy stand-in", before, snapshot(dir))
        assertTrue(repo().listProjects().single().isDamaged)

        // Disk recovers: the very same project opens with its real name.
        assertTrue(mf.delete())
        mf.writeBytes(realMeta)
        assertEquals("Flaky Disk", repo().loadOk(id).meta.name)
    }

    // ------------------------------------------------------------------ what the error card says

    @Test
    fun `every failure carries user-facing text and out-of-memory says so plainly`() {
        val failures: List<LoadResult.Failed> = UnreadableReason.entries.map { LoadResult.Unreadable(it, "detail") } +
            LoadResult.NotFound +
            LoadResult.TooNew(ProjectTooNewException(9, 1))
        failures.forEach {
            assertTrue("${it::class.simpleName} title", it.title.isNotBlank())
            assertTrue("${it::class.simpleName} message", it.message.isNotBlank())
        }
        assertTrue(LoadResult.Unreadable(UnreadableReason.OUT_OF_MEMORY, null).message.contains("not enough memory to open"))
        assertTrue(LoadResult.TooNew(ProjectTooNewException(9, 1)).message.contains("newer version"))
    }
}
