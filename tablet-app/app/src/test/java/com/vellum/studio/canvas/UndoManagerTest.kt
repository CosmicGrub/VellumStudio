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

    // ---- structural steps, byte-budget eviction and bitmap ownership ----

    /** A minimal stand-in for CanvasEngine's layer stack so the manager is tested in isolation. */
    private class FakeHost(val layers: MutableList<Layer> = mutableListOf()) : LayerStackHost {
        var activeIndex = 0
        override fun attachLayer(layer: Layer, index: Int) { layers.add(index.coerceIn(0, layers.size), layer) }
        override fun detachLayer(layer: Layer) { layers.remove(layer) }
        override fun moveLayerTo(layer: Layer, index: Int) {
            layers.remove(layer)
            layers.add(index.coerceIn(0, layers.size), layer)
        }
        override fun setActiveLayerIndex(index: Int) { activeIndex = index }
        fun find(id: String): Layer? = layers.firstOrNull { it.id == id }
    }

    /** What one 4x4 ARGB_8888 layer costs the budget. */
    private val layerBytes = 4L * 4L * 4L

    /** Simulates deleting [layer] from [host]: detach, then hand it to history. Mirrors CanvasEngine.deleteActiveLayer. */
    private fun delete(undoManager: UndoManager, host: FakeHost, layer: Layer) {
        val index = host.layers.indexOf(layer)
        host.layers.removeAt(index)
        undoManager.pushLayerRemove(layer, index, activeBefore = index, activeAfter = 0)
    }

    @Test fun `evicting an over-budget removed-layer step recycles its bitmap`() {
        val host = FakeHost()
        // Budget holds exactly two removed layers.
        val undoManager = UndoManager(maxDepth = 60, budgetBytes = layerBytes * 2, host = host)
        val a = newLayer(Color.RED); val b = newLayer(Color.GREEN); val c = newLayer(Color.BLUE)
        host.layers.addAll(listOf(a, b, c, newLayer(Color.WHITE)))

        delete(undoManager, host, a)
        delete(undoManager, host, b)
        assertFalse("still within budget", a.bitmap.isRecycled)
        assertEquals(layerBytes * 2, undoManager.undoBytes())

        delete(undoManager, host, c) // third removed layer pushes the OLDEST out

        assertTrue("the evicted step's bitmap must be recycled", a.bitmap.isRecycled)
        assertFalse(b.bitmap.isRecycled)
        assertFalse(c.bitmap.isRecycled)
        assertEquals(layerBytes * 2, undoManager.undoBytes())
    }

    @Test fun `the newest minKeptSteps survive even when a single step exceeds the budget`() {
        val host = FakeHost()
        val undoManager = UndoManager(maxDepth = 60, budgetBytes = 1L, minKeptSteps = 2, host = host)
        val a = newLayer(Color.RED); val b = newLayer(Color.GREEN); val c = newLayer(Color.BLUE)
        host.layers.addAll(listOf(a, b, c, newLayer(Color.WHITE)))

        delete(undoManager, host, a)
        delete(undoManager, host, b)
        delete(undoManager, host, c)

        assertTrue(a.bitmap.isRecycled)
        assertFalse("floor keeps the newest two, budget or not", b.bitmap.isRecycled)
        assertFalse(c.bitmap.isRecycled)
    }

    @Test fun `undoing a removed layer re-attaches the same layer at its index and keeps it alive`() {
        val host = FakeHost()
        val undoManager = UndoManager(host = host)
        val a = newLayer(Color.RED); val b = newLayer(Color.GREEN); val c = newLayer(Color.BLUE)
        host.layers.addAll(listOf(a, b, c))

        delete(undoManager, host, b)
        assertEquals(listOf(a, c), host.layers)

        undoManager.undo(host::find)

        assertEquals("same object, same index", listOf(a, b, c), host.layers)
        assertEquals(Color.GREEN, pixelOf(b))
        assertFalse(b.bitmap.isRecycled)
        assertEquals("active layer index restored from the step", 1, host.activeIndex)
    }

    @Test fun `discarding the redo side of a removed layer never recycles the now-live layer`() {
        val host = FakeHost()
        val undoManager = UndoManager(host = host)
        val a = newLayer(Color.RED); val b = newLayer(Color.GREEN)
        host.layers.addAll(listOf(a, b))
        delete(undoManager, host, b)
        undoManager.undo(host::find) // b is attached again; the step now sits on the redo stack

        stroke(undoManager, a, Color.BLUE) // a fresh edit forks history and discards that redo step

        assertFalse("b is live in the stack -- recycling it would crash the next draw", b.bitmap.isRecycled)
        assertTrue(host.layers.contains(b))
        assertFalse(undoManager.canRedo)
    }

    @Test fun `discarding the redo side of an inserted layer recycles the detached layer`() {
        val host = FakeHost()
        val undoManager = UndoManager(host = host)
        val a = newLayer(Color.RED); val added = newLayer(Color.GREEN)
        host.layers.add(a)
        host.layers.add(added)
        undoManager.pushLayerInsert(added, index = 1, activeBefore = 0, activeAfter = 1)
        undoManager.undo(host::find) // added is detached and owned by the redo step
        assertFalse(added.bitmap.isRecycled)
        assertEquals(listOf(a), host.layers)

        stroke(undoManager, a, Color.BLUE) // redo history dropped

        assertTrue("nobody owns the detached layer any more", added.bitmap.isRecycled)
    }

    @Test fun `clear recycles detached layers but leaves attached ones alone`() {
        val host = FakeHost()
        val undoManager = UndoManager(host = host)
        val a = newLayer(Color.RED); val gone = newLayer(Color.GREEN); val live = newLayer(Color.BLUE)
        host.layers.addAll(listOf(a, gone, live))
        delete(undoManager, host, gone)
        undoManager.pushLayerInsert(live, index = 1, activeBefore = 0, activeAfter = 1)

        undoManager.clear()

        assertTrue(gone.bitmap.isRecycled)
        assertFalse("an inserted layer that is still in the stack belongs to the engine", live.bitmap.isRecycled)
        assertFalse(undoManager.canUndo)
    }

    @Test fun `a whole property gesture is one undo step and undo restores every property`() {
        val host = FakeHost()
        val undoManager = UndoManager(host = host)
        val layer = newLayer(Color.RED)
        host.layers.add(layer)

        // A slider drag: begin is called on every tick, only the first snapshots.
        for (i in 1..20) {
            undoManager.beginLayerPropsEdit(layer)
            layer.opacity = 1f - i * 0.04f
        }
        assertFalse("nothing is committed until the gesture ends", undoManager.canUndo)
        undoManager.endLayerPropsEdit()
        assertTrue(undoManager.canUndo)

        undoManager.undo(host::find)

        assertEquals(1f, layer.opacity, 0f)
        assertFalse("20 ticks were exactly one step", undoManager.canUndo)
        undoManager.redo(host::find)
        assertEquals(0.2f, layer.opacity, 0.001f)
    }

    @Test fun `a property gesture that changes nothing pushes no step`() {
        val undoManager = UndoManager()
        val layer = newLayer(Color.RED)
        undoManager.beginLayerPropsEdit(layer)
        undoManager.endLayerPropsEdit()
        assertFalse(undoManager.canUndo)
    }

    @Test fun `a pending property gesture is closed before the next step so history keeps its order`() {
        val host = FakeHost()
        val undoManager = UndoManager(host = host)
        val layer = newLayer(Color.RED)
        host.layers.add(layer)

        undoManager.beginLayerPropsEdit(layer)
        layer.visible = false
        stroke(undoManager, layer, Color.GREEN) // no explicit end: the stroke commit closes the gesture first

        undoManager.undo(host::find)
        assertEquals("the stroke is undone first...", Color.RED, pixelOf(layer))
        assertFalse("...while the visibility change is still in force", layer.visible)
        undoManager.undo(host::find)
        assertTrue("...and is the step below it", layer.visible)
    }

    @Test fun `maxDepth still caps a run of zero-byte steps`() {
        val host = FakeHost()
        val undoManager = UndoManager(maxDepth = 3, host = host)
        val layer = newLayer(Color.RED)
        host.layers.add(layer)
        repeat(5) {
            undoManager.beginLayerPropsEdit(layer)
            layer.visible = !layer.visible
            undoManager.endLayerPropsEdit()
        }
        repeat(3) { undoManager.undo(host::find) }
        assertFalse(undoManager.canUndo)
    }
}
