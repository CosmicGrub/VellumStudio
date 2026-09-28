package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vellum.studio.BuildConfig

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

/**
 * The pixel content of [layerId] immediately before / after a stroke (or fill, selection move...),
 * cropped to [rect] -- the crop-based replacement for the old full-canvas before/after pair (see
 * [UndoManager]'s class doc). [rect] can be the WHOLE layer (when the caller couldn't report a
 * tighter bounds -- see [UndoManager.PendingStroke.commit]'s fallback), in which case [before]/
 * [after] simply equal what used to be stored directly; a crop is exactly the same shape of step,
 * only smaller, so there is no separate "full" step type.
 */
internal class PixelEdit(val layerId: String, val rect: Rect, val before: Bitmap, val after: Bitmap) : UndoStep {
    override val bytes: Long = bitmapBytes(before) + bitmapBytes(after)

    override fun undo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val layer = findLayer(layerId) ?: return false
        layer.restoreRect(rect, before)
        return true
    }

    override fun redo(findLayer: (String) -> Layer?, host: LayerStackHost?): Boolean {
        val layer = findLayer(layerId) ?: return false
        layer.restoreRect(rect, after)
        return true
    }

    override fun discard(applied: Boolean) {
        before.recycle()
        after.recycle()
    }
}

/** [rect] intersected against a [width]x[height] bitmap, or null if nothing of it survives (fully
 * off-canvas -- can happen for a stroke/mirror that strayed entirely outside the layer). */
private fun clampRectToBitmap(rect: Rect, width: Int, height: Int): Rect? {
    val clamped = Rect(rect)
    return if (clamped.intersect(0, 0, width, height) && !clamped.isEmpty) clamped else null
}

/**
 * Debug-only correctness net for crop-based undo (see the roadmap item this shipped with): diffs
 * [before] against [after] OUTSIDE [rect] and throws if anything differs there. A real difference
 * outside the reported rect means whatever fed [rect] under-reported the stroke's true bounds --
 * the two known ways that happens are [CanvasEngine.flattenScratchOnto]'s wetness blur bleeding past
 * the raw dab footprint, and a symmetry mirror whose own dirty bounds didn't get unioned in -- and
 * committing it anyway would silently corrupt undo/redo (a later undo would restore the crop but
 * leave the un-reported, still-changed pixels outside it exactly as the bad stroke left them).
 *
 * Compares via four bulk [Bitmap.getPixels] calls (the disjoint strips above/below/left-of/right-of
 * [rect] that exactly tile "everything outside rect") rather than a per-pixel `getPixel` scan --
 * each `getPixel` is its own JNI round-trip, and a full 2048x2048 canvas is 4+ million of them; only
 * ever runs under [BuildConfig.DEBUG] (see [UndoManager.PendingStroke.commit]), but "debug-only"
 * should still mean "usable while actually drawing on a device", not "technically doesn't ship".
 */
