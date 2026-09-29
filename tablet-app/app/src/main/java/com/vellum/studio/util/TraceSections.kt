package com.vellum.studio.util

import android.os.Trace

/**
 * Constant [android.os.Trace] section names for the handful of coarse hot spots worth seeing as
 * their own slices in a Perfetto capture -- a 60-minute field session otherwise leaves nothing for
 * Perfetto to attribute time to beyond raw method samples, which is exactly the gap this item closes
 * (see the roadmap's "Trace sections and session vitals" item). Deliberately a flat object of
 * `const val`s rather than an enum: [Trace.beginSection] takes a raw [String], so a constant here
 * inlines to that literal at every call site with zero indirection, and grepping this one file
 * answers "what sections exist" without chasing an enum's `.name`.
 *
 * Placement rule (do not add to this file without re-reading it): every section lives in
 * [com.vellum.studio.canvas.DrawingCanvasView], the save/load path
 * ([com.vellum.studio.model.ProjectRepository]), or [com.vellum.studio.canvas.UndoManager] --
 * NEVER inside StrokeRenderer.kt or BrushStampCache.kt (the frozen dab loop). Those two files stay
 * byte-for-byte unchanged by this item; a section around a call INTO them (e.g. [STROKE_DOWN] wraps
 * the view's `startStroke`, which calls into [com.vellum.studio.canvas.DabStrokeEngine]) is fine --
 * editing their own source is not.
 */
object TraceSections {
    /** [com.vellum.studio.canvas.DrawingCanvasView.startStroke] -- a stylus ACTION_DOWN claiming the stroke. */
    const val STROKE_DOWN = "Stroke.down"

    /** [com.vellum.studio.canvas.DrawingCanvasView.moveStroke] -- one ACTION_MOVE, historical samples included. */
    const val STROKE_MOVE = "Stroke.move"

    /** [com.vellum.studio.canvas.DrawingCanvasView.endStroke] -- flatten-to-layer plus the undo commit. */
    const val STROKE_COMMIT = "Stroke.commit"

    /** [com.vellum.studio.canvas.DrawingCanvasView.performFill] -- one bucket-fill tap. */
    const val FILL_PERFORM = "Fill.perform"

    /** [com.vellum.studio.model.ProjectRepository.runSave] -- the actual encode+fsync+rename to disk
     * (not the cheap main-thread capture that queues it -- see [SAVE_PERSIST]'s call site). */
    const val SAVE_PERSIST = "Save.persist"

    /** [com.vellum.studio.model.ProjectRepository.loadBlocking] -- decoding every layer PNG off disk. */
    const val PROJECT_LOAD = "Project.load"

    /** [com.vellum.studio.model.ProjectRepository.writeThumbnail] -- the gallery-card thumbnail flatten. */
    const val PROJECT_THUMBNAIL = "Project.thumbnail"

    /** [com.vellum.studio.canvas.Layer.restore] / `restoreRect` as called from [com.vellum.studio.canvas.UndoManager]'s
     * undo/redo/rollback -- restoring a layer's pixels from a history step. */
    const val LAYER_RESTORE = "Layer.restore"
}

/**
 * Runs [block] inside a `Trace.beginSection(name)` / `endSection()` pair and returns its result.
 * `inline` so a bare `return` inside [block] still returns from the CALLER (a non-local return
 * through an inlined lambda), not from this function -- which is what lets every call site below
 * wrap an existing function body verbatim, early returns and all, by changing only its signature
 * line (`fun foo(...) { ... }` -> `fun foo(...) = trace(NAME) { ... }`) rather than restructuring it.
 *
 * [Trace.beginSection]/[Trace.endSection] are themselves near-free when tracing is not active (the
 * platform checks its own enabled flag internally before doing any real work), so this adds no
 * meaningful overhead to code that already ran the same block directly -- exactly the "negligible
 * and cheap when tracing is disabled" bar this item's guidance sets. The try/finally guarantees
 * [Trace.endSection] always runs, even through one of those early returns or a thrown exception --
 * an unbalanced begin/end would corrupt every section reported for the rest of the process's trace.
 */
inline fun <T> trace(sectionName: String, block: () -> T): T {
    Trace.beginSection(sectionName)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
