package com.vellum.studio.canvas

import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import com.vellum.studio.VellumApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression tests for the "undo integrity quick fixes" (Wave 1): undo/redo running while a stylus
 * stroke is in flight, zero-distance selection taps burning undo steps, and the selection marquee
 * lingering outside the Select tool.
 *
 * Uses [VellumApp] as the Robolectric application because [CanvasEngine]'s constructor sizes the
 * undo depth off [com.vellum.studio.util.DeviceCapabilities], which reads [VellumApp.instance] (only
 * set by [VellumApp.onCreate]) -- same reason ProjectRepositoryTest does. Layers are tiny bitmaps and
 * a "stroke" is an in-place eraseColor, exactly like UndoManagerTest, so a single getPixel tells
 * which snapshot an undo/redo actually restored.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
class UndoIntegrityTest {

    private val size = 64

    private fun newEngine(): CanvasEngine {
        val engine = CanvasEngine(size, size)
        engine.addLayer("base")
        return engine
    }

    /** Simulates one committed stroke on the active layer (begin, mutate in place, commit). */
    private fun commitStroke(engine: CanvasEngine, toColor: Int) {
        val layer = engine.activeLayer()!!
        val pending = engine.undoManager.beginStroke(layer.id, layer.snapshot())
        layer.bitmap.eraseColor(toColor)
        pending.commit(layer.snapshot())
    }

    private fun pixel(engine: CanvasEngine) = engine.activeLayer()!!.bitmap.getPixel(0, 0)

    // ---- undo/redo mid-stroke (persistence:G11) ----

    @Test fun `undo and redo are ignored while a stroke is in flight and history is untouched`() {
        val engine = newEngine()
        commitStroke(engine, Color.RED)
        commitStroke(engine, Color.GREEN)
        assertTrue(engine.undo()) // GREEN -> RED; leaves one undo step and one redo step available
        assertEquals(Color.RED, pixel(engine))
        assertTrue(engine.undoManager.canUndo)
        assertTrue(engine.undoManager.canRedo)

        // A stylus stroke is now live on the active layer (what DrawingCanvasView.startStroke sets).
        engine.strokeInProgressLayerId = engine.activeLayer()!!.id
        engine.activeLayer()!!.bitmap.eraseColor(Color.BLUE) // an in-flight buildUp dab

        assertFalse("undo must be refused mid-stroke", engine.undo())
        assertFalse("redo must be refused mid-stroke", engine.redo())
        assertEquals("the in-flight dabs must not be wiped by a restore", Color.BLUE, pixel(engine))
        assertTrue("undo history must be unchanged", engine.undoManager.canUndo)
        assertTrue("redo history must be unchanged (not cleared, not consumed)", engine.undoManager.canRedo)

        // Once the stroke ends, both work again on the untouched history.
        engine.strokeInProgressLayerId = null
        assertTrue(engine.undo())
        assertEquals(Color.TRANSPARENT, pixel(engine))
        assertTrue(engine.redo())
        assertEquals(Color.RED, pixel(engine))
    }

    @Test fun `undo with nothing to undo reports false and redo likewise`() {
        val engine = newEngine()
        assertFalse(engine.undo())
        assertFalse(engine.redo())
    }

    @Test fun `undo and redo drop the selection marquee`() {
        val engine = newEngine()
        commitStroke(engine, Color.RED)
        engine.currentTool = ToolMode.SELECT
        engine.selectionRect = RectF(2f, 2f, 20f, 20f)
        assertTrue(engine.undo())
        assertNull("the marquee described pixels that just moved back", engine.selectionRect)

        engine.selectionRect = RectF(2f, 2f, 20f, 20f)
        assertTrue(engine.redo())
        assertNull(engine.selectionRect)
    }

    // ---- marquee lifetime (tools:G5) ----

    @Test fun `leaving the Select tool clears the marquee and Deselect clears it explicitly`() {
        val engine = newEngine()
        engine.currentTool = ToolMode.SELECT
        engine.selectionRect = RectF(2f, 2f, 20f, 20f)
        engine.currentTool = ToolMode.SELECT // re-picking Select keeps it
        assertNotNull(engine.selectionRect)

        engine.deselect()
        assertNull(engine.selectionRect)

        engine.selectionRect = RectF(2f, 2f, 20f, 20f)
        engine.currentTool = ToolMode.BRUSH
        assertNull("switching to the brush must not leave a stale marquee behind", engine.selectionRect)
    }

    @Test fun `switching the active layer clears the marquee`() {
        val engine = newEngine()
        engine.addLayer("second")
        engine.currentTool = ToolMode.SELECT
        engine.activeLayerIndex = 0
        engine.selectionRect = RectF(2f, 2f, 20f, 20f)
        engine.activeLayerIndex = 0 // no real change
        assertNotNull(engine.selectionRect)
        engine.activeLayerIndex = 1
        assertNull(engine.selectionRect)
    }

    // ---- selection tap vs. move, driven through the real view's touch path ----

    private fun stylusEvent(action: Int, x: Float, y: Float, downTime: Long): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun newSelectView(engine: CanvasEngine): DrawingCanvasView {
        val view = DrawingCanvasView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, size, size)
        view.attachEngine(engine)
        view.resetView()
        engine.currentTool = ToolMode.SELECT
        return view
    }

    /** One stylus gesture starting at the current selection rect's center (mapped through the
     * view's own fit-and-center matrix into screen space) and dragging by ([dxScreen], [dyScreen]). */
    private fun gesture(view: DrawingCanvasView, engine: CanvasEngine, dxScreen: Float, dyScreen: Float) {
        val rect = engine.selectionRect!!
        val pts = floatArrayOf(rect.centerX(), rect.centerY())
        view.currentMatrixSnapshot().mapPoints(pts)
        val t = SystemClock.uptimeMillis()
        view.onTouchEvent(stylusEvent(MotionEvent.ACTION_DOWN, pts[0], pts[1], t))
        if (dxScreen != 0f || dyScreen != 0f) {
            view.onTouchEvent(stylusEvent(MotionEvent.ACTION_MOVE, pts[0] + dxScreen, pts[1] + dyScreen, t))
        }
        view.onTouchEvent(stylusEvent(MotionEvent.ACTION_UP, pts[0] + dxScreen, pts[1] + dyScreen, t))
    }

    @Test fun `a zero-delta tap inside the selection pushes no undo step and keeps the rect`() {
        val engine = newEngine()
        val view = newSelectView(engine)
        val original = RectF(10f, 10f, 30f, 30f)
        engine.selectionRect = RectF(original)
        assertFalse(engine.undoManager.canUndo)

        repeat(10) { gesture(view, engine, 0f, 0f) }

        assertFalse("stray taps must not create undo steps", engine.undoManager.canUndo)
        assertEquals(original, engine.selectionRect)
    }

    @Test fun `a real drag of the selection still commits exactly one undo step`() {
        val engine = newEngine()
        val view = newSelectView(engine)
        engine.activeLayer()!!.bitmap.eraseColor(Color.RED)
        engine.selectionRect = RectF(10f, 10f, 30f, 30f)

        gesture(view, engine, 8f, 8f)

        assertTrue("a genuine move must still be undoable", engine.undoManager.canUndo)
        assertTrue(engine.undo())
        assertFalse("and it was a single step", engine.undoManager.canUndo)
    }
}
