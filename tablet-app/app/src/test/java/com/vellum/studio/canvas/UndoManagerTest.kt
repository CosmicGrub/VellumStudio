package com.vellum.studio.canvas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [UndoManager] operates directly on [Layer]/[android.graphics.Bitmap] (a stroke-start snapshot,
 * swapped back in on undo/redo), so -- like [RegionAnalyzerTest]/[ShapeAssistTest] -- this runs
 * under Robolectric for real Bitmap/Canvas pixel behavior rather than as a plain JVM unit test.
 *
 * Each simulated "stroke" is just `layer.bitmap.eraseColor(...)` to a distinct solid color, which
 * is enough to tell, by reading back a single pixel, exactly which snapshot a given undo/redo
 * step actually restored -- without needing any real stroke rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class UndoManagerTest {

    private fun solidBitmap(color: Int, size: Int = 4): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private fun newLayer(color: Int): Layer = Layer(name = "layer", bitmap = solidBitmap(color))

    /** Simulates one full stroke: begin, mutate the layer's bitmap in place, commit. */
    private fun stroke(undoManager: UndoManager, layer: Layer, toColor: Int) {
        val pending = undoManager.beginStroke(layer.id, layer.snapshot())
        layer.bitmap.eraseColor(toColor)
        pending.commit(layer.snapshot())
    }

    private fun pixelOf(layer: Layer): Int = layer.bitmap.getPixel(0, 0)

    @Test fun `commit pushes an undoable entry and leaves redo empty`() {
        val undoManager = UndoManager()
        val layer = newLayer(Color.RED)
        assertFalse(undoManager.canUndo)
        assertFalse(undoManager.canRedo)

        stroke(undoManager, layer, Color.GREEN)

        assertTrue(undoManager.canUndo)
        assertFalse(undoManager.canRedo)
    }

    @Test fun `undo restores the pre-stroke content and enables redo`() {
        val undoManager = UndoManager()
        val layer = newLayer(Color.RED)
        stroke(undoManager, layer, Color.GREEN)

        undoManager.undo { id -> layer.takeIf { it.id == id } }

        assertEquals(Color.RED, pixelOf(layer))
        assertFalse(undoManager.canUndo)
        assertTrue(undoManager.canRedo)
    }

    @Test fun `redo restores the post-stroke content`() {
        val undoManager = UndoManager()
        val layer = newLayer(Color.RED)
        stroke(undoManager, layer, Color.GREEN)
        undoManager.undo { id -> layer.takeIf { it.id == id } }

        undoManager.redo { id -> layer.takeIf { it.id == id } }

        assertEquals(Color.GREEN, pixelOf(layer))
        assertTrue(undoManager.canUndo)
        assertFalse(undoManager.canRedo)
    }

    @Test fun `a new push after undo clears the redo stack`() {
        val undoManager = UndoManager()
        val layer = newLayer(Color.RED)

        stroke(undoManager, layer, Color.GREEN) // red -> green
        undoManager.undo { id -> layer.takeIf { it.id == id } } // back to red; redo(green) now available
        assertTrue(undoManager.canRedo)

        stroke(undoManager, layer, Color.BLUE) // red -> blue, pushed without ever redoing the green stroke

        assertFalse("a fresh push must clear the old redo stack", undoManager.canRedo)
        assertTrue(undoManager.canUndo)

        // Confirm redo is genuinely gone, not just flagged false: it must be a safe no-op, and the
        // long-gone "green" entry must never resurface.
        undoManager.redo { id -> layer.takeIf { it.id == id } }
        assertEquals(Color.BLUE, pixelOf(layer))
    }

    @Test fun `undo stack is capped at maxDepth, oldest entries evicted first`() {
        val undoManager = UndoManager(maxDepth = 3)
        val layer = newLayer(Color.RED)
        for (color in listOf(Color.GREEN, Color.BLUE, Color.YELLOW, Color.MAGENTA, Color.CYAN)) {
            stroke(undoManager, layer, color)
        }
        assertTrue(undoManager.canUndo)

        // 5 pushes into a maxDepth=3 manager: only the most recent 3 undo steps should survive.
        repeat(3) { undoManager.undo { id -> layer.takeIf { it.id == id } } }
        assertFalse("only 3 undo steps should have survived the depth cap", undoManager.canUndo)

        // A 4th undo against an empty stack must be a safe no-op -- no crash, no further content change.
        val colorBeforeExtraUndo = pixelOf(layer)
        undoManager.undo { id -> layer.takeIf { it.id == id } }
        assertEquals(colorBeforeExtraUndo, pixelOf(layer))
    }

    @Test fun `undo skips a dead top-of-stack entry and lands on the next resolvable one`() {
        val undoManager = UndoManager()
        val layerA = newLayer(Color.RED)
        val layerB = newLayer(Color.RED)

        stroke(undoManager, layerB, Color.BLUE) // pushed first -- bottom of the undo stack
        stroke(undoManager, layerA, Color.GREEN) // pushed second -- top of the undo stack

        // layerA has since been deleted from the project -- undo() must walk PAST its now-dead,
        // top-of-stack entry and land on layerB's underneath it, rather than getting stuck on (or
        // silently no-op'ing because of) the dead entry.
        undoManager.undo { id -> if (id == layerB.id) layerB else null }

        assertEquals(Color.RED, pixelOf(layerB))
        assertFalse("both the dropped dead entry and the one actually applied should be gone", undoManager.canUndo)
        assertTrue("the entry that was actually applied should still be redoable", undoManager.canRedo)
    }
}
