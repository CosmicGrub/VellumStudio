package com.vellum.studio.canvas

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the structural undo steps need from the engine that owns the layer stack. [UndoManager]
 * deliberately knows nothing about [CanvasEngine] (it stays unit-testable against a fake), so
 * re-attaching / detaching / reordering layers goes through this seam.
 *
 * Contract for implementers: [detachLayer] must remove the layer from the visible stack WITHOUT
 * recycling its bitmap -- the undo step that asked for it now owns that bitmap (see the ownership
 * rules on [UndoManager]). All calls happen on the Main thread.
 */
interface LayerStackHost {
    fun attachLayer(layer: Layer, index: Int)
    fun detachLayer(layer: Layer)
    fun moveLayerTo(layer: Layer, index: Int)
    fun setActiveLayerIndex(index: Int)
}

/** The four undoable per-layer properties, a few bytes -- the whole point of [LayerPropsEdit]. */
internal data class LayerProps(val opacity: Float, val visible: Boolean, val blendMode: LayerBlendMode, val locked: Boolean) {
    fun applyTo(layer: Layer) {
        layer.opacity = opacity
        layer.visible = visible
        layer.blendMode = blendMode
        layer.locked = locked
    }

    companion object {
        fun of(layer: Layer) = LayerProps(layer.opacity, layer.visible, layer.blendMode, layer.locked)
    }
}

/**
 * One reversible history entry. Sealed so [UndoManager]'s stack walking is exhaustive over the
 * step kinds; [PixelEdit] is the original stroke snapshot, the rest are structural.
 *
 * [applied] on [discard] says which stack the step sits on when history drops it for good:
 * true = on the undo stack (its forward effect is currently in force), false = on the redo stack
 * (undone). A step that owns a *detached* layer must recycle that layer's bitmap on discard, and
 * which side owns it flips with [applied] -- a removed layer is detached while applied, an inserted
 * one is detached while undone.
 */
internal sealed interface UndoStep {
    /** Heap this step holds that is NOT already accounted for by a live layer -- the eviction budget input. */
    val bytes: Long

    /** @return false if the step can no longer apply (its layer is gone); the caller then drops it. */
    fun undo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean
    fun redo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean
    fun discard(applied: Boolean)
}

private fun bitmapBytes(b: Bitmap): Long = b.width.toLong() * b.height.toLong() * 4L

/** The full bitmap content of [layerId] immediately before / after a stroke (or fill, selection move...). */
internal class PixelEdit(val layerId: String, val before: Bitmap, val after: Bitmap) : UndoStep {
    override val bytes: Long = bitmapBytes(before) + bitmapBytes(after)

    override fun undo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val layer = findLayer(layerId) ?: return false
        layer.restore(before)
        return true
    }

    override fun redo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val layer = findLayer(layerId) ?: return false
        layer.restore(after)
        return true
    }

    override fun discard(applied: Boolean) {
        before.recycle()
        after.recycle()
    }
}

/**
 * A layer was deleted. Holds the [Layer] object -- and therefore its bitmap -- alive so undo can put
 * back the very same pixels at the very same [index] with the same active layer. Undo of the delete
 * is what the old code made impossible by recycling the bitmap on the spot.
 *
 * While applied (on the undo stack) the layer is detached and this step is its sole owner, so it is
 * counted against the byte budget and its bitmap is recycled when the step is evicted or cleared.
 * While undone (on the redo stack) the layer is live again in the stack -- discarding the step then
 * must NOT recycle it.
 */
internal class LayerRemove(
    val layer: Layer,
    val index: Int,
    val activeBefore: Int,
    val activeAfter: Int,
) : UndoStep {
    override val bytes: Long = bitmapBytes(layer.bitmap)

    override fun undo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val h = requireNotNull(host) { "LayerRemove needs a LayerStackHost" }
        h.attachLayer(layer, index)
        h.setActiveLayerIndex(activeBefore)
        return true
    }

    override fun redo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val h = requireNotNull(host) { "LayerRemove needs a LayerStackHost" }
        h.detachLayer(layer)
        h.setActiveLayerIndex(activeAfter)
        return true
    }

    override fun discard(applied: Boolean) {
        if (applied) layer.bitmap.recycle()
    }
}

/**
 * A layer was added (new, imported reference image, or duplicate). The mirror image of
 * [LayerRemove]: while applied the layer is live and owned by the stack (bytes 0 -- the layers
 * already account for it, and undoing hands the same memory to the redo side rather than adding
 * any); while undone it is detached and owned by this step, so discarding a redo entry (a new edit
 * cleared the redo stack) recycles it.
 */
