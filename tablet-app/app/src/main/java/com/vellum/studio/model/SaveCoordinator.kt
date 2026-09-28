package com.vellum.studio.model

import android.graphics.Bitmap
import com.vellum.studio.canvas.LayerBlendMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** What a save attempt did. [failure] is null on success; [meta] is the meta that was committed
 * (on failure, the meta that was attempted but NOT committed -- always safe to assign back, since
 * the next save recomputes everything in it from the engine anyway). */
data class SaveOutcome(val meta: ProjectMeta, val failure: SaveFailure? = null) {
    val saved: Boolean get() = failure == null
}

/** Why a save did not happen -- always reported, never thrown at a fire-and-forget caller. */
class SaveFailure(val kind: Kind, val detail: String?) {
    enum class Kind { STORAGE_FULL, IO, OUT_OF_MEMORY, UNEXPECTED }

    /** Short, user-facing text for a Snackbar. */
    val userMessage: String
        get() = when (kind) {
            Kind.STORAGE_FULL -> "Couldn't save: storage is full. Free up space, then keep drawing -- your last saved version is safe."
            Kind.IO -> "Couldn't save your project (storage error). Your last saved version is safe."
            Kind.OUT_OF_MEMORY -> "Couldn't save: the device ran low on memory. Your last saved version is safe."
            Kind.UNEXPECTED -> "Couldn't save your project. Your last saved version is safe."
        }
}

/**
 * A failed save that has not been shown to the user yet -- see [ProjectRepository.saveFailures].
 * Carries the project name because the screen that finally shows it is often not the editor of that
 * project (Back has already navigated away by the time an encode/fsync fails).
 */
class SaveFailureNotice(val projectId: String, val projectName: String, val failure: SaveFailure) {
    val message: String get() = "\"$projectName\": ${failure.userMessage}"
}

/**
 * Seams for tests only ([ProjectRepository]'s internal constructor takes one); production always
 * uses this default. Kept as a tiny open class rather than sprinkling `if (testing)` through the
 * pipeline: tests can observe/slow/fail the two places a real device can be killed or run out of
 * space (encoding a layer, and the gap between the last layer rename and the metadata commit)
 * without mocking the filesystem.
 */
internal open class SaveHooks {
    /** Encodes one layer snapshot as PNG. Returns false if the encoder itself failed. */
    open fun encodeLayer(layerId: String, bitmap: Bitmap, out: OutputStream): Boolean =
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)

    /** Called after every changed layer file is renamed into place and before metadata.json is
     * written -- throwing here reproduces "process killed between the two" exactly. */
    open fun beforeMetadataCommit(projectId: String) {}
}

/**
 * One layer as captured on the main thread at save time. [full] is a private copy of the pixels
 * (only for layers whose PNG is out of date); [small] is a thumbnail-sized copy (only for layers
 * whose PNG is current but whose cached thumbnail piece is not). Both are owned by the plan and
 * recycled with it, so the IO side never reads a live [com.vellum.studio.canvas.Layer] bitmap.
 */
internal class LayerCapture(
    val id: String,
    val version: Int,
    val visible: Boolean,
    val opacity: Float,
    val blendMode: LayerBlendMode,
    var full: Bitmap?,
    var small: Bitmap?,
)

internal class SavePlan(val meta: ProjectMeta, val layers: List<LayerCapture>) {
    fun recycle() {
        for (cap in layers) {
            cap.full?.recycle(); cap.full = null
            cap.small?.recycle(); cap.small = null
        }
    }
}

/** A layer's thumbnail-resolution pixels as of [version]; owned by [ProjectState]. */
internal class ThumbEntry(val version: Int, val bitmap: Bitmap)

/**
 * Everything the coordinator remembers about one project. [savedVersions] / [thumbs] are read on
 * the capture side (main thread) and written on the IO side, hence the concurrent maps; writes
 * happen only while [mutex] is held, so they never interleave with each other.
 */
internal class ProjectState {
    val mutex = Mutex()
    val pendingLock = Any()
    var pending: Pending? = null

    /** layerId -> [com.vellum.studio.canvas.Layer.contentVersion] whose pixels are in the committed
     * `<id>.png`. A layer absent here (new, or quarantined at load) is always dirty. */
    val savedVersions = ConcurrentHashMap<String, Int>()
    val thumbs = ConcurrentHashMap<String, ThumbEntry>()

