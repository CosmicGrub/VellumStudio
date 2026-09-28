package com.vellum.studio.canvas

import android.graphics.Color
import com.vellum.studio.VellumApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for structural layer undo and safe layer delete (Wave 1): layer add / delete /
 * reorder and opacity / visibility / blend / lock are steps in the same linear undo history as
 * strokes, and deleting a layer no longer recycles its bitmap out from under that history.
 *
 * Before this, delete recycled the bitmap on the spot and the next Ctrl+Z silently dropped the dead
 * layer's stroke entries and rewound a stroke on a DIFFERENT layer; every property change was
 * invisible to undo. Same conventions as [UndoIntegrityTest]: [VellumApp] as the Robolectric
 * application (CanvasEngine sizes its undo budget off DeviceCapabilities), tiny layers, and a
 * "stroke" is an in-place eraseColor so one getPixel says which snapshot a step restored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
class StructuralUndoTest {

    private val size = 32

    /** Three layers, colors RED / GREEN / BLUE bottom to top, active = top. */
    private fun newEngine(): CanvasEngine {
        val engine = CanvasEngine(size, size)
        engine.addLayer("bottom")
        engine.addLayer("middle")
        engine.addLayer("top")
        // Layer setup above is not history (first layer) or is (the other two); start clean so each
        // test only sees the steps it makes itself.
        engine.undoManager.clear()
        engine.layers[0].bitmap.eraseColor(Color.RED)
        engine.layers[1].bitmap.eraseColor(Color.GREEN)
        engine.layers[2].bitmap.eraseColor(Color.BLUE)
        assertEquals(2, engine.activeLayerIndex)
        return engine
    }

    private fun colors(engine: CanvasEngine) = engine.layers.map { it.bitmap.getPixel(0, 0) }

    private fun commitStroke(engine: CanvasEngine, toColor: Int) {
        val layer = engine.activeLayer()!!
        val pending = engine.undoManager.beginStroke(layer.id, layer.snapshot())
        layer.bitmap.eraseColor(toColor)
        pending.commit(layer.snapshot())
    }

    // ---- delete / undo ----