internal class LayerInsert(
    val layer: Layer,
    val index: Int,
    val activeBefore: Int,
    val activeAfter: Int,
) : UndoStep {
    override val bytes: Long = 0L

    override fun undo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val h = requireNotNull(host) { "LayerInsert needs a LayerStackHost" }
        h.detachLayer(layer)
        h.setActiveLayerIndex(activeBefore)
        return true
    }

    override fun redo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val h = requireNotNull(host) { "LayerInsert needs a LayerStackHost" }
        h.attachLayer(layer, index)
        h.setActiveLayerIndex(activeAfter)
        return true
    }

    override fun discard(applied: Boolean) {
        if (!applied) layer.bitmap.recycle()
    }
}

/** A layer moved in the stack from index [from] to [to]; the active layer followed it. */
internal class LayerMove(
    val layerId: String,
    val from: Int,
    val to: Int,
    val activeBefore: Int,
    val activeAfter: Int,
) : UndoStep {
    override val bytes: Long = 0L

    override fun undo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val h = requireNotNull(host) { "LayerMove needs a LayerStackHost" }
        val layer = findLayer(layerId) ?: return false
        h.moveLayerTo(layer, from)
        h.setActiveLayerIndex(activeBefore)
        return true
    }

    override fun redo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val h = requireNotNull(host) { "LayerMove needs a LayerStackHost" }
        val layer = findLayer(layerId) ?: return false
        h.moveLayerTo(layer, to)
        h.setActiveLayerIndex(activeAfter)
        return true
    }

    override fun discard(applied: Boolean) = Unit
}

/** Opacity / visibility / blend mode / lock of [layerId] before and after ONE user gesture. */
internal class LayerPropsEdit(val layerId: String, val before: LayerProps, val after: LayerProps) : UndoStep {
    override val bytes: Long = 0L

    override fun undo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val layer = findLayer(layerId) ?: return false
        before.applyTo(layer)
        return true
    }

    override fun redo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val layer = findLayer(layerId) ?: return false
        after.applyTo(layer)
        return true
    }

    override fun discard(applied: Boolean) = Unit
}

/**
 * Undo/redo history. Pixel edits (a stroke, fill, selection move) are stored as a stroke-start
 * snapshot plus the new content -- see [DrawingCanvasView] -- so undo/redo is a cheap bitmap swap
 * rather than a re-simulation. Layer structure (add / delete / reorder) and layer properties
 * (opacity, visibility, blend mode, lock) are steps in the SAME linear history, so Ctrl+Z always
 * undoes the most recent thing the user did, whatever kind it was.
 *
 * The [beginStroke]/[PendingStroke.commit] API is unchanged -- a facade over [PixelEdit] -- so the
 * dab loop and [DrawingCanvasView] are untouched by the structural work.
 *
 * LAYER / BITMAP OWNERSHIP (read this before touching layer lifetimes; the save coordinator
 * snapshots layer bitmaps on Main and reads layers off-thread, and depends on these rules):
 *  - A layer in [CanvasEngine.layers] ("attached") is owned by the engine; its bitmap is recycled
 *    only by [CanvasEngine.recycleAll]. No code path recycles an attached layer's bitmap.
 *  - A layer removed from the stack ("detached") is owned by exactly one history step
 *    ([LayerRemove] while applied, [LayerInsert] while undone). It is never in `layers`, so a save
 *    that walks `layers` on Main can never observe a detached (or recycled) bitmap. Its bitmap is
 *    recycled only when that step is discarded: evicted from the undo stack over budget, dropped
 *    from the redo stack by a fresh edit, or [clear]ed. See [UndoStep.discard]'s `applied` flag.
 *  - Every mutation here (push, undo, redo, evict, recycle) runs on Main. Nothing recycles off-thread.
 *
 * Eviction is by BYTES, not step count: a removed-layer step pins a full ARGB_8888 canvas and a
 * pixel step two of them, while property/move steps are a few bytes. Oldest steps go first while the
 * undo stack is over [budgetBytes], but the newest [minKeptSteps] always survive (the old code's
 * depth floor -- a giant canvas must not leave the user with no undo at all), and [maxDepth] caps the
 * step count outright so a long run of cheap steps can't grow unbounded. Oldest-first is also what
 * keeps history consistent: a step referencing a layer is always evicted before any newer step that
 * deletes it, so a live layer never has stale steps pointing at recycled pixels.
 *
 * @param host required only for structural steps; a manager used purely for pixel edits (tests) can omit it.
 */
