package com.vellum.studio.model

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.vellum.studio.VellumApp
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.LayerBlendMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for lifecycle-driven, debounced autosave ([EditorAutosaver]): the defect where
 * the only save triggers were "every 6th stroke" and Back, so Home + a low-memory kill lost up to 5
 * strokes and EVERY layer edit (none of which count as strokes), and Back navigated ahead of the
 * write so the gallery showed a stale card.
 *
 * Same environment as [ProjectDurabilityTest]: NATIVE graphics (real encoded PNGs, real decoded
 * pixels) with [VellumApp] as the application (the thumbnail flatten reads it). "The process was
 * killed" is modeled as a FRESH [ProjectRepository] over the same directory -- a new instance has no
 * in-memory state, exactly like a new process -- and is compared against what the editor's own
 * repository wrote. Timing tests use small real delays (tens of ms) with wide margins rather than a
 * virtual clock, because the debounce is driven by the real coroutine `delay`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = VellumApp::class)
// The RUNNING_* / COMPLETE trim levels are deprecated for app use (API 34) but are exactly the values
// a pre-34 device still delivers to onTrimMemory, which is what these tests simulate.
@Suppress("DEPRECATION")
class EditorAutosaverTest {

    private val app = RuntimeEnvironment.getApplication()

    /** Counts real metadata commits (one per save that actually wrote), and can hold an encode open. */
    private class CountingHooks : SaveHooks() {
        val commits = AtomicInteger()
        @Volatile var entered = CountDownLatch(1)
            private set
        @Volatile var gate: CountDownLatch? = null
            private set

        fun arm() {
            entered = CountDownLatch(1)
            gate = CountDownLatch(1)
        }

        fun release() {
            gate?.countDown()
        }

        override fun encodeLayer(layerId: String, bitmap: Bitmap, out: OutputStream): Boolean {
            entered.countDown()
            gate?.await(10, TimeUnit.SECONDS)
            return super.encodeLayer(layerId, bitmap, out)
        }

        override fun beforeMetadataCommit(projectId: String) {
            commits.incrementAndGet()
        }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun resume() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        /** What Home / a recents swipe does to the visible activity. */
        fun goToBackground() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    /** An editor session the way EditorScreen builds one: create, then LOAD from disk, then open the autosaver. */
    private class Editor(val repo: ProjectRepository, val meta: ProjectMeta, val engine: CanvasEngine, val saver: EditorAutosaver)

    private fun openEditor(repo: ProjectRepository, name: String = "Auto", size: Int = 64): Editor = runBlocking {
        val (created, tmp) = repo.createProject(name, size, size)
        tmp.layers.forEach { it.bitmap.recycle() }
        val (meta, engine) = repo.loadProject(created.id)!!
        Editor(repo, meta, engine, repo.openAutosaver(meta, engine))
    }

    /** One "stroke": pixels change, the layer version and the engine revision bump -- what endStroke does. */
    private fun stroke(engine: CanvasEngine, x: Int, y: Int, color: Int) {
        val layer = engine.activeLayer()!!
        layer.bitmap.setPixel(x, y, color)
        layer.bumpVersion()
        engine.bumpRevision()
    }

    private fun fakeMeta(id: String = "fake") = ProjectMeta(
        id = id, name = "Fake", widthPx = 32, heightPx = 32, createdAt = 0, updatedAt = 0,
        layers = emptyList(), activeLayerIndex = 0, schemaVersion = ProjectMeta.CURRENT_SCHEMA_VERSION,
    )

    /** A save function that never touches disk: counts calls and answers via [answer]. */
    private class FakeSave(var answer: (ProjectMeta) -> Deferred<SaveOutcome> = { CompletableDeferred(SaveOutcome(it)) }) {
        val calls = AtomicInteger()
        operator fun invoke(meta: ProjectMeta, @Suppress("UNUSED_PARAMETER") engine: CanvasEngine): Deferred<SaveOutcome> {
            calls.incrementAndGet()
            return answer(meta)
        }
    }