private fun assertRectCoversAllChanges(before: Bitmap, after: Bitmap, rect: Rect) {
    val width = before.width
    val height = before.height

    fun stripDiffers(x: Int, y: Int, stripWidth: Int, stripHeight: Int): Boolean {
        if (stripWidth <= 0 || stripHeight <= 0) return false
        val n = stripWidth * stripHeight
        val beforePixels = IntArray(n)
        val afterPixels = IntArray(n)
        before.getPixels(beforePixels, 0, stripWidth, x, y, stripWidth, stripHeight)
        after.getPixels(afterPixels, 0, stripWidth, x, y, stripWidth, stripHeight)
        return !beforePixels.contentEquals(afterPixels)
    }

    val differsOutsideRect =
        stripDiffers(0, 0, width, rect.top) || // full-width strip above the rect
            stripDiffers(0, rect.bottom, width, height - rect.bottom) || // full-width strip below
            stripDiffers(0, rect.top, rect.left, rect.height()) || // left of the rect, its own rows
            stripDiffers(rect.right, rect.top, width - rect.right, rect.height()) // right of the rect

    check(!differsOutsideRect) {
        "UndoManager: dirty rect $rect under-reports the actual change on a ${width}x$height layer " +
            "-- a pixel outside it differs before vs. after. Likely cause: wetness blur or a " +
            "symmetry mirror bled past the reported bounds without being accounted for."
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
 * Undo/redo history. Pixel edits (a stroke, fill, selection move) are stored as a before/after pair
 * CROPPED to the region actually touched -- see [PixelEdit] and [DrawingCanvasView] -- so undo/redo
 * is a cheap rect-sized bitmap restore rather than a re-simulation or a full-canvas swap. Layer
 * structure (add / delete / reorder) and layer properties (opacity, visibility, blend mode, lock)
 * are steps in the SAME linear history, so Ctrl+Z always undoes the most recent thing the user did,
 * whatever kind it was.
 *
 * The [beginStroke]/[PendingStroke.commit] API's SHAPE is unchanged -- still a facade over one
 * sealed step type per pixel edit -- so the dab loop is untouched either way; [commit] itself grew
 * an optional dirty-rect parameter (see its own doc) when this item moved [PixelEdit] off full-canvas
 * bitmaps, which is why [DrawingCanvasView] (not [StrokeRenderer]/[BrushStampCache]) now also has to
 * accumulate and pass that rect.
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
 * Eviction is by BYTES, not step count: a removed-layer step pins a full ARGB_8888 canvas, while a
 * pixel step pins only two crops the size of whatever it actually touched (see [PixelEdit] -- a
 * typical stroke's crop is a small fraction of the full canvas, which is the whole point of this
 * item: the same [budgetBytes] now buys dozens of steps instead of the ~6 a pair of full-canvas
 * bitmaps used to allow), and property/move steps are a few bytes. Oldest steps go first while the
 * undo stack is over [budgetBytes], but the newest [minKeptSteps] always survive -- no longer a
 * "floor that overrides the budget" the way the old fixed 6-step depth did, just enough to guarantee
 * one huge step (an uncropped fallback entry, or one genuinely enormous stroke) can never evict
 * itself and leave zero undo -- and [maxDepth] caps the step count outright so a long run of cheap
 * steps can't grow unbounded. Oldest-first is also what keeps history consistent: a step referencing
 * a layer is always evicted before any newer step that deletes it, so a live layer never has stale
 * steps pointing at recycled pixels.
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
        /**
         * Call when the stroke lifts; [after] is a copy of the layer's bitmap post-stroke.
         *
         * [dirtyRect] is the caller-reported union of everything the stroke actually touched
         * (DrawingCanvasView unions [StrokeEngine.takeDirtyBounds] across the whole stroke, padded
         * for AA and for wetness blur -- see its own doc). When it is null, empty, or entirely
         * off-canvas -- fill/selection-move not yet reporting one, or a degenerate case -- this
         * falls back to a step covering the WHOLE layer using [before]/[after] directly (no crop,
         * no extra copy): correct either way, just not memory-optimal, which is exactly the
         * "unknown bounds fall back to a full-layer entry" rule this item's approach calls for.
         *
         * A crop, when one applies, is taken here (not by the caller) so [before] can be recycled
         * immediately after -- keeping only the small cropped pair alive in history, not the two
         * full-canvas snapshots a stroke needed to produce them.
         */
        fun commit(after: Bitmap, dirtyRect: Rect? = null) {
            val rect = dirtyRect?.let { clampRectToBitmap(it, before.width, before.height) }
            if (rect == null) {
                push(PixelEdit(layerId, Rect(0, 0, before.width, before.height), before, after))
                return
            }
            if (BuildConfig.DEBUG) assertRectCoversAllChanges(before, after, rect)
            val beforeCrop = Bitmap.createBitmap(before, rect.left, rect.top, rect.width(), rect.height())
            val afterCrop = Bitmap.createBitmap(after, rect.left, rect.top, rect.width(), rect.height())
            before.recycle()
            after.recycle()
            push(PixelEdit(layerId, rect, beforeCrop, afterCrop))
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
