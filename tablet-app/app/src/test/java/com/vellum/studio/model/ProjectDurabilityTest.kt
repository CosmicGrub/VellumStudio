package com.vellum.studio.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.vellum.studio.VellumApp
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.Layer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for the durable save pipeline ([ProjectRepository.requestSave] /
 * [SaveCoordinator] / [com.vellum.studio.util.DurableFile]): the defect cluster where overlapping
 * unserialized saves, in-place PNG truncation, live bitmaps read on IO threads, and an undecodable
 * layer silently replaced by blank-then-overwritten could each destroy or crash a user's project.
 *
 * `@GraphicsMode(NATIVE)` because these assert on REAL encoded PNG bytes and real decoded pixels
 * (same reason as DiagramRendererTest); under the legacy shadow graphics a truncated PNG would
 * "decode" to garbage and the whole quarantine path would be untestable. VellumApp is the
 * application for the same reason ProjectRepositoryTest uses it (the thumbnail flatten reads
 * VellumApp.instance's paper-texture setting).
 *
 * Every "the app was killed / restarted" case is modeled as a FRESH [ProjectRepository] over the
 * same directory: a new instance has no in-memory dirty-tracking, exactly like a new process.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = VellumApp::class)
class ProjectDurabilityTest {

    private val app = RuntimeEnvironment.getApplication()

    /** Observes, slows and fails the two places a real device can be killed or run out of space. */
    private class TestHooks : SaveHooks() {
        val encodedIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val active = AtomicInteger()
        val maxConcurrentEncodes = AtomicInteger()
        @Volatile var entered = CountDownLatch(1)
            private set

        @Volatile var gate: CountDownLatch? = null
            private set
        @Volatile var dawdleMs = 0L
        @Volatile var failEncode: IOException? = null
        @Volatile var failBeforeMetadata: IOException? = null

        /** Makes the NEXT encode block until [gate] is released; [entered] fires when it is blocked. */
        fun arm() {
            entered = CountDownLatch(1)
            gate = CountDownLatch(1)
        }

        override fun encodeLayer(layerId: String, bitmap: Bitmap, out: OutputStream): Boolean {
            val n = active.incrementAndGet()
            maxConcurrentEncodes.accumulateAndGet(n) { a, b -> maxOf(a, b) }
            try {
                entered.countDown()
                gate?.await(10, TimeUnit.SECONDS)
                if (dawdleMs > 0) Thread.sleep(dawdleMs)
                // After the gate, so a test can hold an encode open and only THEN have it fail.
                failEncode?.let {
                    out.write(ByteArray(100)) // a partial write, so the tmp file really has bytes to clean up
                    throw it
                }
                encodedIds += layerId
                return super.encodeLayer(layerId, bitmap, out)
            } finally {
                active.decrementAndGet()
            }
        }

        override fun beforeMetadataCommit(projectId: String) {
            failBeforeMetadata?.let { throw it }
        }
    }

    private fun repo(hooks: SaveHooks = SaveHooks()) = ProjectRepository(app, hooks)

    private fun paint(layer: Layer, color: Int) {
        layer.bitmap.eraseColor(color)
        layer.bumpVersion()
    }

    private fun layerFile(repo: ProjectRepository, projectId: String, layer: Layer) =
        File(File(repo.projectDir(projectId), "layers"), "${layer.id}.png")

    private fun decodePixel(file: File): Int? = BitmapFactory.decodeFile(file.path)?.getPixel(0, 0)