    @Test fun `delete then undo restores the layer at the same index with its pixels and the same active layer`() {
        val engine = newEngine()
        engine.activeLayerIndex = 1
        val middle = engine.layers[1]

        assertTrue(engine.deleteActiveLayer())
        assertEquals(2, engine.layers.size)
        assertFalse("delete must not recycle: history still owns these pixels", middle.bitmap.isRecycled)

        assertTrue(engine.undo())

        assertEquals(3, engine.layers.size)
        assertSame("the very same Layer object comes back", middle, engine.layers[1])
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), colors(engine))
        assertEquals("same active layer as before the delete", 1, engine.activeLayerIndex)
        assertSame(middle, engine.activeLayer())
    }

    @Test fun `redo after undoing a delete removes the layer again and undo works a second time`() {
        val engine = newEngine()
        engine.activeLayerIndex = 1
        val middle = engine.layers[1]
        engine.deleteActiveLayer()
        engine.undo()

        assertTrue(engine.redo())
        assertEquals(listOf(Color.RED, Color.BLUE), colors(engine))
        assertFalse(middle.bitmap.isRecycled)

        assertTrue(engine.undo())
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), colors(engine))
    }

    @Test fun `undo after a delete restores the layer instead of rewinding a stroke on another layer`() {
        val engine = newEngine()
        engine.activeLayerIndex = 0
        commitStroke(engine, Color.YELLOW) // a stroke on the bottom layer, made BEFORE the delete
        engine.activeLayerIndex = 1
        engine.deleteActiveLayer()

        assertTrue(engine.undo())

        // The old behavior: the delete had no step, so this Ctrl+Z reverted the yellow stroke on the
        // bottom layer while the deleted middle layer stayed gone.
        assertEquals(3, engine.layers.size)
        assertEquals(listOf(Color.YELLOW, Color.GREEN, Color.BLUE), colors(engine))

        assertTrue(engine.undo()) // the next Ctrl+Z is the stroke, as expected
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), colors(engine))
    }

    @Test fun `deleting the top layer restores active layer on undo`() {
        val engine = newEngine()
        engine.deleteActiveLayer() // active was 2 (top); afterwards active clamps to 1
        assertEquals(1, engine.activeLayerIndex)

        engine.undo()

        assertEquals(2, engine.activeLayerIndex)
        assertEquals(Color.BLUE, engine.activeLayer()!!.bitmap.getPixel(0, 0))
    }

    @Test fun `delete is refused on the last layer and records nothing`() {
        val engine = CanvasEngine(size, size)
        engine.addLayer("only")
        assertFalse(engine.deleteActiveLayer())
        assertEquals(1, engine.layers.size)
        assertFalse(engine.undoManager.canUndo)
    }

    @Test fun `delete is refused for the layer a stroke is drawing into and the bitmap is untouched`() {
        val engine = newEngine()
        val top = engine.activeLayer()!!
        engine.strokeInProgressLayerId = top.id

        assertFalse(engine.deleteActiveLayer())

        assertEquals(3, engine.layers.size)
        assertFalse(top.bitmap.isRecycled)
        assertFalse(engine.undoManager.canUndo)
    }

    @Test fun `a new edit after undoing a delete keeps the restored layer alive`() {
        val engine = newEngine()
        engine.activeLayerIndex = 1
        val middle = engine.layers[1]
        engine.deleteActiveLayer()
        engine.undo() // the delete step is now on the redo stack and middle is live again

        commitStroke(engine, Color.MAGENTA) // forks history, discarding that redo step

        assertFalse("discarding the redo entry must not recycle a live layer", middle.bitmap.isRecycled)
        assertEquals(Color.MAGENTA, middle.bitmap.getPixel(0, 0))
    }

    @Test fun `recycleAll releases a layer held only by a delete step`() {
        val engine = newEngine()
        engine.activeLayerIndex = 1
        val middle = engine.layers[1]
        engine.deleteActiveLayer()

        engine.recycleAll()

        assertTrue("a deleted layer's bitmap must not leak past engine teardown", middle.bitmap.isRecycled)
    }

    // ---- structural undo is refused mid-stroke ----

    @Test fun `structural undo and redo are refused while a stroke is active`() {
        val engine = newEngine()
        engine.activeLayerIndex = 1
        engine.deleteActiveLayer() // history: [remove(middle)]; active now 1 (was the old top)
        val topLayer = engine.layers[1]
        engine.strokeInProgressLayerId = topLayer.id

        assertFalse("must not re-insert a layer under a live stroke", engine.undo())
        assertEquals(2, engine.layers.size)
        assertTrue("history untouched", engine.undoManager.canUndo)

        engine.strokeInProgressLayerId = null
        assertTrue(engine.undo())
        assertEquals(3, engine.layers.size)

        engine.strokeInProgressLayerId = engine.layers[0].id
        assertFalse(engine.redo())
        assertEquals(3, engine.layers.size)
        assertTrue(engine.undoManager.canRedo)
    }

    // ---- move ----

    @Test fun `layer move is undoable and redoable and the active layer follows`() {
        val engine = newEngine()
        engine.activeLayerIndex = 0
        engine.moveActiveLayer(1) // bottom (RED) goes up one
        assertEquals(listOf(Color.GREEN, Color.RED, Color.BLUE), colors(engine))
        assertEquals(1, engine.activeLayerIndex)

        assertTrue(engine.undo())
        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), colors(engine))
        assertEquals(0, engine.activeLayerIndex)

        assertTrue(engine.redo())
        assertEquals(listOf(Color.GREEN, Color.RED, Color.BLUE), colors(engine))
        assertEquals(1, engine.activeLayerIndex)
    }

    @Test fun `a move that does not change the order records nothing`() {
        val engine = newEngine()
        engine.activeLayerIndex = 2
        engine.moveActiveLayer(1) // already on top
        assertFalse(engine.undoManager.canUndo)
    }

    // ---- add / duplicate ----

    @Test fun `the first layer of a project is not an undo step but later adds are`() {
        val engine = CanvasEngine(size, size)
        engine.addLayer("first")
        assertFalse("undoing the only layer away would leave an empty canvas", engine.undoManager.canUndo)

        val second = engine.addLayer("second")
        assertTrue(engine.undoManager.canUndo)
        assertEquals(1, engine.activeLayerIndex)

        assertTrue(engine.undo())
        assertEquals(1, engine.layers.size)
        assertEquals(0, engine.activeLayerIndex)
        assertFalse("detached layer is held by the redo step, not recycled", second.bitmap.isRecycled)

        assertTrue(engine.redo())
        assertSame(second, engine.layers[1])
        assertEquals(1, engine.activeLayerIndex)
    }

    @Test fun `duplicate layer is undoable and undo removes the copy`() {
        val engine = newEngine()
        engine.activeLayerIndex = 0
        engine.duplicateActiveLayer()
        assertEquals(4, engine.layers.size)
        assertEquals(1, engine.activeLayerIndex)

        assertTrue(engine.undo())

        assertEquals(listOf(Color.RED, Color.GREEN, Color.BLUE), colors(engine))
        assertEquals(0, engine.activeLayerIndex)
    }

    // ---- properties ----

    @Test fun `visibility blend mode and lock are each one undoable step`() {
        val engine = newEngine()
        val layer = engine.layers[1]

        engine.setLayerVisible(layer, false)
        engine.setLayerBlendMode(layer, LayerBlendMode.MULTIPLY)
        engine.setLayerLocked(layer, true)

        assertTrue(engine.undo())
        assertFalse("lock undone", layer.locked)
        assertEquals(LayerBlendMode.MULTIPLY, layer.blendMode)
        assertFalse(layer.visible)

        assertTrue(engine.undo())
        assertEquals(LayerBlendMode.NORMAL, layer.blendMode)
        assertFalse(layer.visible)

        assertTrue(engine.undo())
        assertTrue(layer.visible)
        assertFalse(engine.undoManager.canUndo)

        assertTrue(engine.redo())
        assertFalse(layer.visible)
    }

    @Test fun `setting a property to its current value records nothing`() {
        val engine = newEngine()
        val layer = engine.layers[1]
        engine.setLayerVisible(layer, true)
        engine.setLayerLocked(layer, false)
        engine.setLayerBlendMode(layer, LayerBlendMode.NORMAL)
        assertFalse(engine.undoManager.canUndo)
    }

    @Test fun `an opacity slider drag is exactly one undo step`() {
        val engine = newEngine()
        val layer = engine.layers[1]

        // 30 onValueChange ticks, then onValueChangeFinished.
        for (i in 1..30) engine.setLayerOpacity(layer, 1f - i / 40f)
        engine.commitLayerPropsEdit()
        assertEquals(0.25f, layer.opacity, 0.001f)

        assertTrue(engine.undo())
        assertEquals("one Ctrl+Z reverts the whole drag", 1f, layer.opacity, 0f)
        assertFalse("and there was only one step", engine.undoManager.canUndo)

        assertTrue(engine.redo())
        assertEquals(0.25f, layer.opacity, 0.001f)
    }

    @Test fun `undo closes an opacity drag whose finish callback never arrived`() {
        val engine = newEngine()
        val layer = engine.layers[1]
        engine.setLayerOpacity(layer, 0.5f) // panel disposed mid-drag: no commit

        assertTrue(engine.undo())

        assertEquals(1f, layer.opacity, 0f)
    }

    @Test fun `a property change lands in history between the strokes around it`() {
        val engine = newEngine()
        engine.activeLayerIndex = 1
        commitStroke(engine, Color.YELLOW)
        engine.setLayerVisible(engine.activeLayer()!!, false)
        commitStroke(engine, Color.CYAN)

        engine.undo() // the CYAN stroke
        assertEquals(Color.YELLOW, engine.layers[1].bitmap.getPixel(0, 0))
        assertFalse(engine.layers[1].visible)
        engine.undo() // the visibility change
        assertTrue(engine.layers[1].visible)
        engine.undo() // the YELLOW stroke
        assertEquals(Color.GREEN, engine.layers[1].bitmap.getPixel(0, 0))
    }

    @Test fun `undoing a delete brings the marquee down and bumps revision for autosave`() {
        val engine = newEngine()
        engine.activeLayerIndex = 1
        engine.deleteActiveLayer()
        engine.currentTool = ToolMode.SELECT
        engine.selectionRect = android.graphics.RectF(1f, 1f, 5f, 5f)
        val rev = engine.revision

        engine.undo()

        assertNull(engine.selectionRect)
        assertTrue(engine.revision > rev)
    }
}