class UndoManager(
    private val maxDepth: Int = 25,
    private val budgetBytes: Long = Long.MAX_VALUE,
    private val minKeptSteps: Int = 1,
    private val host: LayerStackHost? = null,
) {
    private val undoStack = ArrayDeque<UndoStep>()
    private val redoStack = ArrayDeque<UndoStep>()

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private fun refreshFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    /** Bytes currently pinned by the undo stack -- what [budgetBytes] is enforced against. Test hook. */
    internal fun undoBytes(): Long = undoStack.sumOf { it.bytes }

    private class PendingProps(val layer: Layer, val before: LayerProps)

    private var pendingProps: PendingProps? = null

    /**
     * Pushes [step] as the newest history entry. Any half-finished property gesture is closed first
     * so it lands BEFORE this step (history stays in the order the user did things), the redo stack
     * is discarded (a fresh edit forks history), and the byte budget is enforced.
     */
    private fun push(step: UndoStep) {
        endLayerPropsEdit()
        redoStack.forEach { it.discard(applied = false) }
        redoStack.clear()
        undoStack.addLast(step)
        var bytes = undoBytes()
        while (undoStack.size > maxDepth || (bytes > budgetBytes && undoStack.size > minKeptSteps)) {
            val evicted = undoStack.removeFirst()
            bytes -= evicted.bytes
            evicted.discard(applied = true)
        }
        refreshFlags()
    }

    /** Call once, right when a stroke starts, with a copy of the layer's bitmap at that instant. */
    fun beginStroke(layerId: String, before: Bitmap): PendingStroke = PendingStroke(layerId, before)

    inner class PendingStroke(private val layerId: String, private val before: Bitmap) {
        /** Call when the stroke lifts; [after] is a copy of the layer's bitmap post-stroke. */
        fun commit(after: Bitmap) {
            push(PixelEdit(layerId, before, after))
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

    /** [layer] was just deleted (already detached, bitmap NOT recycled); history now owns its pixels. */
    fun pushLayerRemove(layer: Layer, index: Int, activeBefore: Int, activeAfter: Int) =
        push(LayerRemove(layer, index, activeBefore, activeAfter))

    /** [layer] was just added at [index] (new, imported or duplicated). */
    fun pushLayerInsert(layer: Layer, index: Int, activeBefore: Int, activeAfter: Int) =
        push(LayerInsert(layer, index, activeBefore, activeAfter))

    fun pushLayerMove(layerId: String, from: Int, to: Int, activeBefore: Int, activeAfter: Int) =
        push(LayerMove(layerId, from, to, activeBefore, activeAfter))

    /**
     * Opens a property gesture on [layer]: remembers its opacity/visibility/blend/lock as they are
     * now. Idempotent while the same layer's gesture is open (a slider drag calls this on every
     * tick and only the first one snapshots), so the whole drag becomes ONE step, committed by
     * [endLayerPropsEdit]. Starting a gesture on a different layer closes the previous one first.
     */
    fun beginLayerPropsEdit(layer: Layer) {
        val open = pendingProps
        if (open != null) {
            if (open.layer === layer) return
            endLayerPropsEdit()
        }
        pendingProps = PendingProps(layer, LayerProps.of(layer))
    }

    /** Closes the open property gesture, pushing one step iff something actually changed. Safe to call with none open. */
    fun endLayerPropsEdit() {
        val open = pendingProps ?: return
        pendingProps = null
        val after = LayerProps.of(open.layer)
        if (after == open.before) return
        push(LayerPropsEdit(open.layer.id, open.before, after))
    }

    // Both walk past (and drop) any step that can no longer apply, instead of stopping on the first
    // one. With structural steps in the same history this should now only happen for a step whose
    // layer was removed by something outside the history (e.g. a project reload) -- deleting a layer
    // through the engine is itself a step, so it is undone BEFORE any older step on that layer is
    // reached. Kept as a defensive drop so a dead step can never wedge the Undo button; discarding
    // it also recycles its bitmaps instead of leaving them as inert dead weight.
    fun undo(findLayer: (String) -> Layer?) {
        endLayerPropsEdit()
        while (true) {
            val step = undoStack.removeLastOrNull() ?: break
            if (step.undo(findLayer, host)) {
                redoStack.addLast(step)
                break
            }
            step.discard(applied = true)
        }
        refreshFlags()
    }

    fun redo(findLayer: (String) -> Layer?) {
        endLayerPropsEdit()
        while (true) {
            val step = redoStack.removeLastOrNull() ?: break
            if (step.redo(findLayer, host)) {
                undoStack.addLast(step)
                break
            }
            step.discard(applied = false)
        }
        refreshFlags()
    }

    fun clear() {
        pendingProps = null
        undoStack.forEach { it.discard(applied = true) }
        redoStack.forEach { it.discard(applied = false) }
        undoStack.clear()
        redoStack.clear()
        refreshFlags()
    }
}