    fun clearThumbs() {
        thumbs.values.forEach { it.bitmap.recycle() }
        thumbs.clear()
    }

    class Pending(val plan: SavePlan, val waiters: List<CompletableDeferred<SaveOutcome>>)
}

/**
 * The app-scoped single writer for project storage.
 *
 * Why it exists (the failure it replaces): every save used to be a fresh coroutine launched on the
 * screen's own scope and run straight on Dispatchers.IO against the live layer bitmaps. Two saves
 * (6th-stroke autosave + Back) ran in parallel over the same PNGs; leaving the screen cancelled a
 * save mid-write; and a layer delete/undo/stroke during the multi-hundred-ms encode recycled or
 * half-rewrote the bitmap being read. Here:
 *
 * - Exactly one save per project runs at a time ([ProjectState.mutex]); load/delete/rename/zip take
 *   the same lock, so nothing observes or mutates a project directory mid-save.
 * - Work runs on this coordinator's own [scope] (SupervisorJob, app lifetime), not the caller's.
 *   The caller only awaits a [Deferred]; a cancelled caller (composition left, Back pressed) stops
 *   waiting but the save still completes.
 * - Requests coalesce: at most one save waits behind the running one, and a newer request
 *   supersedes it (its pixels are a superset, since layer versions only grow), so a burst of
 *   triggers costs one extra save, not N, and never N sets of snapshot bitmaps in memory.
 * - A failure of any kind (IOException, disk full, OOM, a bug) is turned into a [SaveOutcome], so
 *   the coroutine that used to crash the process can no longer throw out of here.
 */
internal class SaveCoordinator(private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {

    private val states = ConcurrentHashMap<String, ProjectState>()

    fun stateFor(projectId: String): ProjectState = states.getOrPut(projectId) { ProjectState() }

    /**
     * Queues [plan] for [state]'s project and returns immediately. [run] executes on [scope] while
     * holding the project lock, and owns nothing: the coordinator recycles [plan] afterwards.
     * [onFailure] converts an exception escaping [run] into the outcome handed to every waiter.
     */
    fun submit(
        state: ProjectState,
        plan: SavePlan,
        run: (SavePlan) -> SaveOutcome,
        onFailure: (ProjectMeta, Throwable) -> SaveOutcome,
    ): Deferred<SaveOutcome> {
        val result = CompletableDeferred<SaveOutcome>()
        val superseded: SavePlan?
        synchronized(state.pendingLock) {
            val old = state.pending
            superseded = old?.plan
            state.pending = ProjectState.Pending(plan, (old?.waiters ?: emptyList()) + result)
        }
        superseded?.recycle()
        scope.launch {
            state.mutex.withLock {
                val pending = synchronized(state.pendingLock) { state.pending.also { state.pending = null } }
                    ?: return@withLock // a newer request already carried this one's waiters through
                val outcome = try {
                    run(pending.plan)
                } catch (t: Throwable) {
                    onFailure(pending.plan.meta, t)
                } finally {
                    pending.plan.recycle()
                }
                pending.waiters.forEach { it.complete(outcome) }
            }
        }
        return result
    }

    /** Runs [block] with the project lock held, i.e. never concurrently with a save of it. */
    suspend fun <T> withProjectLock(projectId: String, block: () -> T): T =
        stateFor(projectId).mutex.withLock { block() }
}

internal fun classifyFailure(t: Throwable, storageBytesFree: Long?): SaveFailure {
    val msg = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    return when {
        t is OutOfMemoryError -> SaveFailure(SaveFailure.Kind.OUT_OF_MEMORY, t.message)
        t is IOException && (
            msg.contains("ENOSPC", ignoreCase = true) ||
                msg.contains("No space left", ignoreCase = true) ||
                (storageBytesFree != null && storageBytesFree < 1L * 1024 * 1024)
            ) -> SaveFailure(SaveFailure.Kind.STORAGE_FULL, msg)
        t is IOException -> SaveFailure(SaveFailure.Kind.IO, msg)
        else -> SaveFailure(SaveFailure.Kind.UNEXPECTED, "${t.javaClass.simpleName}: ${t.message}")
    }
}
