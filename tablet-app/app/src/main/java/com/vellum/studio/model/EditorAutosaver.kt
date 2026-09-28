package com.vellum.studio.model

import android.content.ComponentCallbacks2
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vellum.studio.canvas.CanvasEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** What the editor's "Saved" indicator shows. */
enum class SaveStatus {
    /** Everything on the canvas is in the committed project on disk. */
    SAVED,

    /** Edited since the last save started; the debounce (or a lifecycle event) will save it. */
    UNSAVED,

    /** A save is being encoded/written right now. */
    SAVING,

    /** The last save failed (the failure itself is announced by the app-level SaveFailureHost);
     * the changes stay dirty and the next trigger retries. */
    FAILED,
}

/** Why a save was requested. [lifecycle] ones are worth a DiagnosticLog line; the two
 * background triggers fire every few seconds while drawing and would flood the rolling log. */
enum class SaveReason(val label: String, val lifecycle: Boolean) {
    DEBOUNCE("debounced autosave", false),
    STROKE_BACKSTOP("6-commit backstop", false),
    STOP("ON_STOP", true),
    TRIM_MEMORY("trim-memory UI_HIDDEN", true),
    BACK("Back", true),
    DISPOSE("editor closed", true),
}

/**
 * The editor's autosave policy for ONE open project: when to save, and whether there is anything to
 * save. The write itself is [ProjectRepository.requestSave] / [SaveCoordinator] (single writer,
 * atomic files, dirty-layer snapshots); this class only decides WHEN, and tracks what is on disk.
 *
 * Why it replaces the old "every 6th stroke or on Back" counter (the failure): Home followed by a
 * low-memory kill lost up to 5 strokes, and every non-stroke edit -- layer add/delete/reorder/
 * opacity/blend/lock, reference import, undo/redo -- was never saved at all until the next 6th
 * stroke or Back, because none of them count as a stroke. All of them (and every stroke, fill and
 * selection move) bump [CanvasEngine.revision], so that counter is the trigger now:
 *
 * - DEBOUNCE ([run]): [debounceMs] after the last revision change, so a burst of edits is one save
 *   and idle time is what triggers it. A stylus user drawing continuously never goes quiet for that
 *   long, so [maxLatencyMs] caps how long a dirty canvas can wait, and the old every-Nth-commit
 *   counter stays as a third, independent backstop ([noteStrokeCommitted]).
 * - LIFECYCLE ([lifecycleObserver], [onTrimMemory], [close]): ON_STOP, trim-memory UI_HIDDEN and
 *   leaving the editor flush immediately -- the moments after which the process may die without
 *   another chance.
 * - BACK ([flush] returns the Deferred): the caller awaits it before navigating, so the gallery
 *   never reloads ahead of the save.
 *
 * Dirty flag: [capturedRevision] is the [CanvasEngine.revision] the last save request captured, so
 * "is there anything to save" is one int compare. A flush with nothing dirty does no capture, no
 * encode and no file write -- which is what makes ON_STOP-on-every-app-switch and the redundant
 * trim/dispose flushes free. A failed save resets it to a sentinel so the unsaved edits stay dirty
 * (see [SaveStatus.FAILED]); there is deliberately no automatic retry loop -- against a full disk
 * that would re-encode 16MB layers every couple of seconds -- the next edit, ON_STOP or Back retries.
 *
 * Threading: [flush] and everything that calls it run on the MAIN thread, because the capture inside
 * [ProjectRepository.requestSave] must read the layers on the thread that mutates them. Nothing here
 * is tied to a coroutine scope that can be cancelled: the capture is synchronous, the write runs on
 * the coordinator's app-lifetime scope, and the post-save bookkeeping is a completion handler on that
 * result -- so a composition that leaves (Back, config change) cannot cancel a save or lose its
 * bookkeeping. The only cancellable piece is [run], the debounce timer, which SHOULD die with the
 * screen.
 */
