package com.vellum.studio.canvas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pen-down / pen-up cost measurement for this item's roadmap doneWhen ("pen-down and pen-up times
 * are recorded before and after"). These are plain JVM/Robolectric-NATIVE numbers timed on whatever
 * machine runs the Gradle test task -- NOT device numbers (no JIT warmup control comparable to a
 * real app process, no real GPU/compositor, no S Pen latency chain) -- and are printed via
 * `println` (visible in the test task's console/log output) rather than compared against a device
 * baseline that does not exist in a JVM test.
 *
 * What this item DID and DID NOT change at the call-site level, stated honestly:
 *  - PEN-DOWN cost (the full-canvas [Layer.snapshot] taken at ACTION_DOWN, in [DrawingCanvasView.
 *    startStroke]) is UNCHANGED by this item. This item crops the STORED undo step, not the
 *    transient pre-stroke snapshot every brush still takes today -- eliminating that pen-down copy
 *    (e.g. by reading a persistent "shadow" of the layer's last-committed state instead) is called
 *    out as later, separate work in the roadmap's own approach notes, not part of this item's scope.
 *  - PEN-UP (commit) cost: cropping does strictly more CPU work per commit call than the pre-item
 *    shape (two `Bitmap.createBitmap` sub-rect copies, versus just keeping the two full bitmaps
 *    references as-is), so a SLOWER commit call is the honestly-expected result, not a regression to
 *    explain away. What this item actually buys is BYTES RETAINED per step (measured in the second
 *    test below) and therefore undo DEPTH for the same budget (see CropBasedUndoPropertyTest's
 *    dedicated depth test) -- not commit-call latency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CropBasedUndoTimingTest {

    private val size = 2048

    private fun avgNanosOf(iterations: Int, warmup: Int = 3, block: () -> Unit): Double {
        repeat(warmup) { block() }
        var total = 0L
        repeat(iterations) {
            val start = System.nanoTime()
            block()
            total += System.nanoTime() - start
        }
        return total.toDouble() / iterations
    }

    @Test fun `pen-down and pen-up costs are measured and logged honestly as JVM numbers`() {
        val layer = Layer(name = "L", bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888))
        val paint = Paint().apply { color = Color.RED }

        val penDownAvgNs = avgNanosOf(10) {
            layer.snapshot().recycle()
        }

        // OLD-style pen-up: exactly the pre-item commit path (this item's own fallback for
        // unreported bounds) -- the two full bitmaps are kept as-is, no extra copy.
        val oldStyleCommitAvgNs = avgNanosOf(10) {
            val before = layer.snapshot()
            Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 400f, paint)
            UndoManager().beginStroke(layer.id, before).commit(layer.snapshot(), dirtyRect = null)
        }

        // NEW-style pen-up: crop both to a 400x400 dirty rect (this item's own roadmap approach
        // note's example stroke size) and recycle the two full temporaries.
        val newStyleCommitAvgNs = avgNanosOf(10) {
            val before = layer.snapshot()
            Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 400f, paint)
            UndoManager().beginStroke(layer.id, before).commit(layer.snapshot(), dirtyRect = Rect(0, 0, 400, 400))
        }

        println(
            "CropBasedUndoTimingTest (JVM numbers, NOT device numbers; ${size}x$size layer, " +
                "10-iteration average after 3-iteration warmup): " +
                "pen-down (full Layer.snapshot) = ${"%.0f".format(penDownAvgNs)} ns; " +
                "pen-up/commit OLD-style (no crop, two full bitmaps kept) = ${"%.0f".format(oldStyleCommitAvgNs)} ns; " +
                "pen-up/commit NEW-style (crop to 400x400 + recycle full temporaries) = ${"%.0f".format(newStyleCommitAvgNs)} ns.",
        )

        // Loose sanity bounds only -- JVM wall-clock timing is inherently noisy (JIT, GC pauses), so
        // this deliberately does NOT assert an ordering between old/new commit cost (see the class
        // doc: cropping is honestly expected to cost MORE CPU per call, not less). It only guards
        // against a catastrophic regression (an accidental O(canvas^2) loop, a runaway retry) hiding
        // in either path.
        val oneSecondNs = 1_000_000_000.0
        assertTrue("pen-down took suspiciously long: ${penDownAvgNs}ns", penDownAvgNs < oneSecondNs)
        assertTrue("old-style commit took suspiciously long: ${oldStyleCommitAvgNs}ns", oldStyleCommitAvgNs < oneSecondNs)
        assertTrue("new-style commit took suspiciously long: ${newStyleCommitAvgNs}ns", newStyleCommitAvgNs < oneSecondNs)
    }

    @Test fun `crop-based steps retain far fewer bytes per step than the pre-item full-bitmap shape`() {
        val layer = Layer(name = "L", bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888))
        val paint = Paint().apply { color = Color.RED }

        val oldStyleManager = UndoManager()
        val before1 = layer.snapshot()
        Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 400f, paint)
        oldStyleManager.beginStroke(layer.id, before1).commit(layer.snapshot(), dirtyRect = null)
        val oldStyleBytes = oldStyleManager.undoBytes()

        val newStyleManager = UndoManager()
        val before2 = layer.snapshot()
        Canvas(layer.bitmap).drawRect(0f, 0f, 400f, 400f, paint)
        newStyleManager.beginStroke(layer.id, before2).commit(layer.snapshot(), dirtyRect = Rect(0, 0, 400, 400))
        val newStyleBytes = newStyleManager.undoBytes()

        println(
            "CropBasedUndoTimingTest: retained bytes for one 400x400 stroke on a ${size}x$size layer -- " +
                "OLD-style (two full bitmaps) = $oldStyleBytes bytes; NEW-style (two 400x400 crops) = $newStyleBytes bytes " +
                "(${"%.1f".format(oldStyleBytes.toDouble() / newStyleBytes)}x smaller).",
        )

        assertTrue(
            "expected the old full-bitmap shape to retain the whole ${size}x$size canvas twice over",
            oldStyleBytes == size.toLong() * size.toLong() * 4L * 2L,
        )
        assertTrue("expected the new crop shape to retain only the 400x400 region twice over", newStyleBytes == 400L * 400L * 4L * 2L)
        assertTrue("crop-based retention should be at least 10x smaller for a small stroke on a large canvas", oldStyleBytes >= newStyleBytes * 10)
    }
}