    private fun fakeSaver(
        engine: CanvasEngine,
        save: FakeSave,
        revisions: kotlinx.coroutines.flow.Flow<Int>? = null,
        debounceMs: Long = 100,
        maxLatencyMs: Long = 30_000,
    ): EditorAutosaver =
        if (revisions != null) {
            EditorAutosaver(fakeMeta(), engine, save::invoke, revisions, debounceMs = debounceMs, maxLatencyMs = maxLatencyMs)
        } else {
            EditorAutosaver(fakeMeta(), engine, save::invoke, debounceMs = debounceMs, maxLatencyMs = maxLatencyMs)
        }

    // ------------------------------------------------------------------ the headline scenario

    @Test
    fun `two strokes and a blend mode change survive ON_STOP and a process restart`() = runBlocking {
        val hooks = CountingHooks()
        val repo = ProjectRepository(app, hooks)
        val ed = openEditor(repo)
        val owner = TestOwner().apply { resume() }
        owner.lifecycle.addObserver(ed.saver.lifecycleObserver)
        assertEquals(SaveStatus.SAVED, ed.saver.status)

        stroke(ed.engine, 3, 3, Color.RED)
        stroke(ed.engine, 5, 5, Color.BLUE)
        ed.engine.setLayerBlendMode(ed.engine.layers[0], LayerBlendMode.MULTIPLY)
        assertEquals(SaveStatus.UNSAVED, ed.saver.status)

        // Before the lifecycle event nothing is on disk: this is the pre-fix world, where a kill
        // here loses both strokes and the blend mode.
        val before = ProjectRepository(app, SaveHooks()).loadProject(ed.meta.id)!!.second
        assertEquals(Color.TRANSPARENT, before.layers[0].bitmap.getPixel(3, 3))
        assertEquals(LayerBlendMode.NORMAL, before.layers[0].blendMode)

        owner.goToBackground() // Home; then `adb shell am kill` == "a brand-new repository reads the disk"
        ed.saver.flush(SaveReason.BACK).await() // waits for the in-flight ON_STOP save, does not start another

        val after = ProjectRepository(app, SaveHooks()).loadProject(ed.meta.id)!!.second
        assertEquals(Color.RED, after.layers[0].bitmap.getPixel(3, 3))
        assertEquals(Color.BLUE, after.layers[0].bitmap.getPixel(5, 5))
        assertEquals(LayerBlendMode.MULTIPLY, after.layers[0].blendMode)
        assertEquals(SaveStatus.SAVED, ed.saver.status)
    }

    @Test
    fun `a layer-only edit with no stroke at all is persisted by ON_STOP`() = runBlocking {
        val repo = ProjectRepository(app, SaveHooks())
        val ed = openEditor(repo)
        val owner = TestOwner().apply { resume() }
        owner.lifecycle.addObserver(ed.saver.lifecycleObserver)

        // The old counter never saw any of these: no stroke, no save until Back.
        ed.engine.addLayer("Sketch")
        ed.engine.setLayerOpacity(ed.engine.layers[0], 0.4f)
        ed.engine.setLayerLocked(ed.engine.layers[0], true)

        owner.goToBackground()
        ed.saver.flush(SaveReason.BACK).await()

        val reloaded = ProjectRepository(app, SaveHooks()).loadProject(ed.meta.id)!!.second
        assertEquals(listOf("Layer 1", "Sketch"), reloaded.layers.map { it.name })
        assertEquals(0.4f, reloaded.layers[0].opacity, 0.001f)
        assertTrue(reloaded.layers[0].locked)
    }

    // ------------------------------------------------------------------ dirty flag: no-change flush is free

    @Test
    fun `an ON_STOP with no changes does no capture, no encode and no metadata write`() = runBlocking {
        val hooks = CountingHooks()
        val repo = ProjectRepository(app, hooks)
        val ed = openEditor(repo)
        val owner = TestOwner().apply { resume() }
        owner.lifecycle.addObserver(ed.saver.lifecycleObserver)
        val commitsAfterCreate = hooks.commits.get()
        val metaFile = File(repo.projectDir(ed.meta.id), "metadata.json")
        val bytesAfterCreate = metaFile.readBytes()

        // Three app switches and a trim callback on an untouched canvas.
        repeat(3) { owner.goToBackground(); owner.resume() }
        repo.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(commitsAfterCreate, hooks.commits.get())
        assertTrue(metaFile.readBytes().contentEquals(bytesAfterCreate))
        assertFalse(ed.saver.isDirty)

        // One real edit -> exactly one save, however many lifecycle events follow it.
        stroke(ed.engine, 1, 1, Color.GREEN)
        owner.goToBackground()
        ed.saver.flush(SaveReason.BACK).await()
        assertEquals(commitsAfterCreate + 1, hooks.commits.get())
        owner.resume(); owner.goToBackground()
        repo.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        ed.saver.close()
        assertEquals(commitsAfterCreate + 1, hooks.commits.get())
    }