    /** Project with three distinctly colored layers, saved and committed once. */
    private fun threeLayerProject(repo: ProjectRepository, hooks: TestHooks? = null): Triple<ProjectMeta, CanvasEngine, List<Int>> = runBlocking {
        val (meta0, engine) = repo.createProject("Durable", 64, 64)
        engine.addLayer("Two")
        engine.addLayer("Three")
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE)
        engine.layers.forEachIndexed { i, l -> paint(l, colors[i]) }
        val outcome = repo.saveProjectDurably(meta0, engine)
        assertTrue(outcome.failure?.detail, outcome.saved)
        hooks?.encodedIds?.clear()
        Triple(outcome.meta, engine, colors)
    }

    // ------------------------------------------------------------------ (b) single writer

    @Test
    fun `a burst of overlapping saves never runs two encodes at once and leaves every PNG decodable`() = runBlocking {
        val hooks = TestHooks().apply { dawdleMs = 15 }
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)

        // Fire six saves back to back while the first is still encoding (each preceded by an edit,
        // so each has real work): exactly the "6th-stroke autosave + Back" overlap, times three.
        val pending = (1..6).map { round ->
            engine.layers.forEach { paint(it, Color.rgb(round * 30, 0, 0)) }
            repo.requestSave(meta, engine)
        }
        val outcomes = pending.awaitAll()

        assertTrue("every queued/superseded request must still get a successful outcome", outcomes.all { it.saved })
        assertEquals("saves for one project must be strictly serialized", 1, hooks.maxConcurrentEncodes.get())

        val reloaded = repo().loadOk(meta.id)
        assertTrue(reloaded.quarantinedLayerNames.isEmpty())
        assertEquals(3, reloaded.engine.layers.size)
        // The LAST edit wins: coalescing may drop intermediate rounds but never the newest state.
        reloaded.engine.layers.forEach { assertEquals(Color.rgb(180, 0, 0), it.bitmap.getPixel(0, 0)) }
    }

    @Test
    fun `saves fired from separate threads at once are serialized and decodable`() {
        val hooks = TestHooks().apply { dawdleMs = 10 }
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)
        engine.layers.forEach { paint(it, Color.MAGENTA) }

        val go = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<SaveOutcome>())
        val threads = (1..4).map {
            Thread {
                go.await()
                results += runBlocking { repo.saveProjectDurably(meta, engine) }
            }.apply { start() }
        }
        go.countDown()
        threads.forEach { it.join(15_000) }

        assertEquals(4, results.size)
        assertTrue(results.all { it.saved })
        assertEquals(1, hooks.maxConcurrentEncodes.get())
        runBlocking { repo().loadOk(meta.id) }.engine.layers.forEach {
            assertEquals(Color.MAGENTA, it.bitmap.getPixel(0, 0))
        }
    }

    @Test
    fun `a save keeps running after the caller that started it is cancelled`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)
        val target = engine.layers[0]
        paint(target, Color.YELLOW)
        hooks.arm()

        // Models the editor scope being cancelled the moment Back is pressed.
        val caller = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { repo.saveProjectDurably(meta, engine) }
        assertTrue(hooks.entered.await(10, TimeUnit.SECONDS))
        caller.cancel()
        caller.join()
        hooks.gate!!.countDown()

        val file = layerFile(repo, meta.id, target)
        withTimeout(10_000) { while (decodePixel(file) != Color.YELLOW) delay(20) }
        assertEquals(Color.YELLOW, decodePixel(file))
    }

    // ------------------------------------------------------------------ (c) snapshots

    @Test
    fun `deleting mutating and adding layers during a slow encode neither crashes nor tears the saved copy`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, colors) = threeLayerProject(repo, hooks)
        val ids = engine.layers.map { it.id }
        // Dirty every layer so all three are snapshotted and encoded, then hold the encoder open.
        engine.layers.forEachIndexed { i, l -> paint(l, colors[i]) }
        hooks.arm()
        assertEquals("active layer is the top one", 2, engine.activeLayerIndex)

        val pending = repo.requestSave(meta, engine)
        assertTrue(hooks.entered.await(10, TimeUnit.SECONDS))

        // --- while the IO thread is stuck inside the encoder, the UI thread does everything the
        //     audit said could crash or tear the save ---
        val recycled = engine.layers[2].bitmap
        // Structural undo keeps a deleted layer's bitmap alive so Undo can bring it back, so the
        // live bitmap is only recycled once history lets go of it -- which is still the hazard this
        // test exists for (a bitmap recycled on Main while the IO thread is mid-save).
        engine.deleteActiveLayer()
        engine.undoManager.clear()
        assertTrue(recycled.isRecycled)
        paint(engine.layers[0], Color.BLACK)      // in-place overwrite of a layer being saved
        engine.addLayer("Late")                   // structural change to the SnapshotStateList
        hooks.gate!!.countDown()

        val outcome = pending.await()
        assertTrue(outcome.failure?.detail, outcome.saved)

        // The committed project is the state AT CAPTURE: three layers, original colors.
        val reloaded = repo().loadOk(meta.id)
        assertTrue(reloaded.quarantinedLayerNames.isEmpty())
        assertEquals(ids, reloaded.engine.layers.map { it.id })
        assertEquals(colors, reloaded.engine.layers.map { it.bitmap.getPixel(0, 0) })

        // ...and the post-mutation state saves cleanly on top of it afterwards.
        val second = repo.saveProjectDurably(outcome.meta, engine)
        assertTrue(second.saved)
        val after = repo().loadOk(meta.id)
        assertEquals(engine.layers.map { it.id }, after.engine.layers.map { it.id })
        assertEquals(Color.BLACK, after.engine.layers[0].bitmap.getPixel(0, 0))
    }

    @Test
    fun `unchanged layers are not re-encoded and their files are not touched`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)
        val files = engine.layers.map { layerFile(repo, meta.id, it) }
        // Age the files so a rewrite is unmistakable regardless of filesystem timestamp resolution.
        val aged = System.currentTimeMillis() - 60_000
        files.forEach { assertTrue(it.setLastModified(aged)) }
        val agedActual = files.map { it.lastModified() }

        paint(engine.layers[1], Color.CYAN)
        assertTrue(repo.saveProjectDurably(meta, engine).saved)

        assertEquals("only the edited layer may be re-encoded", listOf(engine.layers[1].id), hooks.encodedIds.toList())
        assertEquals(agedActual[0], files[0].lastModified())
        assertEquals(agedActual[2], files[2].lastModified())
        assertTrue("the edited layer's file was rewritten", files[1].lastModified() != agedActual[1])
        assertEquals(Color.CYAN, decodePixel(files[1]))

        // A save with nothing dirty encodes nothing (but still commits metadata and thumbnail).
        hooks.encodedIds.clear()
        assertTrue(repo.saveProjectDurably(meta, engine).saved)
        assertTrue(hooks.encodedIds.isEmpty())
        assertNotNull(BitmapFactory.decodeFile(File(repo.projectDir(meta.id), "thumbnail.png").path))
    }

    @Test
    fun `after reopening a project a save with no edits encodes no layer and still refreshes the thumbnail`() = runBlocking {
        val first = repo()
        val (meta, _, _) = threeLayerProject(first)
        val thumb = File(first.projectDir(meta.id), "thumbnail.png")
        thumb.delete()

        val hooks = TestHooks()
        val second = repo(hooks)
        val loaded = second.loadOk(meta.id)
        assertTrue(second.saveProjectDurably(loaded.meta, loaded.engine).saved)

        assertTrue("reopen + save must not re-encode layers that came straight off disk", hooks.encodedIds.isEmpty())
        assertNotNull("thumbnail is rebuilt from scaled pieces even though no layer was snapshotted", BitmapFactory.decodeFile(thumb.path))
    }

    // ------------------------------------------------------------------ (a) atomic commit / kill

    @Test
    fun `a kill between the layer renames and the metadata write reloads the previous project`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)
        val previousIds = engine.layers.map { it.id }

        // The doomed save: repaint a layer, add a new one, get killed after the PNGs are renamed in.
        paint(engine.layers[0], Color.WHITE)
        val added = engine.addLayer("Added")
        paint(added, Color.BLACK)
        hooks.failBeforeMetadata = IOException("simulated process kill")
        val failed = repo.saveProjectDurably(meta, engine)
        assertFalse(failed.saved)

        val layersDir = File(repo.projectDir(meta.id), "layers")
        assertTrue("the new layer's PNG made it to disk before the 'kill'", File(layersDir, "${added.id}.png").exists())

        // Restart: a brand-new repository. Structure is exactly the previous commit; nothing is
        // quarantined, nothing is blank.
        val restarted = repo()
        val reloaded = restarted.loadOk(meta.id)
        assertEquals(previousIds, reloaded.engine.layers.map { it.id })
        assertTrue(reloaded.quarantinedLayerNames.isEmpty())
        assertEquals(meta.name, reloaded.meta.name)

        // The orphan is swept by the next successful save from the restarted app...
        assertTrue(restarted.saveProjectDurably(reloaded.meta, reloaded.engine).saved)
        assertFalse(File(layersDir, "${added.id}.png").exists())

        // ...and, on the ORIGINAL repository, the failed save is fully retried (nothing was
        // wrongly recorded as saved when the commit didn't happen).
        hooks.failBeforeMetadata = null
        assertTrue(repo.saveProjectDurably(meta, engine).saved)
        val finalLoad = repo().loadOk(meta.id)
        assertEquals(engine.layers.map { it.id }, finalLoad.engine.layers.map { it.id })
        assertEquals(Color.BLACK, finalLoad.engine.layers.last().bitmap.getPixel(0, 0))
        assertEquals(Color.WHITE, finalLoad.engine.layers.first().bitmap.getPixel(0, 0))
    }

    @Test
    fun `deleted layers' files are removed only after the new metadata is committed`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)
        val doomed = engine.layers[2]
        val doomedFile = layerFile(repo, meta.id, doomed)
        engine.deleteActiveLayer()

        hooks.failBeforeMetadata = IOException("simulated process kill")
        assertFalse(repo.saveProjectDurably(meta, engine).saved)
        assertTrue("old metadata still references it, so the file must survive a failed commit", doomedFile.exists())
        val stillOld = repo().loadOk(meta.id)
        assertEquals(3, stillOld.engine.layers.size)
        assertEquals(Color.BLUE, stillOld.engine.layers[2].bitmap.getPixel(0, 0))

        hooks.failBeforeMetadata = null
        assertTrue(repo.saveProjectDurably(meta, engine).saved)
        assertFalse(doomedFile.exists())
    }

    // ------------------------------------------------------------------ (d) quarantine

    @Test
    fun `a truncated layer PNG is quarantined and reported and never overwritten by a later save`() = runBlocking {
        val repo = repo()
        val (meta, engine, colors) = threeLayerProject(repo)
        val victim = engine.layers[1]
        val victimFile = layerFile(repo, meta.id, victim)
        RandomAccessFile(victimFile, "rw").use { it.setLength(10) }
        val truncatedBytes = victimFile.readBytes()

        val reopened = repo()
        val loaded = reopened.loadOk(meta.id)
        assertEquals(listOf(victim.name), loaded.quarantinedLayerNames)
        val corrupt = File(victimFile.path + ".corrupt")
        assertTrue("damaged bytes are kept, not deleted", corrupt.exists())
        assertTrue(truncatedBytes.contentEquals(corrupt.readBytes()))
        assertFalse(victimFile.exists())
        // Other layers are untouched and the damaged one opens blank instead of failing the project.
        assertEquals(colors[0], loaded.engine.layers[0].bitmap.getPixel(0, 0))
        assertEquals(0, loaded.engine.layers[1].bitmap.getPixel(0, 0))
        assertEquals(colors[2], loaded.engine.layers[2].bitmap.getPixel(0, 0))

        // Saving afterwards writes a real file for the layer but leaves the quarantined bytes alone.
        assertTrue(reopened.saveProjectDurably(loaded.meta, loaded.engine).saved)
        assertTrue(truncatedBytes.contentEquals(corrupt.readBytes()))
        val again = repo().loadOk(meta.id)
        assertTrue("no repeat warning once the layer has a valid file again", again.quarantinedLayerNames.isEmpty())
        assertEquals(3, again.engine.layers.size)
    }

    @Test
    fun `truncated metadata falls back to the backup with layer names and order intact`() = runBlocking {
        val repo = repo()
        val (meta, engine, _) = threeLayerProject(repo)
        engine.layers[0].name = "Sketch"
        val v1 = repo.saveProjectDurably(meta, engine)
        engine.layers[0].name = "Ink"                    // second save rotates v1 into metadata.json.bak
        assertTrue(repo.saveProjectDurably(v1.meta, engine).saved)

        val metaFile = File(repo.projectDir(meta.id), "metadata.json")
        val bakFile = File(repo.projectDir(meta.id), "metadata.json.bak")
        assertTrue(bakFile.exists())
        RandomAccessFile(metaFile, "rw").use { it.setLength(5) }

        val loaded = repo().loadOk(meta.id)
        assertEquals("real name, not 'Recovered Project'", "Durable", loaded.meta.name)
        assertEquals(engine.layers.map { it.id }, loaded.engine.layers.map { it.id })
        assertEquals("previous good version's layer name, not 'Recovered Layer N'", "Sketch", loaded.engine.layers[0].name)
    }

    // ------------------------------------------------------------------ (e) failures are values, not crashes

    @Test
    fun `disk full during a save is reported as a failed outcome, leaves the old project intact and cleans up`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, colors) = threeLayerProject(repo, hooks)
        engine.layers.forEach { paint(it, Color.WHITE) }

        hooks.failEncode = IOException("write failed: ENOSPC (No space left on device)")
        val outcome = repo.saveProjectDurably(meta, engine) // must not throw
        assertFalse(outcome.saved)
        assertEquals(SaveFailure.Kind.STORAGE_FULL, outcome.failure!!.kind)
        assertTrue(outcome.failure!!.userMessage.contains("storage is full"))

        val layersDir = File(repo.projectDir(meta.id), "layers")
        assertTrue("half-written tmp files are removed", layersDir.listFiles()!!.none { it.name.endsWith(".tmp") })
        val reloaded = repo().loadOk(meta.id)
        assertTrue(reloaded.quarantinedLayerNames.isEmpty())
        assertEquals("the last good save is untouched", colors, reloaded.engine.layers.map { it.bitmap.getPixel(0, 0) })

        // Once space frees up the very next save succeeds with everything that was pending.
        hooks.failEncode = null
        assertTrue(repo.saveProjectDurably(outcome.meta, engine).saved)
        assertEquals(Color.WHITE, repo().loadOk(meta.id).engine.layers[0].bitmap.getPixel(0, 0))
    }

    @Test
    fun `saving with a recycled live layer bitmap reports a failure instead of crashing`() = runBlocking {
        val repo = repo()
        val (meta, engine, _) = threeLayerProject(repo)
        paint(engine.layers[0], Color.WHITE)
        engine.layers[0].bitmap.recycle() // a layer that is still listed but already recycled
        val outcome = repo.saveProjectDurably(meta, engine)
        assertFalse(outcome.saved)
        assertNotNull(outcome.failure)
    }

    /**
     * The Back-button scenario: EditorScreen requests the save, and its composition scope (the only
     * thing awaiting the outcome) is cancelled by the navigation in the same click -- before the
     * encode has finished failing. The failure must still be observable, on app-scoped state, or the
     * user leaves believing unsaved strokes were saved.
     */
    @Test
    fun `a write failure is observable on saveFailures after the requesting scope was cancelled`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)
        paint(engine.layers[0], Color.WHITE)
        assertTrue(repo.saveFailures.value.isEmpty())

        hooks.failEncode = IOException("write failed: ENOSPC (No space left on device)")
        hooks.arm() // hold the encode open so the scope is provably cancelled BEFORE the failure happens
        val screenScope = CoroutineScope(Dispatchers.Default)
        val pending = repo.requestSave(meta, engine)
        val awaiter = screenScope.launch { pending.await() }
        assertTrue(hooks.entered.await(10, TimeUnit.SECONDS))
        awaiter.cancelAndJoin()
        screenScope.cancel() // "Back": the screen and everything launched on it is gone
        hooks.gate!!.countDown()

        val outcome = withTimeout(10_000) { pending.await() } // the save itself still ran to completion
        assertEquals(SaveFailure.Kind.STORAGE_FULL, outcome.failure!!.kind)

        val notices = repo.saveFailures.value
        assertEquals(1, notices.size)
        assertEquals(meta.id, notices[0].projectId)
        assertEquals(SaveFailure.Kind.STORAGE_FULL, notices[0].failure.kind)
        assertTrue(notices[0].message.contains(meta.name))
        assertTrue(notices[0].message.contains("storage is full"))

        // Stays until a host reports having shown it, then is gone.
        assertEquals(1, repo.saveFailures.value.size)
        repo.acknowledgeSaveFailure(notices[0])
        assertTrue(repo.saveFailures.value.isEmpty())
    }

    @Test
    fun `a capture-time failure is published too, and repeats for the same project and kind do not pile up`() = runBlocking {
        val repo = repo()
        val (meta, engine, _) = threeLayerProject(repo)
        paint(engine.layers[0], Color.WHITE)
        engine.layers[0].bitmap.recycle() // capture itself throws (stands in for an OOM copying a layer)

        // Nobody awaits either of these -- the requester is "already gone".
        repo.requestSave(meta, engine)
        repo.requestSave(meta, engine)
        val notices = repo.saveFailures.value
        assertEquals("deduplicated per (project, kind)", 1, notices.size)
        assertEquals(meta.id, notices[0].projectId)
        assertFalse(notices[0].failure.userMessage.isBlank())
    }

    @Test
    fun `a successful save publishes nothing and a failed project creation throws instead of also raising a notice`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, _) = threeLayerProject(repo, hooks)
        paint(engine.layers[1], Color.CYAN)
        assertTrue(repo.saveProjectDurably(meta, engine).saved)
        assertTrue(repo.saveFailures.value.isEmpty())

        hooks.failEncode = IOException("write failed: ENOSPC (No space left on device)")
        val thrown = try { repo.createProject("Doomed", 32, 32); null } catch (e: IOException) { e }
        assertNotNull("creation surfaces failure as an exception", thrown)
        assertTrue("...and does not double-report through the snackbar channel", repo.saveFailures.value.isEmpty())
    }

    @Test
    fun `saveProject keeps its original signature and still returns the saved meta`() = runBlocking {
        val repo = repo()
        val (meta, engine) = repo.createProject("Legacy", 32, 32)
        val saved: ProjectMeta = repo.saveProject(meta, engine)
        assertEquals(meta.id, saved.id)
        assertTrue(saved.updatedAt >= meta.updatedAt)
        assertEquals(1, repo.listProjects().size)
    }

    @Test
    fun `zip export omits durability side files`() = runBlocking {
        val repo = repo()
        val (meta, engine, _) = threeLayerProject(repo)
        assertTrue(repo.saveProjectDurably(meta, engine).saved) // creates metadata.json.bak
        File(repo.projectDir(meta.id), "layers/x.png.corrupt").writeText("junk")
        val zipBytes = java.io.ByteArrayOutputStream().also { assertTrue(repo.exportProjectZipTo(meta.id, it)) }.toByteArray()
        val names = java.util.zip.ZipInputStream(zipBytes.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue(names.any { it == "metadata.json" })
        assertTrue(names.none { it.endsWith(".bak") || it.contains(".corrupt") || it.endsWith(".tmp") })
    }

    // ------------------------------------------------------------------ delete + undo vs. the save coordinator
    //
    // Found while merging the save coordinator (persist) with structural layer undo (undo): the
    // coordinator decides at CAPTURE time whether a layer's PNG is current (saved version == version),
    // and prunes/sweeps a layer's PNG when a committed save no longer references it. Undoing a delete
    // brings the same Layer back with the same contentVersion, so if that happens while the save that
    // dropped it is still in flight, a second capture sees "already saved" and takes no snapshot --
    // and then the first save's sweep deletes the PNG the second save's metadata points at.

    @Test
    fun `undoing a layer delete after the save that dropped it committed restores the layer's pixels on disk`() = runBlocking {
        val repo = repo()
        val (meta, engine, colors) = threeLayerProject(repo)
        val victim = engine.layers[2]

        assertTrue(engine.deleteActiveLayer())
        val afterDelete = repo.saveProjectDurably(meta, engine)
        assertTrue(afterDelete.saved)
        assertFalse("the dropped layer's file is swept once the delete commits", layerFile(repo, meta.id, victim).exists())

        assertTrue(engine.undo())
        assertTrue(repo.saveProjectDurably(afterDelete.meta, engine).saved)

        val reloaded = repo().loadOk(meta.id)
        assertTrue(reloaded.quarantinedLayerNames.isEmpty())
        assertEquals(3, reloaded.engine.layers.size)
        assertEquals(colors[2], reloaded.engine.layers.first { it.id == victim.id }.bitmap.getPixel(0, 0))
    }

    @Test
    fun `undoing a layer delete while the save that dropped it is still in flight does not lose the layer's pixels`() = runBlocking {
        val hooks = TestHooks()
        val repo = repo(hooks)
        val (meta, engine, colors) = threeLayerProject(repo, hooks)
        val victim = engine.layers[2]

        // Save #1 drops the victim, and has real encode work (a dirty bottom layer) so it can be
        // held open inside the encoder while the user does the next thing.
        assertTrue(engine.deleteActiveLayer())
        paint(engine.layers[0], Color.CYAN)
        hooks.arm()
        val first = repo.requestSave(meta, engine)
        assertTrue(hooks.entered.await(10, TimeUnit.SECONDS))

        // The snackbar's Undo, tapped while save #1 is still encoding, then the autosave that follows.
        assertTrue(engine.undo())
        assertEquals(3, engine.layers.size)
        val second = repo.requestSave(meta, engine)
        hooks.gate!!.countDown()

        assertTrue(first.await().saved)
        assertTrue(second.await().saved)

        val reloaded = repo().loadOk(meta.id)
        assertTrue("the restored layer must not have been quarantined or blanked", reloaded.quarantinedLayerNames.isEmpty())
        assertEquals(3, reloaded.engine.layers.size)
        assertEquals("restored layer keeps its own pixels", colors[2], reloaded.engine.layers.first { it.id == victim.id }.bitmap.getPixel(0, 0))
        assertEquals("the other edit that was in flight also landed", Color.CYAN, reloaded.engine.layers[0].bitmap.getPixel(0, 0))
        assertTrue("the restored layer's PNG exists on disk", layerFile(repo, meta.id, victim).exists())
    }
}
