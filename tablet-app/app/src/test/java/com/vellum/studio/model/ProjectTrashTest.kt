package com.vellum.studio.model

import android.graphics.Canvas
import android.graphics.Color
import com.vellum.studio.VellumApp
import com.vellum.studio.art.ColoringTemplate
import com.vellum.studio.network.SyncServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.io.IOException
import java.net.Socket

/**
 * Soft delete: [ProjectRepository.deleteProject] moves a project into `.trash/` instead of
 * destroying it, [ProjectRepository.restoreProject] (the gallery's Undo) moves it back, and
 * [ProjectRepository.purgeExpiredTrash] is the only automatic destroyer, at 30 days. Before this,
 * one card-menu tap ran `deleteRecursively` on a multi-hour layered project.
 *
 * The other half of what these pin is INVISIBILITY: the trash must never be listed as a project,
 * served over the LAN, or fed to `recoverFromLayerFiles` (a trashed folder whose metadata is gone
 * still has layer PNGs, so a trash inside the scanned directory would come back as a phantom
 * "Recovered Project"). Everything runs against real files, and "identical" means every byte of the
 * directory tree, not just the names.
 *
 * `@GraphicsMode(NATIVE)` + [VellumApp] for the same reasons as [ProjectDurabilityTest] (real PNG
 * bytes; the thumbnail step reads VellumApp.instance).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = VellumApp::class)
class ProjectTrashTest {

    private val app = RuntimeEnvironment.getApplication()
    private val repo = ProjectRepository(app)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val servers = mutableListOf<SyncServer>()

    @After
    fun stopServers() = servers.forEach { it.stop() }

    private val externalRoot: File get() = app.getExternalFilesDir(null)!!
    private val trashRoot: File get() = File(externalRoot, ProjectRepository.TRASH_DIR_NAME)
    private val projectsRoot: File get() = File(externalRoot, "projects")

    /** Every file (path -> bytes) and directory (path -> null) under [dir]: the whole "is anything different?" question. */
    private fun snapshot(dir: File): Map<String, List<Byte>?> =
        dir.walkTopDown().filter { it != dir }.associate { f ->
            f.relativeTo(dir).path to (if (f.isFile) f.readBytes().toList() else null)
        }

    /** A saved two-layer project (thumbnail and `.bak` included, thanks to a second save); returns its id. */
    private fun savedProject(name: String = "Hours Of Work"): String = runBlocking {
        val (meta0, engine) = repo.createProject(name, 64, 64)
        engine.addLayer("Ink")
        engine.layers.forEachIndexed { i, layer ->
            layer.bitmap.eraseColor(if (i == 0) Color.RED else Color.BLUE)
            layer.bumpVersion()
        }
        val first = repo.saveProjectDurably(meta0, engine)
        assertTrue(first.failure?.detail, first.saved)
        engine.layers[1].bitmap.eraseColor(Color.GREEN)
        engine.layers[1].bumpVersion()
        val second = repo.saveProjectDurably(first.meta, engine)
        assertTrue(second.failure?.detail, second.saved)
        engine.layers.forEach { it.bitmap.recycle() }
        meta0.id
    }

    private fun template(id: String) = ColoringTemplate(
        id = id, name = "Photo $id", category = "My Photos",
        draw = { _: Canvas, _: Int -> }, referenceFilePath = "/fake/$id.jpg",
    )

    // ------------------------------------------------------------------ delete + undo

    @Test
    fun `delete moves the project into the trash and out of the gallery listing`() = runBlocking {
        val id = savedProject("Sunset")

        val trashId = repo.deleteProject(id)!!

        assertTrue("gone from the gallery", repo.listProjects().isEmpty())
        assertFalse("its folder left projects/", File(projectsRoot, id).exists())
        val entry = repo.listTrash().single()
        assertEquals(trashId, entry.trashId)
        assertEquals(id, entry.projectId)
        assertEquals("the row is recognizable by its real name", "Sunset", entry.name)
        assertTrue("thumbnail travels with it", entry.thumbnailFile!!.exists())
        assertTrue("and it is a sibling of projects/, not inside it", File(trashRoot, trashId).isDirectory && trashRoot.parentFile == projectsRoot.parentFile)
    }

    @Test
    fun `undo restores a byte-identical project`() = runBlocking {
        val id = savedProject("Sunset")
        val before = snapshot(File(projectsRoot, id))
        assertTrue("fixture must hold real layer PNGs, a thumbnail and a .bak", before.keys.any { it.endsWith(".png") } && before.keys.contains("metadata.json.bak"))

        val trashId = repo.deleteProject(id)!!
        assertTrue(repo.restoreProject(trashId))

        assertEquals("every byte of every file is what it was", before, snapshot(File(projectsRoot, id)))
        assertEquals(listOf("Sunset"), repo.listProjects().map { it.name })
        assertTrue("nothing left in the trash", repo.listTrash().isEmpty())
        val reopened = repo.loadProject(id)
        assertTrue("and it opens as a normal project, was $reopened", reopened is LoadResult.Ok)
        (reopened as LoadResult.Ok).project.engine.recycleAll()
    }

    @Test
    fun `delete and restore each tick the library revision so the gallery re-lists`() = runBlocking {
        val id = savedProject()
        val start = repo.libraryRevision.value
        val trashId = repo.deleteProject(id)!!
        val afterDelete = repo.libraryRevision.value
        assertTrue(afterDelete > start)
        repo.restoreProject(trashId)
        assertTrue(repo.libraryRevision.value > afterDelete)
    }

    @Test
    fun `deleting the same id twice leaves two distinct trash entries`() = runBlocking {
        val id = savedProject()
        val first = repo.deleteProject(id)!!
        assertTrue(repo.restoreProject(first))
        val second = repo.deleteProject(id)!!
        assertNotEquals("a re-delete must not collide with, or overwrite, an earlier entry", first, second)

        // Same id trashed while an older entry for it still exists (a project re-created under the id).
        File(projectsRoot, id).mkdirs()
        File(projectsRoot, "$id/metadata.json").writeText("""{"id":"$id","name":"Second life","widthPx":8,"heightPx":8,"createdAt":1,"updatedAt":2,"layers":[]}""")
        val third = repo.deleteProject(id)!!
        assertEquals(setOf(second, third), repo.listTrash().map { it.trashId }.toSet())
    }

    @Test
    fun `restore refuses to overwrite a project that exists again and leaves both alone`() = runBlocking {
        val id = savedProject("Original")
        val trashId = repo.deleteProject(id)!!
        File(projectsRoot, id).mkdirs()
        File(projectsRoot, "$id/marker.txt").writeText("live")
        val trashedBefore = snapshot(File(trashRoot, trashId))

        assertFalse(repo.restoreProject(trashId))

        assertEquals("the live folder was not touched", "live", File(projectsRoot, "$id/marker.txt").readText())
        assertEquals("the trash entry is intact", trashedBefore, snapshot(File(trashRoot, trashId)))
    }

    @Test
    fun `deleting an unknown id is a no-op and never creates a trash folder`() = runBlocking {
        assertNull(repo.deleteProject("no-such-project"))
        assertFalse("nothing to trash, nothing created", trashRoot.exists())
    }

    @Test
    fun `delete refuses ids that would resolve to the projects root or outside it`() = runBlocking {
        val id = savedProject()
        val before = snapshot(projectsRoot)
        for (bad in listOf("", ".", "..", "../projects", ".trash", "a/b", "a\\b")) {
            try {
                repo.deleteProject(bad)
                fail("expected a refusal for \"$bad\"")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals("nothing moved", before, snapshot(projectsRoot))
        assertEquals(1, repo.listProjects().size)
        assertEquals(id, repo.listProjects().single().id)
    }

    @Test
    fun `a failed move leaves the project exactly where it was and throws instead of deleting`() = runBlocking {
        val id = savedProject()
        val before = snapshot(File(projectsRoot, id))
        // A plain FILE where the trash folder must go: mkdirs cannot succeed.
        trashRoot.writeText("not a folder")

        try {
            repo.deleteProject(id)
            fail("expected IOException")
        } catch (_: IOException) {
        }

        assertEquals("the project is untouched", before, snapshot(File(projectsRoot, id)))
        assertEquals(1, repo.listProjects().size)
    }

    // ------------------------------------------------------------------ invisibility

    @Test
    fun `a trashed project is invisible to every projects scan including template lookup and open`() = runBlocking {
        val (meta, engine) = repo.createFromTemplate(template("photo_1"))
        engine.recycleAll()
        assertEquals(meta.id, repo.findProjectBySourceTemplateId("photo_1"))

        repo.deleteProject(meta.id)

        assertTrue(repo.listProjects().isEmpty())
        assertNull("a repeat tap on that photo must make a fresh project, not reopen the trashed one", repo.findProjectBySourceTemplateId("photo_1"))
        assertEquals(LoadResult.NotFound, repo.loadProject(meta.id))
        assertFalse("loading a trashed id must not have created a folder for it", File(projectsRoot, meta.id).exists())
    }

    @Test
    fun `a trashed project whose metadata is gone is NOT resurrected as a Recovered Project`() = runBlocking {
        val id = savedProject("Lost Metadata")
        val dir = File(projectsRoot, id)
        assertTrue(File(dir, "metadata.json").delete())
        assertTrue(File(dir, "metadata.json.bak").delete())
        assertEquals("precondition: in projects/ this folder IS recovered from its layer PNGs", listOf("Recovered Project"), repo.listProjects().map { it.name })

        val trashId = repo.deleteProject(id)!!

        assertTrue("the layer PNGs in the trash must not be recovered into a phantom project", repo.listProjects().isEmpty())
        assertNull(repo.findProjectBySourceTemplateId("anything"))
        assertTrue("but it is still restorable", repo.restoreProject(trashId))
    }

    @Test
    fun `any dot-prefixed folder inside projects is skipped by the scans as a second guard`() = runBlocking {
        val id = savedProject("Real")
        // A trash-like folder in the scanned directory (e.g. an older layout, or a copy someone made).
        File(projectsRoot, id).copyRecursively(File(projectsRoot, ".trash-$id"))
        File(projectsRoot, ".trash-$id/metadata.json").delete()
        File(projectsRoot, ".trash-$id/metadata.json.bak").delete()

        assertEquals(listOf("Real"), repo.listProjects().map { it.name })
        assertNull(repo.findProjectBySourceTemplateId("x"))
    }

    @Test
    fun `the LAN server never lists or serves trash, even through path traversal`() {
        val liveId = savedProject("Live")
        val trashedId = savedProject("Trashed")
        val trashId = runBlocking { repo.deleteProject(trashedId)!! }
        val port = startServer()

        val list = get(port, "/projects")
        assertEquals(200, list.status)
        val ids = Json.parseToJsonElement(list.body).let { (it as kotlinx.serialization.json.JsonArray).map { e -> (e.jsonObject["id"] as JsonPrimitive).content } }
        assertEquals("only the live project is listed", listOf(liveId), ids)

        assertEquals(200, get(port, "/projects/$liveId/thumbnail.png").status)
        assertEquals(200, get(port, "/projects/$liveId/export.zip").status)
        assertEquals("by its old id", 404, get(port, "/projects/$trashedId/export.zip").status)
        assertEquals(404, get(port, "/projects/$trashedId/thumbnail.png").status)
        // NanoHTTPD does not normalize "..", so a raw request reaches outside projects/ unless guarded.
        assertTrue("fixture: the trash entry really has a thumbnail to leak", File(trashRoot, "$trashId/thumbnail.png").exists())
        assertEquals("traversal to the trash entry (thumbnail)", 404, get(port, "/projects/../.trash/$trashId/thumbnail.png").status)
        assertEquals("traversal to the trash entry (zip)", 404, get(port, "/projects/../.trash/$trashId/export.zip").status)
        assertEquals("percent-encoded traversal", 404, get(port, "/projects/%2e%2e/.trash/$trashId/export.zip").status)
        assertEquals("an id that names nothing is a 404, not an empty zip", 404, get(port, "/projects/nope/export.zip").status)
    }

    // ------------------------------------------------------------------ purge + delete forever

    /** Re-stamps a trash entry as if it had been deleted [ageMs] ago (the age lives in the folder name). */
    private fun age(trashId: String, ageMs: Long): String {
        val projectId = trashId.substringBeforeLast("__")
        val renamed = "${projectId}__${System.currentTimeMillis() - ageMs}"
        assertTrue(File(trashRoot, trashId).renameTo(File(trashRoot, renamed)))
        return renamed
    }

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `purge removes entries past thirty days and keeps younger ones restorable`() = runBlocking {
        val old = age(repo.deleteProject(savedProject("Old"))!!, 31 * day)
        val fresh = age(repo.deleteProject(savedProject("Fresh"))!!, 29 * day)
        val justDeleted = repo.deleteProject(savedProject("Just now"))!!

        val purged = repo.purgeExpiredTrash()

        assertEquals(1, purged)
        assertFalse("the 31-day-old entry is gone from disk", File(trashRoot, old).exists())
        assertEquals(setOf(fresh, justDeleted), repo.listTrash().map { it.trashId }.toSet())
        assertTrue("a 29-day-old entry can still be restored", repo.restoreProject(fresh))
    }

    @Test
    fun `purge uses the retention constant, ignores foreign folders and future stamps, and creates nothing`() = runBlocking {
        assertEquals("no trash at all: nothing to do", 0, repo.purgeExpiredTrash())
        assertFalse("a purge of a missing trash must not create it", trashRoot.exists())

        assertEquals(30L * day, ProjectRepository.TRASH_RETENTION_MS)
        val future = age(repo.deleteProject(savedProject("Clock was wrong"))!!, -5 * day)
        val foreign = File(trashRoot, "not-ours").apply { mkdirs(); File(this, "keep.txt").writeText("x") }

        assertEquals(0, repo.purgeExpiredTrash(nowMs = System.currentTimeMillis()))
        assertTrue(File(trashRoot, future).exists())
        assertEquals("something this class did not name is never purged", 1, repo.purgeExpiredTrash(nowMs = System.currentTimeMillis() + 100 * day))
        assertTrue(File(foreign, "keep.txt").exists())
    }

    @Test
    fun `delete forever removes an entry for good and only that entry`() = runBlocking {
        val a = repo.deleteProject(savedProject("A"))!!
        val b = repo.deleteProject(savedProject("B"))!!

        assertTrue(repo.deleteTrashedProject(a))

        assertFalse(File(trashRoot, a).exists())
        assertEquals(listOf(b), repo.listTrash().map { it.trashId })
        assertFalse("and it can no longer be restored", repo.restoreProject(a))
        assertTrue(repo.listProjects().isEmpty())
    }

    @Test
    fun `trash operations refuse traversal and names they did not produce`() = runBlocking {
        val id = savedProject("Live")
        repo.deleteProject(savedProject("Trashed"))
        val before = snapshot(externalRoot)

        for (bad in listOf("", "..", "../projects/$id", "../projects/${id}__1", "$id", "a/b__1")) {
            assertFalse("restore \"$bad\"", repo.restoreProject(bad))
            assertFalse("forever \"$bad\"", repo.deleteTrashedProject(bad))
        }

        assertEquals("nothing anywhere changed", before, snapshot(externalRoot))
    }

    // ------------------------------------------------------------------ rename

    @Test
    fun `rename by id persists across listings and touches nothing but the name`() = runBlocking {
        val id = savedProject("Untitled")
        val dir = File(projectsRoot, id)
        val pngsBefore = snapshot(dir).filterKeys { it.endsWith(".png") }
        val layersBefore = json.parseToJsonElement(File(dir, "metadata.json").readText()).jsonObject["layers"]

        assertTrue(repo.renameProjectById(id, "Sunset over Water"))

        assertEquals("Sunset over Water", ProjectRepository(app).listProjects().single().name)
        val after = json.parseToJsonElement(File(dir, "metadata.json").readText()).jsonObject
        assertEquals(layersBefore, after["layers"])
        assertEquals("layer PNGs and thumbnail are byte-identical", pngsBefore, snapshot(dir).filterKeys { it.endsWith(".png") })
        assertEquals("the previous metadata rotated to .bak", "Untitled", (json.parseToJsonElement(File(dir, "metadata.json.bak").readText()).jsonObject["name"] as JsonPrimitive).content)
    }

    @Test
    fun `rename by id refuses a newer-schema project and a project without readable metadata`() = runBlocking {
        val future = savedProject("Future")
        val mf = File(projectsRoot, "$future/metadata.json")
        val root = json.parseToJsonElement(mf.readText()).jsonObject
        val rewritten: JsonObject = buildJsonObject {
            root.forEach { (k, v) -> if (k != "schemaVersion") put(k, v) }
            put("schemaVersion", JsonPrimitive(99))
        }
        mf.writeText(rewritten.toString())
        val futureBefore = snapshot(File(projectsRoot, future))
        try {
            repo.renameProjectById(future, "Nope")
            fail("expected ProjectTooNewException")
        } catch (_: ProjectTooNewException) {
        }
        assertEquals(futureBefore, snapshot(File(projectsRoot, future)))

        val gone = savedProject("Gone")
        File(projectsRoot, "$gone/metadata.json").delete()
        File(projectsRoot, "$gone/metadata.json.bak").delete()
        val goneBefore = snapshot(File(projectsRoot, gone))
        assertFalse("never renames a recovered stand-in into existence", repo.renameProjectById(gone, "Nope"))
        assertEquals(goneBefore, snapshot(File(projectsRoot, gone)))
        assertFalse(repo.renameProjectById("no-such-project", "Nope"))
    }

    // ------------------------------------------------------------------ tiny HTTP client for the LAN test

    private class Reply(val status: Int, val body: String)

    private fun startServer(): Int {
        val server = SyncServer(repo, port = 0)
        server.start()
        servers += server
        return server.listeningPort
    }

    /** Raw request line so `..` and `%2e%2e` reach the server exactly as written (HttpURLConnection would normalize them). */
    private fun get(port: Int, path: String): Reply = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 10_000
        socket.getOutputStream().apply {
            write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        val raw = socket.getInputStream().readBytes()
        val text = String(raw, Charsets.ISO_8859_1)
        val status = text.substringBefore("\r\n").split(" ")[1].toInt()
        Reply(status, text.substringAfter("\r\n\r\n", ""))
    }
}