class EditorAutosaver internal constructor(
    initialMeta: ProjectMeta,
    private val engine: CanvasEngine,
    private val save: (ProjectMeta, CanvasEngine) -> Deferred<SaveOutcome>,
    /** Emits the engine's revision whenever it changes. Injectable so tests need no Compose snapshot machinery. */
    private val revisions: Flow<Int> = snapshotFlow { engine.revision },
    private val log: (String) -> Unit = {},
    private val onClose: (EditorAutosaver) -> Unit = {},
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxLatencyMs: Long = MAX_LATENCY_MS,
    private val strokeBackstop: Int = STROKE_BACKSTOP,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()

    // Guarded by [lock]. The Compose-state ones are also read by the top bar's indicator, so they
    // are snapshot state; writes may come from the coordinator's thread (completion handler), which
    // snapshot state permits.
    private var meta = initialMeta
    private var requestSeq = 0
    private var lastRequest: CompletableDeferred<SaveOutcome?>? = null
    private var dirtySinceMs: Long? = null
    private var strokesSinceSave = 0
    private var inFlight by mutableIntStateOf(0)
    private var failed by mutableStateOf(false)

    /** [CanvasEngine.revision] captured by the newest save request; a project just loaded from disk is clean by definition. */
    private var capturedRevision by mutableIntStateOf(engine.revision)

    /** True if the canvas has changes no save request has captured (or the last one failed). */
    val isDirty: Boolean get() = engine.revision != capturedRevision

    /** Reading this from a composable subscribes it to every input it derives from. */
    val status: SaveStatus
        get() = when {
            inFlight > 0 -> SaveStatus.SAVING
            failed -> SaveStatus.FAILED
            isDirty -> SaveStatus.UNSAVED
            else -> SaveStatus.SAVED
        }

    /**
     * Saves now if there is anything to save, and returns a handle to await: the outcome, or null
     * when nothing was dirty and nothing was in flight. If nothing is dirty but an earlier save is
     * still being written, the handle is THAT save -- Back must not navigate ahead of it either.
     * Main thread only (see the class doc); the capture has happened by the time this returns.
     */
    fun flush(reason: SaveReason): Deferred<SaveOutcome?> {
        val started = nowMs()
        if (!isDirty) {
            val running = synchronized(lock) { lastRequest?.takeIf { !it.isCompleted } }
            if (reason.lifecycle) {
                log("${reason.label}: nothing to save (no changes since last save${if (running != null) "; waiting on the save in flight" else ""})")
            }
            // NOT CompletableDeferred(null): with a null literal that resolves to the (parent: Job?)
            // constructor and yields a deferred that never completes -- Back would wait out its timeout.
            return running ?: CompletableDeferred<SaveOutcome?>().apply { complete(null) }
        }

        val rev = engine.revision
        val currentMeta = synchronized(lock) { meta }
        // The capture (layer metadata + bitmap copies of dirty layers) happens inside this call.
        val pending = save(currentMeta, engine)
        val result = CompletableDeferred<SaveOutcome?>()
        val seq = synchronized(lock) {
            requestSeq += 1
            inFlight += 1
            capturedRevision = rev
            dirtySinceMs = null
            strokesSinceSave = 0
            lastRequest = result
            requestSeq
        }
        if (reason.lifecycle) log("${reason.label}: saving unsaved changes")
        // Registered only after the bookkeeping above: a capture failure returns an already-completed
        // Deferred, whose handler runs right here, inline.
        pending.invokeOnCompletion { onSaveFinished(seq, reason, started, pending, result) }
        return result
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun onSaveFinished(
        seq: Int,
        reason: SaveReason,
        started: Long,
        pending: Deferred<SaveOutcome>,
        result: CompletableDeferred<SaveOutcome?>,
    ) {
        val outcome = try { pending.getCompleted() } catch (_: Throwable) { null }
        val ok = outcome?.saved == true
        synchronized(lock) {
            inFlight -= 1
            if (ok) meta = outcome!!.meta
            // Only the newest request decides the indicator and the dirty flag: an older one that
            // finishes (or is superseded and shares the newer one's outcome) after a newer capture
            // must not undo it. The newer capture's pixels are a superset (layer versions only grow).
            if (seq == requestSeq) {
                failed = !ok
                if (!ok) capturedRevision = FAILED_SENTINEL
            }
        }
        if (reason.lifecycle) {
            val ms = nowMs() - started
            log(if (ok) "${reason.label}: save committed (${ms}ms)" else "${reason.label}: save FAILED (${outcome?.failure?.kind ?: "no outcome"}) after ${ms}ms; changes stay unsaved")
        }
        result.complete(outcome)
    }

    /** Call after every committed stroke/fill/selection move; the every-Nth-commit backstop under the debounce. */
    fun noteStrokeCommitted() {
        val due = synchronized(lock) { ++strokesSinceSave >= strokeBackstop }
        if (due) flush(SaveReason.STROKE_BACKSTOP)
    }

    /** ON_STOP is the last moment an app going to the background is guaranteed to run code. */
    val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) flush(SaveReason.STOP)
    }

    /** [ComponentCallbacks2.onTrimMemory]: from UI_HIDDEN up the app has no visible UI and is a kill candidate. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flush(SaveReason.TRIM_MEMORY)
    }

    /** The editor is being left: one last flush (free if already clean), then stop receiving trim callbacks. */
    fun close() {
        flush(SaveReason.DISPOSE)
        onClose(this)
    }

    /**
     * The debounce timer: suspends for the life of the collecting scope (the editor screen). Each
     * revision change restarts the wait, capped at [maxLatencyMs] since the canvas first became
     * dirty. A due save waits out a stylus stroke still in flight rather than landing mid-stroke.
     */
    suspend fun run() {
        revisions.collectLatest {
            if (!isDirty) return@collectLatest
            val since = synchronized(lock) { dirtySinceMs ?: nowMs().also { dirtySinceMs = it } }
            val remainingToCap = maxLatencyMs - (nowMs() - since)
            delay(minOf(debounceMs, remainingToCap).coerceAtLeast(0L))
            while (engine.strokeInProgressLayerId != null) delay(STROKE_RECHECK_MS)
            flush(SaveReason.DEBOUNCE)
        }
    }

    companion object {
        const val DEBOUNCE_MS = 2_000L
        const val MAX_LATENCY_MS = 30_000L
        const val STROKE_BACKSTOP = 6
        private const val STROKE_RECHECK_MS = 250L

        /** Never equals a real revision, so a failed save's edits read as dirty. */
        private const val FAILED_SENTINEL = Int.MIN_VALUE
    }
}
