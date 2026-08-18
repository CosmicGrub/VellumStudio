package com.vellum.studio.canvas

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One reversible edit: the full bitmap content of [layerId] immediately before the stroke began. */
private class StrokeEdit(val layerId: String, val before: Bitmap)

/**
 * Stroke-level undo/redo. A snapshot of the target layer is taken at stroke-start (see
 * [DrawingCanvasView]) and pushed here at stroke-end together with the *new* content, so undo/redo
 * is a cheap bitmap swap rather than a re-simulation of the stroke.
 *
 * Bounded to [maxDepth] steps; the oldest snapshots are recycled to cap memory use, which matters
 * at high canvas resolutions where each snapshot is a full ARGB_8888 copy.
 */
class UndoManager(private val maxDepth: Int = 25) {
    private class Entry(val layerId: String, val before: Bitmap, val after: Bitmap)

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Call once, right when a stroke starts, with a copy of the layer's bitmap at that instant. */
    fun beginStroke(layerId: String, before: Bitmap): PendingStroke = PendingStroke(layerId, before)

    inner class PendingStroke(private val layerId: String, private val before: Bitmap) {
        /** Call when the stroke lifts; [after] is a copy of the layer's bitmap post-stroke. */
        fun commit(after: Bitmap) {
            redoStack.forEach { it.before.recycle(); it.after.recycle() }
            redoStack.clear()
            undoStack.addLast(Entry(layerId, before, after))
            while (undoStack.size > maxDepth) {
                val evicted = undoStack.removeFirst()
                evicted.before.recycle()
                // keep evicted.after alive only if some later entry's "before" happens to alias it;
                // in this stroke-snapshot model each Entry owns independent bitmaps, so it's safe to drop.
                evicted.after.recycle()
            }
            refreshFlags()
        }

        /** Call if the stroke ended up being a no-op (e.g. zero-length tap) to avoid a wasted step. */
        fun discard() {
            before.recycle()
        }

        /** Call on ACTION_CANCEL to revert whatever partial drawing already hit the layer bitmap. */
        fun rollback(layer: Layer) {
            layer.restore(before)
            before.recycle()
        }
    }

    // Both walk past (and drop) any entry whose layer has since been deleted, instead of stopping on
    // the first one. A stroke's own layer isn't tracked by undo/redo the way its CONTENT is - once
    // that layer is gone, the entry can never apply again on either an undo or a later redo, so
    // silently no-op'ing on it (the old behavior) left the user pressing Undo repeatedly with no
    // visible effect, once per dead entry, before reaching a step that actually did something.
    // Dropping instead of skip-and-requeue also means those bitmaps get recycled instead of sitting
    // in the destination stack forever as permanently-inert dead weight.
    fun undo(findLayer: (String) -> Layer?) {
        while (true) {
            val entry = undoStack.removeLastOrNull() ?: break
            val layer = findLayer(entry.layerId)
            if (layer != null) {
                redoStack.addLast(entry)
                layer.restore(entry.before)
                break
            }
            entry.before.recycle()
            entry.after.recycle()
        }
        refreshFlags()
    }

    fun redo(findLayer: (String) -> Layer?) {
        while (true) {
            val entry = redoStack.removeLastOrNull() ?: break
            val layer = findLayer(entry.layerId)
            if (layer != null) {
                undoStack.addLast(entry)
                layer.restore(entry.after)
                break
            }
            entry.before.recycle()
            entry.after.recycle()
        }
        refreshFlags()
    }

    fun clear() {
        undoStack.forEach { it.before.recycle(); it.after.recycle() }
        redoStack.forEach { it.before.recycle(); it.after.recycle() }
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
    }
}
