package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.vellum.studio.VellumApp
import com.vellum.studio.util.DeviceCapabilities
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Correctness net for crop-based undo (this item's roadmap doneWhen -- the highest-risk item in
 * Wave 2, since it touches rollback semantics): a from-scratch, test-only oracle
 * ([ReferenceFullSnapshotUndo]) that undoes/redoes via full-canvas bitmap swaps -- exactly what
 * [UndoManager.PixelEdit] did before this item cropped it -- run in lockstep against the REAL,
 * now-cropped [UndoManager] over a long random walk of strokes, fills and selection moves, asserting
 * bit-exact pixel equality after every single undo/redo. The oracle never touches, wraps or reuses
 * any of [UndoManager]/[PixelEdit]/[Layer.restoreRect], so a bug specific to the crop path (an
 * off-by-one in the reported rect, a wrong restore blend mode, a bad recycle) cannot also be present
 * in the oracle and silently cancel out in the comparison.
 *
 * `@GraphicsMode(NATIVE)`: every assertion here depends on real ARGB_8888 pixel storage
 * (`Bitmap.createBitmap(src, x, y, w, h)` cropping, `getPixels` bulk reads, `BlendMode.SRC`/`CLEAR`
 * compositing) -- the legacy shadow does not reliably round-trip any of that (see
 * DrawingCanvasViewInputRoutingTest's own doc comment for the same reasoning). [VellumApp] is the
 * Robolectric application because the layer-delete test below drives a real [CanvasEngine], whose
 * constructor sizes the undo budget off [com.vellum.studio.util.DeviceCapabilities], which reads
 * [VellumApp.instance] (only set by [VellumApp.onCreate]) -- same reason UndoIntegrityTest/
 * StructuralUndoTest use it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropBasedUndoPropertyTest {

    private val width = 96
    private val height = 64

    private fun blankLayer(): Layer = Layer(name = "L", bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun bitmapsEqual(a: Bitmap, b: Bitmap): Boolean =
        a.width == b.width && a.height == b.height && pixelsOf(a).contentEquals(pixelsOf(b))

    // ---- a from-scratch oracle, independent of UndoManager/PixelEdit/Layer.restoreRect ----

    /**
     * Deliberately re-implements "undo/redo as full-canvas bitmap swaps" from scratch -- exactly
     * [UndoManager.PixelEdit]'s behavior before this item cropped it -- kept only in this test source
     * set. The one thing it reuses is [Layer.restore] (a pre-existing, Wave-1 full-canvas erase+draw
     * that predates and is untouched by this item), never [Layer.restoreRect] or [UndoManager]
     * itself.
     */
    private class ReferenceFullSnapshotUndo {
        private class Step(val before: Bitmap, val after: Bitmap)
        private val undoStack = ArrayDeque<Step>()
        private val redoStack = ArrayDeque<Step>()

        fun push(before: Bitmap, after: Bitmap) {
            redoStack.forEach { it.before.recycle(); it.after.recycle() }
            redoStack.clear()
            undoStack.addLast(Step(before, after))
        }

        fun undo(layer: Layer) {
            val step = undoStack.removeLastOrNull() ?: return
            layer.restore(step.before)
            redoStack.addLast(step)
        }

        fun redo(layer: Layer) {
            val step = redoStack.removeLastOrNull() ?: return
            layer.restore(step.after)
            undoStack.addLast(step)
        }
    }

    // ---- planned random actions: generated ONCE, then replayed identically onto both bitmaps, so
    // the two implementations always see the exact same pixel mutations and only differ in HOW they
    // record/restore history ----

    private sealed class PlannedAction {
        /** Mutates [bitmap] in place and returns the exact rect touched (used to crop the real
         * UndoManager's step -- the oracle doesn't need it, it always keeps the whole canvas). */
        abstract fun apply(bitmap: Bitmap): Rect

        class Stroke(private val rect: Rect, private val color: Int) : PlannedAction() {
            override fun apply(bitmap: Bitmap): Rect {
                Canvas(bitmap).drawRect(
                    rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(),
                    Paint().apply { color = this@Stroke.color },
                )
                return rect
            }
        }

        /** A translucent SRC_OVER fill -- distinct from [Stroke] in that it composites over
         * whatever's already there rather than replacing it, exercising a different pixel-math path
         * (still an exact rect: a plain unantialiased rect fill touches nothing outside it). */
        class Fill(private val rect: Rect, private val color: Int) : PlannedAction() {
            override fun apply(bitmap: Bitmap): Rect {
                val paint = Paint().apply { color = this@Fill.color; blendMode = BlendMode.SRC_OVER }
                Canvas(bitmap).drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
                return rect
            }
        }

        /** Mirrors [DrawingCanvasView.commitSelectionMove]'s real algorithm: extract, clear the
         * source, paste at a translated (clamped) destination. Returns the union of source and
         * destination, exactly like the real call site computes it. */
        class SelectionMove(private val src: Rect, private val dx: Int, private val dy: Int, private val w: Int, private val h: Int) : PlannedAction() {
            override fun apply(bitmap: Bitmap): Rect {
                val extracted = Bitmap.createBitmap(bitmap, src.left, src.top, src.width(), src.height())
                val canvas = Canvas(bitmap)
                canvas.drawRect(src.left.toFloat(), src.top.toFloat(), src.right.toFloat(), src.bottom.toFloat(), Paint().apply { blendMode = BlendMode.CLEAR })
                val destLeft = (src.left + dx).coerceIn(0, w - 1)
                val destTop = (src.top + dy).coerceIn(0, h - 1)
                canvas.drawBitmap(extracted, destLeft.toFloat(), destTop.toFloat(), null)
                extracted.recycle()
                val destRect = Rect(destLeft, destTop, destLeft + src.width(), destTop + src.height())
                val union = Rect(src)
                union.union(destRect)
                union.intersect(0, 0, w, h)
                return union
            }
        }
    }

    private fun randomRect(rnd: Random): Rect {
        val x0 = rnd.nextInt(width)
        val y0 = rnd.nextInt(height)
        val w = 1 + rnd.nextInt(width - x0)
        val h = 1 + rnd.nextInt(height - y0)
        return Rect(x0, y0, x0 + w, y0 + h)
    }

    private fun randomOpaqueColor(rnd: Random) = Color.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
    private fun randomTranslucentColor(rnd: Random) = Color.argb(40 + rnd.nextInt(180), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))

    private fun randomAction(rnd: Random): PlannedAction = when (rnd.nextInt(3)) {
        0 -> PlannedAction.Stroke(randomRect(rnd), randomOpaqueColor(rnd))
        1 -> PlannedAction.Fill(randomRect(rnd), randomTranslucentColor(rnd))
        else -> PlannedAction.SelectionMove(randomRect(rnd), rnd.nextInt(21) - 10, rnd.nextInt(21) - 10, width, height)
    }

    // ---- the property test itself ----

    @Test fun `crop-based undo matches a reference full-snapshot implementation bit-exactly through random strokes fills and selection moves`() {
        for (seed in longArrayOf(1L, 2L, 3L, 4L, 5L)) {
            val rnd = Random(seed)
            val cropLayer = blankLayer()
            val refLayer = blankLayer()
            // No eviction in play here -- this test is purely about crop CORRECTNESS, not depth;
            // eviction under a realistic byte budget is its own dedicated test below.
            val cropUndo = UndoManager(maxDepth = 10_000, budgetBytes = Long.MAX_VALUE)
            val refUndo = ReferenceFullSnapshotUndo()

            repeat(40) {
                val action = randomAction(rnd)
                val cropBefore = cropLayer.snapshot()
                val refBefore = refLayer.snapshot()
                val rect = action.apply(cropLayer.bitmap)
                action.apply(refLayer.bitmap)
                cropLayer.bumpVersion()
                refLayer.bumpVersion()

                cropUndo.beginStroke(cropLayer.id, cropBefore).commit(cropLayer.snapshot(), rect)
                refUndo.push(refBefore, refLayer.snapshot())

                assertTrue("seed=$seed: layers diverged right after an identical mutation", bitmapsEqual(cropLayer.bitmap, refLayer.bitmap))

                // Occasionally undo a few steps then redo them straight back -- in LOCKSTEP on both
                // managers -- so a single long random walk exercises many undo/redo transitions, not
                // just whatever the very last action happened to be, while always leaving history
                // exactly where later actions expect to keep building on it.
                if (rnd.nextInt(3) == 0) {
                    val steps = 1 + rnd.nextInt(3)
                    repeat(steps) {
                        cropUndo.undo { id -> cropLayer.takeIf { it.id == id } }
                        refUndo.undo(refLayer)
                        assertTrue("seed=$seed: undo mismatch", bitmapsEqual(cropLayer.bitmap, refLayer.bitmap))
                    }
                    repeat(steps) {
                        cropUndo.redo { id -> cropLayer.takeIf { it.id == id } }
                        refUndo.redo(refLayer)
                        assertTrue("seed=$seed: redo mismatch", bitmapsEqual(cropLayer.bitmap, refLayer.bitmap))
                    }
                }
            }

            // Full unwind at the end: undo everything, then redo everything, checking every step.
            while (cropUndo.canUndo) {
                cropUndo.undo { id -> cropLayer.takeIf { it.id == id } }
                refUndo.undo(refLayer)
                assertTrue("seed=$seed: full-unwind undo mismatch", bitmapsEqual(cropLayer.bitmap, refLayer.bitmap))
            }
            assertTrue("seed=$seed: full undo must reach the blank starting layer", pixelsOf(cropLayer.bitmap).all { Color.alpha(it) == 0 })
            while (cropUndo.canRedo) {
                cropUndo.redo { id -> cropLayer.takeIf { it.id == id } }
                refUndo.redo(refLayer)
                assertTrue("seed=$seed: full-unwind redo mismatch", bitmapsEqual(cropLayer.bitmap, refLayer.bitmap))
            }
        }
    }

    @Test fun `cancel mid-stroke restores the pre-stroke content exactly and pushes no history step`() {
        val rnd = Random(7)
        val cropLayer = blankLayer()
        val cropUndo = UndoManager()

        // A real committed step first, so "pushes no step" below has a non-trivial baseline to
        // compare against (bytes unchanged, not just "still zero").
        val a = randomAction(rnd)
        val cropBefore0 = cropLayer.snapshot()
        val rectA = a.apply(cropLayer.bitmap)
        cropLayer.bumpVersion()
        cropUndo.beginStroke(cropLayer.id, cropBefore0).commit(cropLayer.snapshot(), rectA)
        val bytesBeforeCancel = cropUndo.undoBytes()
        val pixelsBeforeCancel = pixelsOf(cropLayer.bitmap)

        // Start a second stroke and let some paint actually hit the layer (exactly what an in-flight
        // buildUp brush does before ACTION_CANCEL) -- a full-canvas repaint in a color that cannot
        // coincidentally already be there, so the "precondition: this really changed something"
        // check below can never be a random false negative -- then cancel instead of committing.
        val cropBefore1 = cropLayer.snapshot()
        val pending1 = cropUndo.beginStroke(cropLayer.id, cropBefore1)
        Canvas(cropLayer.bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.MAGENTA })
        assertFalse(
            "precondition: the in-flight paint really touched the layer",
            pixelsBeforeCancel.contentEquals(pixelsOf(cropLayer.bitmap)),
        )

        pending1.rollback(cropLayer)

        assertArrayEquals("rollback must restore exactly the pre-second-stroke content", pixelsBeforeCancel, pixelsOf(cropLayer.bitmap))
        assertEquals("a cancelled stroke must not push a history step", bytesBeforeCancel, cropUndo.undoBytes())
        assertTrue(cropUndo.canUndo)
        assertFalse(cropUndo.canRedo)
    }

    @Test fun `undo walks correctly across a layer delete back to an earlier crop-based pixel edit`() {
        val engine = CanvasEngine(width, height)
        engine.addLayer("A") // the very first layer -- not itself a history step
        val layerA = engine.activeLayer()!!
        val blankPixels = pixelsOf(layerA.bitmap)

        val rnd = Random(99)
        val action = randomAction(rnd)
        val before = layerA.snapshot()
        val rect = action.apply(layerA.bitmap)
        layerA.bumpVersion()
        engine.bumpRevision()
        engine.undoManager.beginStroke(layerA.id, before).commit(layerA.snapshot(), rect)
        val strokedPixels = pixelsOf(layerA.bitmap)
        assertFalse("precondition: the action actually changed layer A", blankPixels.contentEquals(strokedPixels))

        val layerB = engine.addLayer("B")
        engine.activeLayerIndex = engine.layers.indexOf(layerB)
        assertTrue("precondition: layer B was really deleted", engine.deleteActiveLayer())

        assertTrue("undo #1 undoes the delete of layer B", engine.undo())
        assertTrue(engine.layers.any { it.id == layerB.id })
        assertTrue("undo #2 undoes adding layer B", engine.undo())
        assertEquals(1, engine.layers.size)

        assertTrue("undo #3 must reach the crop-based pixel edit on layer A", engine.undo())
        assertArrayEquals("layer A must be restored bit-exactly across the delete/insert boundary", blankPixels, pixelsOf(layerA.bitmap))

        assertTrue("redo replays the crop-based pixel edit", engine.redo())
        assertArrayEquals(strokedPixels, pixelsOf(layerA.bitmap))
    }

    @Test fun `crop-based undo gives far more than the old fixed floor of 6 steps at 2048x2048 within a realistic device budget`() {
        val size = 2048
        val layer = Layer(name = "L", bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888))
        // The exact budget DeviceCapabilitiesTest pins for the Tab S9 FE / Fold5's 512MB large-heap
        // grant: 0.15 * 512MB, clamped into [48, 512]MB -- ~80.5MB.
        val budgetBytes = DeviceCapabilities.undoBudgetBytesFor(512, isLowRamDevice = false)
        val undoManager = UndoManager(maxDepth = CanvasEngine.MAX_UNDO_STEPS, budgetBytes = budgetBytes, minKeptSteps = CanvasEngine.MIN_UNDO_STEPS)

        // A 400x400 dirty rect is the figure this item's own roadmap approach uses ("roughly 25x
        // more depth... for a 400x400 px stroke"). Each crop step costs 400*400*4*2 ≈ 1.22MB, so the
        // ~80.5MB budget should hold on the order of 60+ of them -- push 100 to also exercise real
        // eviction, not just "everything fits".
        var pushedBytes = 0L
        repeat(100) { i ->
            val before = layer.snapshot()
            Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 400f, Paint().apply { color = Color.rgb(i % 256, 0, 0) })
            undoManager.beginStroke(layer.id, before).commit(layer.snapshot(), Rect(0, 0, 400, 400))
            pushedBytes += 400L * 400L * 4L * 2L
        }

        assertTrue("byte eviction should have kicked in once pushed bytes exceeded the budget", undoManager.undoBytes() <= budgetBytes)

        var kept = 0
        while (undoManager.canUndo) {
            undoManager.undo { id -> layer.takeIf { it.id == id } }
            kept++
        }
        // Two FULL 2048x2048 bitmaps per step (the pre-crop behavior) cost ~33.5MB/step, which the
        // same ~80.5MB budget could only fit 2 of -- forced up to exactly 6 by the old depth floor.
        // Crop-based steps at 400x400 are small enough that the SAME budget keeps dramatically more.
        assertTrue("expected byte-driven depth well past the old fixed floor of 6, got $kept steps", kept >= 30)
    }

    @Test fun `the debug assertion catches a dirty rect that misses real pixel change`() {
        // Simulates exactly the bug class this assertion exists to catch: a wetness blur or a
        // symmetry mirror paints past the caller-reported bounds, and the caller (wrongly) reports
        // only the un-blurred/un-mirrored footprint.
        val layer = blankLayer()
        val before = layer.snapshot()
        Canvas(layer.bitmap).drawRect(10f, 10f, 40f, 40f, Paint().apply { color = Color.RED })
        val underReportedRect = Rect(10, 10, 20, 20) // misses most of what was actually painted
        val undoManager = UndoManager()
        val pending = undoManager.beginStroke(layer.id, before)

        assertThrows(IllegalStateException::class.java) {
            pending.commit(layer.snapshot(), underReportedRect)
        }
    }

    @Test fun `the debug assertion does not false-positive on a rect that exactly covers the change`() {
        val layer = blankLayer()
        val before = layer.snapshot()
        Canvas(layer.bitmap).drawRect(10f, 10f, 40f, 40f, Paint().apply { color = Color.RED })
        val undoManager = UndoManager()
        val pending = undoManager.beginStroke(layer.id, before)

        // Must not throw.
        pending.commit(layer.snapshot(), Rect(10, 10, 40, 40))
        assertTrue(undoManager.canUndo)
    }
}