    @Test
    fun `layer edits bump the revision the dirty flag is keyed on`() {
        val mutations = listOf<Pair<String, (CanvasEngine) -> Unit>>(
            "add layer" to { e -> e.addLayer("New") },
            "add reference image" to { e -> e.addImageLayer("Ref", Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)) },
            "duplicate layer" to { e -> e.duplicateActiveLayer() },
            "opacity" to { e -> e.setLayerOpacity(e.layers[0], 0.5f) },
            "visibility" to { e -> e.setLayerVisible(e.layers[0], false) },
            "blend mode" to { e -> e.setLayerBlendMode(e.layers[0], LayerBlendMode.MULTIPLY) },
            "lock" to { e -> e.setLayerLocked(e.layers[0], true) },
            "reorder" to { e -> e.moveActiveLayer(-1) },
            "delete layer" to { e -> e.deleteActiveLayer() },
        )
        for ((label, mutate) in mutations) {
            val engine = CanvasEngine(32, 32)
            engine.addLayer("A"); engine.addLayer("B") // two layers so reorder/delete are legal
            val save = FakeSave()
            val saver = fakeSaver(engine, save)
            assertFalse("$label: starts clean", saver.isDirty)
            mutate(engine)
            assertTrue("$label must mark the project dirty", saver.isDirty)
            assertEquals("$label", SaveStatus.UNSAVED, saver.status)
            runBlocking { saver.flush(SaveReason.STOP).await() }
            assertEquals("$label: exactly one save", 1, save.calls.get())
            assertFalse("$label: clean after the save", saver.isDirty)
        }
    }

    // ------------------------------------------------------------------ debounce

    @Test
    fun `a burst of edits is one debounced save and idle triggers it`() = runBlocking {
        val engine = CanvasEngine(32, 32).apply { addLayer("A") }
        val save = FakeSave()
        val revisions = MutableStateFlow(engine.revision)
        val saver = fakeSaver(engine, save, revisions, debounceMs = 150)
        val timer = launch(Dispatchers.Default) { saver.run() }
        try {
            repeat(6) {
                engine.bumpRevision(); revisions.value = engine.revision
                delay(20) // well inside the debounce window
            }
            assertEquals("still inside the debounce window after the last edit", 0, save.calls.get())
            delay(600)
            assertEquals("the whole burst is ONE save", 1, save.calls.get())
            assertEquals(SaveStatus.SAVED, saver.status)

            engine.bumpRevision(); revisions.value = engine.revision
            delay(600)
            assertEquals(2, save.calls.get())
        } finally {
            timer.cancelAndJoin()
        }
    }

    @Test
    fun `continuous editing cannot starve the debounce past the max latency`() = runBlocking {
        val engine = CanvasEngine(32, 32).apply { addLayer("A") }
        val save = FakeSave()
        val revisions = MutableStateFlow(engine.revision)
        // 200ms debounce but edits every 50ms: a pure debounce would never fire during the storm.
        val saver = fakeSaver(engine, save, revisions, debounceMs = 200, maxLatencyMs = 500)
        val timer = launch(Dispatchers.Default) { saver.run() }
        try {
            repeat(30) { // ~1.5s of continuous drawing
                engine.bumpRevision(); revisions.value = engine.revision
                delay(50)
            }
            assertTrue("saved during the storm (was ${save.calls.get()})", save.calls.get() >= 2)
        } finally {
            timer.cancelAndJoin()
        }
    }

    @Test
    fun `the debounce waits out a stroke still in flight`() = runBlocking {
        val engine = CanvasEngine(32, 32).apply { addLayer("A") }
        val save = FakeSave()
        val revisions = MutableStateFlow(engine.revision)
        val saver = fakeSaver(engine, save, revisions, debounceMs = 50)
        val timer = launch(Dispatchers.Default) { saver.run() }
        try {
            engine.strokeInProgressLayerId = engine.layers[0].id
            engine.bumpRevision(); revisions.value = engine.revision
            delay(500)
            assertEquals("no save lands mid-stroke", 0, save.calls.get())
            engine.strokeInProgressLayerId = null
            delay(800)
            assertEquals("saved once the stroke ended", 1, save.calls.get())
        } finally {
            timer.cancelAndJoin()
        }
    }

    @Test
    fun `the real snapshotFlow on engine revision drives the debounce`() = runBlocking {
        val engine = CanvasEngine(32, 32).apply { addLayer("A") }
        val save = FakeSave()
        val saver = fakeSaver(engine, save, debounceMs = 80) // default revisions = snapshotFlow { engine.revision }
        val timer = launch(Dispatchers.Default) { saver.run() }
        try {
            delay(150) // let the collector start and read the initial value
            engine.setLayerOpacity(engine.layers[0], 0.3f) // a layer edit, not a stroke
            // In the app the Compose global-snapshot manager delivers this; a plain test must.
            Snapshot.sendApplyNotifications()
            withTimeout(5_000) { while (save.calls.get() == 0) delay(20) }
            assertEquals(1, save.calls.get())
        } finally {
            timer.cancelAndJoin()
        }
    }

    @Test
    fun `the every 6th commit backstop still flushes without any debounce timer running`() {
        val engine = CanvasEngine(32, 32).apply { addLayer("A") }
        val save = FakeSave()
        val saver = fakeSaver(engine, save)
        repeat(5) { engine.bumpRevision(); saver.noteStrokeCommitted() }
        assertEquals(0, save.calls.get())
        engine.bumpRevision(); saver.noteStrokeCommitted()
        assertEquals(1, save.calls.get())
    }

    // ------------------------------------------------------------------ Back awaits, gallery refreshes

    @Test
    fun `Back awaits the save in flight and a clean flush hands back that same save`() = runBlocking {
        val engine = CanvasEngine(32, 32).apply { addLayer("A") }
        val gate = CompletableDeferred<SaveOutcome>()
        val save = FakeSave { gate }
        val saver = fakeSaver(engine, save)

        engine.bumpRevision()
        val autosave = saver.flush(SaveReason.DEBOUNCE)
        assertEquals(SaveStatus.SAVING, saver.status)
        // Back arrives while that write is still running and nothing new is dirty.
        val back = saver.flush(SaveReason.BACK)
        assertFalse("Back must not navigate ahead of the save in flight", back.isCompleted)
        assertEquals("no second save was started", 1, save.calls.get())

        gate.complete(SaveOutcome(fakeMeta()))
        withTimeout(5_000) { back.await(); autosave.await() }
        assertEquals(SaveStatus.SAVED, saver.status)
        assertTrue("with nothing dirty or in flight, Back has nothing to wait for", saver.flush(SaveReason.BACK).isCompleted)
    }

    @Test
    fun `after Back's await the library revision ticked and the gallery listing has the fresh thumbnail and sort order`() = runBlocking {
        val repo = ProjectRepository(app, SaveHooks())
        val older = openEditor(repo, "Older")
        Thread.sleep(20)
        val newer = openEditor(repo, "Newer")
        Thread.sleep(20)
        assertEquals("Newer sorts first before any edit", listOf("Newer", "Older"), repo.listProjects().map { it.name })

        // Paint the whole older canvas red, then leave the way Back does: flush and await.
        older.engine.layers[0].bitmap.eraseColor(Color.RED)
        older.engine.layers[0].bumpVersion()
        older.engine.bumpRevision()
        val revisionBefore = repo.libraryRevision.value
        older.saver.flush(SaveReason.BACK).await()

        assertTrue("a committed save ticks the library revision the gallery collects", repo.libraryRevision.value > revisionBefore)
        val listing = repo.listProjects() // what the gallery does when it (re)enters composition
        assertEquals("the just-edited project moved to the top", listOf("Older", "Newer"), listing.map { it.name })
        val thumb = BitmapFactory.decodeFile(listing[0].thumbnailFile!!.path)
        assertNotNull(thumb)
        val px = thumb.getPixel(thumb.width / 2, thumb.height / 2)
        assertTrue("thumbnail shows the fresh red, not the blank canvas (was #${Integer.toHexString(px)})", Color.red(px) > 200 && Color.green(px) < 60)
        assertEquals(newer.meta.name, listing[1].name)
    }

    @Test
    fun `deleting a project also ticks the library revision`() = runBlocking {
        val repo = ProjectRepository(app, SaveHooks())
        val ed = openEditor(repo)
        val before = repo.libraryRevision.value
        repo.deleteProject(ed.meta.id)
        assertTrue(repo.libraryRevision.value > before)
    }

    // ------------------------------------------------------------------ survives the caller

    @Test
    fun `cancelling the screen scope mid-save neither loses the save nor its bookkeeping`() = runBlocking {
        val hooks = CountingHooks()
        val repo = ProjectRepository(app, hooks)
        val ed = openEditor(repo)
        stroke(ed.engine, 7, 7, Color.MAGENTA)

        hooks.arm() // the encode blocks until released, so the save is provably mid-write
        val screenScope = CoroutineScope(Job() + Dispatchers.Default)
        val waiting = screenScope.launch { ed.saver.flush(SaveReason.BACK).await() }
        assertTrue(hooks.entered.await(10, TimeUnit.SECONDS))
        // Back popped the route: the composition scope is cancelled while the encode is running.
        waiting.cancelAndJoin()
        screenScope.coroutineContext[Job]!!.cancelAndJoin()
        hooks.release()

        withTimeout(10_000) { while (ed.saver.status != SaveStatus.SAVED) delay(20) }
        val reloaded = ProjectRepository(app, SaveHooks()).loadProject(ed.meta.id)!!.second
        assertEquals(Color.MAGENTA, reloaded.layers[0].bitmap.getPixel(7, 7))
    }

    // ------------------------------------------------------------------ failure keeps the work dirty

    @Test
    fun `a failed save leaves the edits dirty and the next trigger retries`() = runBlocking {
        val engine = CanvasEngine(32, 32).apply { addLayer("A") }
        val save = FakeSave { CompletableDeferred(SaveOutcome(it, SaveFailure(SaveFailure.Kind.STORAGE_FULL, "ENOSPC"))) }
        val saver = fakeSaver(engine, save)

        engine.bumpRevision()
        assertNotNull(saver.flush(SaveReason.STOP).await())
        assertEquals(SaveStatus.FAILED, saver.status)
        assertTrue("a failed save must not mark the edits saved", saver.isDirty)

        save.answer = { CompletableDeferred(SaveOutcome(it)) } // space freed
        saver.flush(SaveReason.STOP).await()
        assertEquals(2, save.calls.get())
        assertEquals(SaveStatus.SAVED, saver.status)
        assertFalse(saver.isDirty)
    }

    // ------------------------------------------------------------------ trim memory

    @Test
    fun `trim memory flushes an open editor from UI_HIDDEN up but not on foreground pressure or after close`() = runBlocking {
        val repo = ProjectRepository(app, SaveHooks())
        val ed = openEditor(repo)

        stroke(ed.engine, 2, 2, Color.CYAN)
        repo.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) // still visible: no reason to write
        assertTrue(ed.saver.isDirty)
        assertEquals(SaveStatus.UNSAVED, ed.saver.status)

        repo.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertFalse("UI_HIDDEN captured the edit", ed.saver.isDirty)
        ed.saver.flush(SaveReason.BACK).await()
        val reloaded = ProjectRepository(app, SaveHooks()).loadProject(ed.meta.id)!!.second
        assertEquals(Color.CYAN, reloaded.layers[0].bitmap.getPixel(2, 2))

        ed.saver.close() // editor left: no longer reachable from the process-wide callback
        stroke(ed.engine, 4, 4, Color.YELLOW)
        repo.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        assertTrue(ed.saver.isDirty)
    }

    @Test
    fun `leaving the editor flushes what is still unsaved`() = runBlocking {
        val repo = ProjectRepository(app, SaveHooks())
        val ed = openEditor(repo)
        stroke(ed.engine, 6, 6, Color.GREEN)
        ed.saver.close() // EditorScreen's onDispose
        ed.saver.flush(SaveReason.BACK).await()
        val reloaded = ProjectRepository(app, SaveHooks()).loadProject(ed.meta.id)!!.second
        assertEquals(Color.GREEN, reloaded.layers[0].bitmap.getPixel(6, 6))
    }
}
