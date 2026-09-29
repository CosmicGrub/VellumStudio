package com.vellum.studio.canvas

import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import com.vellum.studio.VellumApp
import com.vellum.studio.testing.PointerEvents
import com.vellum.studio.testing.PointerEvents.P
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The safety net for the hardened input contract that lives in [DrawingCanvasView.onTouchEvent] and
 * its handleFinger* / stroke methods -- until now protected by comments alone:
 *
 *  - only a stylus / hardware eraser draws; a finger only pans / pinch-zooms / rotates;
 *  - while a stylus stroke is live, finger input has ZERO effect (palm rejection), yet a finger that
 *    landed mid-stroke is still tracked, so it takes over cleanly once the pen lifts (no view jump);
 *  - ACTION_CANCEL rolls the partial stroke back and leaves no history step behind;
 *  - locking the layer mid-stroke cancels the stroke;
 *  - TOOL_TYPE_ERASER routes to the eraser (a deliberately selected eraser variant wins over the
 *    FlatEraser default); a mouse or unknown pointer does nothing at all;
 *  - batched historical samples are consumed in order.
 *
 * These drive the REAL view with REAL [MotionEvent]s ([PointerEvents]) straight into onTouchEvent /
 * onHoverEvent and assert on the layer bitmap, the scratch bitmap, the view matrix and the engine's
 * bookkeeping -- the same observable state a regression would corrupt. `@GraphicsMode(NATIVE)` is
 * what makes the pixel assertions mean anything (the legacy Canvas shadow rasterizes nothing, so
 * "the layer did not change" would pass against a broken app); [VellumApp] is the application for
 * the same reason UndoIntegrityTest uses it (CanvasEngine's constructor reads DeviceCapabilities,
 * and the view's default pressure-gamma provider reads the settings repository).
 *
 * Determinism: every brush a pixel assertion touches is jitter-free (InkPen, FeltMarker, FlatEraser,
 * HardEraser have jitter = opacityJitter = 0), so identical event streams give bit-identical pixels
 * and "same stream, with a palm added" can be compared exactly. The default Pencil (jitter 0.35,
 * unseeded kotlin.random in the frozen dab loop) is deliberately never pixel-compared; the frozen
 * loop is not touched to seed it.
 *
 * What this cannot prove (a JVM has no digitizer): real stylus hover distance reporting, the device
 * report rate / real batching, and latency. The hover cases below pin only that a synthesized hover
 * stream cannot mutate stroke / selection / navigation state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingCanvasViewInputRoutingTest {

    private val size = 160

    /**
     * One engine + one attached, laid-out view, with a constant gamma of 1 injected unless a test
     * passes its own (null keeps the view's default, Settings-backed provider). Listeners record what
     * the view reports so a test can assert "never committed" / "active then inactive".
     */
    private inner class Rig(
        brush: Brush,
        prefillLeftHalf: Boolean = false,
        gamma: (() -> Float)? = { 1f },
        tool: ToolMode = ToolMode.BRUSH,
    ) {
        val engine = CanvasEngine(size, size).also { it.addLayer("base") }
        val layer: Layer = engine.activeLayer()!!
        val view = DrawingCanvasView(RuntimeEnvironment.getApplication())
        var commits = 0
        val activeChanges = mutableListOf<Boolean>()

        init {
            if (prefillLeftHalf) {
                // A solid, non-trivial background: the strokes under test cross it, so "restored" or
                // "erased" means something and a rollback to a blank layer would be caught.
                val c = android.graphics.Canvas(layer.bitmap)
                c.drawRect(0f, 0f, size / 2f, size.toFloat(), android.graphics.Paint().apply { color = 0xFFCC3311.toInt() })
            }
            engine.currentBrush = brush
            engine.currentTool = tool
            view.layout(0, 0, size, size)
            view.attachEngine(engine)
            view.resetView()
            if (gamma != null) view.pressureGammaProvider = gamma
            view.onStrokeCommitted = { commits++ }
            view.onStrokeActiveChanged = { activeChanges += it }
        }

        /** Canvas-space point -> view-space point through the view's own current matrix. */
        fun toView(cx: Float, cy: Float): FloatArray =
            floatArrayOf(cx, cy).also { view.currentMatrixSnapshot().mapPoints(it) }

        fun send(ev: MotionEvent) {
            view.onTouchEvent(ev)
            ev.recycle()
        }

        fun hover(ev: MotionEvent) {
            view.onHoverEvent(ev)
            ev.recycle()
        }

        fun layerPixels(): IntArray = pixelsOf(layer.bitmap)
        fun scratchPixels(): IntArray = pixelsOf(engine.strokeScratch)
        fun matrixValues(): FloatArray = valuesOf(view.currentMatrixSnapshot())
    }

    private fun pixelsOf(bmp: android.graphics.Bitmap): IntArray =
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }

    private fun valuesOf(m: Matrix): FloatArray = FloatArray(9).also { m.getValues(it) }

    private fun assertSamePixels(message: String, expected: IntArray, actual: IntArray) {
        assertEquals("$message (sizes)", expected.size, actual.size)
        val differing = expected.indices.count { expected[it] != actual[it] }
        assertEquals("$message: $differing pixel(s) differ", 0, differing)
    }

    private fun assertSameMatrix(message: String, expected: FloatArray, actual: FloatArray) {
        for (i in expected.indices) assertEquals("$message (matrix[$i])", expected[i], actual[i], 1e-4f)
    }

    private fun alphaSum(px: IntArray): Long = px.sumOf { (it ushr 24).toLong() }

    private fun alphaAt(rig: Rig, x: Int, y: Int) = Color.alpha(rig.layer.bitmap.getPixel(x, y))

    private fun scaleOf(v: FloatArray) = hypot(v[Matrix.MSCALE_X], v[Matrix.MSKEW_Y])
    private fun rotationDegOf(v: FloatArray) = Math.toDegrees(atan2(v[Matrix.MSKEW_Y].toDouble(), v[Matrix.MSCALE_X].toDouble()))

    /** Canvas point under a view-space point, via the inverse of the view's current matrix. */
    private fun canvasUnder(rig: Rig, vx: Float, vy: Float): FloatArray {
        val inv = Matrix()
        assertTrue(rig.view.currentMatrixSnapshot().invert(inv))
        return floatArrayOf(vx, vy).also { inv.mapPoints(it) }
    }

    // A dog-leg path in canvas space, long enough for many dabs and bent so that dropping a middle
    // sample changes the pixels.
    private val pathA = floatArrayOf(30f, 40f)
    private val pathB = floatArrayOf(90f, 40f)
    private val pathC = floatArrayOf(90f, 100f)
    private val pathD = floatArrayOf(130f, 120f)

    private fun stylusAt(rig: Rig, pt: FloatArray, id: Int = 0, pressure: Float = 1f): P =
        rig.toView(pt[0], pt[1]).let { PointerEvents.stylus(id, it[0], it[1], pressure) }

    /** A complete one-pointer stylus stroke A -> B -> C -> D, one MOVE per vertex. */
    private fun drawStylusStroke(rig: Rig, pressure: Float = 1f, tool: (P) -> P = { it }) {
        val a = tool(stylusAt(rig, pathA, pressure = pressure))
        val b = tool(stylusAt(rig, pathB, pressure = pressure))
        val c = tool(stylusAt(rig, pathC, pressure = pressure))
        val d = tool(stylusAt(rig, pathD, pressure = pressure))
        rig.send(PointerEvents.down(a))
        rig.send(PointerEvents.move(b))
        rig.send(PointerEvents.move(c))
        rig.send(PointerEvents.move(d))
        rig.send(PointerEvents.up(d))
    }

    private fun asEraser(p: P) = p.copy(toolType = MotionEvent.TOOL_TYPE_ERASER)

    // ================================================================ finger never draws

    @Test fun `a finger drag never changes pixels or history in any tool and does pan the view`() {
        for (tool in ToolMode.entries) {
            val rig = Rig(BrushPresets.InkPen, prefillLeftHalf = true, tool = tool)
            val pixelsBefore = rig.layerPixels()
            val scratchBefore = rig.scratchPixels()
            val matrixBefore = rig.matrixValues()

            // One finger: DOWN, a MOVE that only sets the baseline, then a real drag.
            val p0 = rig.toView(40f, 40f)
            rig.send(PointerEvents.down(PointerEvents.finger(0, p0[0], p0[1])))
            rig.send(PointerEvents.move(PointerEvents.finger(0, p0[0], p0[1])))
            rig.send(PointerEvents.move(PointerEvents.finger(0, p0[0] + 30f, p0[1] + 20f)))
            rig.send(PointerEvents.move(PointerEvents.finger(0, p0[0] + 60f, p0[1] + 45f)))
            rig.send(PointerEvents.up(PointerEvents.finger(0, p0[0] + 60f, p0[1] + 45f)))

            assertSamePixels("finger drag under $tool changed the layer", pixelsBefore, rig.layerPixels())
            assertSamePixels("finger drag under $tool changed the scratch", scratchBefore, rig.scratchPixels())
            assertFalse("finger must not start a stroke ($tool)", rig.engine.undoManager.canUndo)
            assertNull("finger must not define a selection ($tool)", rig.engine.selectionRect)
            assertNull(rig.engine.strokeInProgressLayerId)
            assertEquals("finger must not commit ($tool)", 0, rig.commits)
            assertTrue("finger must never raise the stroke-active signal ($tool)", rig.activeChanges.isEmpty())

            val matrixAfter = rig.matrixValues()
            assertEquals("pan x under $tool", matrixBefore[Matrix.MTRANS_X] + 60f, matrixAfter[Matrix.MTRANS_X], 1e-3f)
            assertEquals("pan y under $tool", matrixBefore[Matrix.MTRANS_Y] + 45f, matrixAfter[Matrix.MTRANS_Y], 1e-3f)
            assertEquals("a one-finger pan must not scale", scaleOf(matrixBefore), scaleOf(matrixAfter), 1e-5f)
        }
    }

    // ================================================================ stylus draws (and owns the design of scratch vs direct)

    @Test fun `a stylus stroke marks pixels commits one undo step and clears the in-progress marker`() {
        val rig = Rig(BrushPresets.InkPen)
        val blank = rig.layerPixels()
        val a = stylusAt(rig, pathA)
        val b = stylusAt(rig, pathB)

        rig.send(PointerEvents.down(a))
        assertEquals("the layer is marked in-progress from the first touch", rig.layer.id, rig.engine.strokeInProgressLayerId)
        rig.send(PointerEvents.move(b))
        // InkPen is a scratch-routed brush: the ink lives in the scratch bitmap until pen-up...
        assertSamePixels("a scratch-routed stroke must not touch the layer before pen-up", blank, rig.layerPixels())
        assertTrue("ink is in the scratch mid-stroke", alphaSum(rig.scratchPixels()) > 0)

        rig.send(PointerEvents.up(b))
        // ...and is baked into the layer exactly once, at pen-up.
        assertEquals("the middle of the line is solid ink", 255, alphaAt(rig, 60, 40))
        assertEquals("far from the line stays empty", 0, alphaAt(rig, 10, 150))
        assertNull(rig.engine.strokeInProgressLayerId)
        assertEquals(1, rig.commits)
        assertEquals(listOf(true, false), rig.activeChanges)
        assertTrue(rig.engine.undoManager.canUndo)
        assertTrue(rig.engine.undo())
        assertSamePixels("undo restores the blank layer", blank, rig.layerPixels())
        assertFalse("and it was exactly one step", rig.engine.undoManager.canUndo)
    }

    @Test fun `a build-up brush lays ink straight onto the layer while the stylus is still down`() {
        val rig = Rig(BrushPresets.FeltMarker)
        rig.send(PointerEvents.down(stylusAt(rig, pathA)))
        rig.send(PointerEvents.move(stylusAt(rig, pathB)))
        assertTrue("buildUp dabs land on the layer live", alphaSum(rig.layerPixels()) > 0)
        rig.send(PointerEvents.up(stylusAt(rig, pathB)))
        assertEquals(1, rig.commits)
    }

    // ================================================================ palm rejection

    /**
     * The same pen stroke, with and without a palm/finger that lands mid-stroke and drags far, MUST
     * produce bit-identical pixels and leave the matrix untouched -- for a scratch-routed brush and
     * a direct (buildUp) brush, the two paths a regression could break independently.
     */
    @Test fun `a finger landing and dragging during a stylus stroke changes neither pixels nor the matrix`() {
        for (brush in listOf(BrushPresets.InkPen, BrushPresets.FeltMarker)) {
            val control = Rig(brush, prefillLeftHalf = true)
            drawStylusStroke(control)
            val controlPixels = control.layerPixels()

            val rig = Rig(brush, prefillLeftHalf = true)
            val matrixBefore = rig.matrixValues()
            val a = stylusAt(rig, pathA)
            val b = stylusAt(rig, pathB)
            val c = stylusAt(rig, pathC)
            val d = stylusAt(rig, pathD)
            rig.send(PointerEvents.down(a))
            rig.send(PointerEvents.move(b))

            // The palm lands (POINTER_DOWN, index 1) while the pen is down...
            val palm0 = PointerEvents.finger(1, 20f, 140f)
            rig.send(PointerEvents.pointerDown(1, b, palm0))
            val layerMid = rig.layerPixels()
            val scratchMid = rig.scratchPixels()

            // ...then drags across the canvas in an event where the pen itself has not moved. Nothing
            // at all may change: this is the "finger MOVE during a stylus stroke" invariant.
            rig.send(PointerEvents.move(b, palm0.at(100f, 20f)))
            rig.send(PointerEvents.move(b, palm0.at(140f, 90f)))
            assertSamePixels("finger MOVE mid-stroke changed the layer (${brush.id})", layerMid, rig.layerPixels())
            assertSamePixels("finger MOVE mid-stroke changed the scratch (${brush.id})", scratchMid, rig.scratchPixels())
            assertSameMatrix("finger MOVE mid-stroke moved the view (${brush.id})", matrixBefore, rig.matrixValues())
            assertEquals("the pen still owns the stroke", rig.layer.id, rig.engine.strokeInProgressLayerId)

            // The pen carries on while the palm keeps dragging.
            rig.send(PointerEvents.move(c, palm0.at(60f, 150f)))
            rig.send(PointerEvents.move(d, palm0.at(10f, 10f)))
            rig.send(PointerEvents.pointerUp(1, d, palm0.at(10f, 10f))) // the palm lifts; the stroke must NOT end
            assertEquals("a finger lifting must not end the pen's stroke", rig.layer.id, rig.engine.strokeInProgressLayerId)
            rig.send(PointerEvents.up(d))

            assertSamePixels("the palm changed the final stroke (${brush.id})", controlPixels, rig.layerPixels())
            assertSameMatrix("the palm moved the view (${brush.id})", matrixBefore, rig.matrixValues())
            assertEquals(1, rig.commits)
        }
    }

    @Test fun `a finger already resting when the pen lands is also ignored for the whole stroke`() {
        val control = Rig(BrushPresets.FeltMarker)
        drawStylusStroke(control)

        val rig = Rig(BrushPresets.FeltMarker)
        val matrixBefore = rig.matrixValues()
        val resting = PointerEvents.finger(0, 30f, 130f)
        rig.send(PointerEvents.down(resting))
        // The pen lands as a second pointer (index 1); the finger is pointer 0 for the whole gesture.
        val a = stylusAt(rig, pathA, id = 1)
        val b = stylusAt(rig, pathB, id = 1)
        val c = stylusAt(rig, pathC, id = 1)
        val d = stylusAt(rig, pathD, id = 1)
        rig.send(PointerEvents.pointerDown(1, resting, a))
        rig.send(PointerEvents.move(resting.at(35f, 120f), b))
        rig.send(PointerEvents.move(resting.at(80f, 60f), c))
        rig.send(PointerEvents.move(resting.at(120f, 30f), d))
        rig.send(PointerEvents.pointerUp(1, resting.at(120f, 30f), d))

        assertSamePixels("a resting palm changed the stroke", control.layerPixels(), rig.layerPixels())
        assertSameMatrix("a resting palm moved the view", matrixBefore, rig.matrixValues())
    }

    @Test fun `a second stylus pointer during a stroke is ignored and one pen owns the stroke`() {
        val control = Rig(BrushPresets.FeltMarker)
        drawStylusStroke(control)

        val rig = Rig(BrushPresets.FeltMarker)
        val a = stylusAt(rig, pathA)
        val b = stylusAt(rig, pathB)
        val c = stylusAt(rig, pathC)
        val d = stylusAt(rig, pathD)
        val second = PointerEvents.stylus(1, 20f, 150f)
        rig.send(PointerEvents.down(a))
        rig.send(PointerEvents.move(b))
        rig.send(PointerEvents.pointerDown(1, b, second))
        rig.send(PointerEvents.move(c, second.at(150f, 10f)))
        rig.send(PointerEvents.move(d, second.at(10f, 10f)))
        rig.send(PointerEvents.pointerUp(1, d, second.at(10f, 10f)))
        assertEquals("the second pen lifting must not end the first pen's stroke", rig.layer.id, rig.engine.strokeInProgressLayerId)
        rig.send(PointerEvents.up(d))

        assertSamePixels("a second stylus pointer changed the stroke", control.layerPixels(), rig.layerPixels())
        assertEquals(1, rig.commits)
    }

    @Test fun `a finger that landed mid-stroke takes over cleanly after the pen lifts without jumping the view`() {
        val rig = Rig(BrushPresets.InkPen)
        val matrixBefore = rig.matrixValues()
        val a = stylusAt(rig, pathA)
        val b = stylusAt(rig, pathB)
        val finger0 = PointerEvents.finger(1, 50f, 50f)

        rig.send(PointerEvents.down(a))
        rig.send(PointerEvents.pointerDown(1, a, finger0)) // finger lands DURING the stroke
        rig.send(PointerEvents.move(b, finger0.at(70f, 60f)))
        rig.send(PointerEvents.pointerUp(0, b, finger0.at(70f, 60f))) // the pen lifts first; the finger stays down
        assertNull("the pen's lift ends the stroke", rig.engine.strokeInProgressLayerId)
        assertEquals(1, rig.commits)
        assertSameMatrix("nothing panned yet", matrixBefore, rig.matrixValues())

        // Android never re-sends DOWN for that finger -- it is only ever tracked because the view
        // registered it while the stroke was live. Its FIRST move after the pen lifts must only set
        // the navigation baseline (no jump from the finger's old position), the next one pans.
        val f = finger0.at(70f, 60f)
        rig.send(PointerEvents.move(f))
        assertSameMatrix("first finger move after pen-up must not jump the view", matrixBefore, rig.matrixValues())
        rig.send(PointerEvents.move(f.at(90f, 75f)))
        val after = rig.matrixValues()
        assertEquals(matrixBefore[Matrix.MTRANS_X] + 20f, after[Matrix.MTRANS_X], 1e-3f)
        assertEquals(matrixBefore[Matrix.MTRANS_Y] + 15f, after[Matrix.MTRANS_Y], 1e-3f)
    }

    // ================================================================ two-finger navigation still works

    private fun twoFingerRig(): Triple<Rig, P, P> {
        val rig = Rig(BrushPresets.InkPen, prefillLeftHalf = true, tool = ToolMode.BRUSH)
        val f0 = PointerEvents.finger(0, 60f, 80f)
        val f1 = PointerEvents.finger(1, 100f, 80f)
        rig.send(PointerEvents.down(f0))
        rig.send(PointerEvents.pointerDown(1, f0, f1))
        rig.send(PointerEvents.move(f0, f1)) // baseline only
        return Triple(rig, f0, f1)
    }

    @Test fun `two fingers moving together pan without scaling or rotating and never touch pixels`() {
        val (rig, f0, f1) = twoFingerRig()
        val pixelsBefore = rig.layerPixels()
        val before = rig.matrixValues()

        rig.send(PointerEvents.move(f0.at(70f, 95f), f1.at(110f, 95f)))

        val after = rig.matrixValues()
        assertEquals(before[Matrix.MTRANS_X] + 10f, after[Matrix.MTRANS_X], 1e-3f)
        assertEquals(before[Matrix.MTRANS_Y] + 15f, after[Matrix.MTRANS_Y], 1e-3f)
        assertEquals(scaleOf(before), scaleOf(after), 1e-5f)
        assertEquals(rotationDegOf(before), rotationDegOf(after), 1e-3)
        assertSamePixels("a two-finger pan changed pixels", pixelsBefore, rig.layerPixels())
    }

    @Test fun `spreading two fingers zooms about their midpoint`() {
        val (rig, f0, f1) = twoFingerRig()
        val before = rig.matrixValues()
        val underMid = canvasUnder(rig, 80f, 80f)

        rig.send(PointerEvents.move(f0.at(50f, 80f), f1.at(110f, 80f))) // 40px apart -> 60px apart

        val after = rig.matrixValues()
        assertEquals("pinch ratio", scaleOf(before) * 1.5f, scaleOf(after), 1e-3f)
        val stillUnderMid = canvasUnder(rig, 80f, 80f)
        assertEquals(underMid[0], stillUnderMid[0], 1e-2f)
        assertEquals("the canvas point under the fingers' midpoint stays put", underMid[1], stillUnderMid[1], 1e-2f)
    }

    @Test fun `rotating two fingers rotates the view about their midpoint`() {
        val (rig, _, _) = twoFingerRig()
        val before = rig.matrixValues()
        val underMid = canvasUnder(rig, 80f, 80f)

        // The pair (centered on (80,80), 40px apart) sweeps 45 degrees in 15 degree steps -- each step
        // is a small delta, so the angle never straddles the +-pi wrap.
        for (deg in listOf(15.0, 30.0, 45.0)) {
            val r = Math.toRadians(deg)
            val dx = (20 * cos(r)).toFloat()
            val dy = (20 * sin(r)).toFloat()
            rig.send(
                PointerEvents.move(
                    PointerEvents.finger(0, 80f - dx, 80f - dy),
                    PointerEvents.finger(1, 80f + dx, 80f + dy),
                ),
            )
        }

        val after = rig.matrixValues()
        assertEquals("rotated by the fingers' sweep", rotationDegOf(before) + 45.0, rotationDegOf(after), 0.05)
        assertEquals("rotation alone does not scale", scaleOf(before), scaleOf(after), 1e-3f)
        val stillUnderMid = canvasUnder(rig, 80f, 80f)
        assertEquals(underMid[0], stillUnderMid[0], 1e-2f)
        assertEquals(underMid[1], stillUnderMid[1], 1e-2f)
    }

    // ================================================================ cancel

    @Test fun `ACTION_CANCEL restores the pre-stroke pixels clears the marker and pushes no history`() {
        // FeltMarker paints straight onto the layer, so the layer really is dirty at cancel time and
        // the rollback has work to do; InkPen keeps its ink in the scratch, so the layer must simply
        // stay as it was and the stale scratch must not leak into the next stroke.
        for (brush in listOf(BrushPresets.FeltMarker, BrushPresets.InkPen)) {
            val rig = Rig(brush, prefillLeftHalf = true)
            val before = rig.layerPixels()
            val a = stylusAt(rig, pathA)
            val b = stylusAt(rig, pathB)
            val c = stylusAt(rig, pathC)

            rig.send(PointerEvents.down(a))
            rig.send(PointerEvents.move(b))
            rig.send(PointerEvents.move(c))
            if (brush.buildUp) {
                assertNotEquals("precondition: the stroke really dirtied the layer", before.toList(), rig.layerPixels().toList())
            }
            assertNotNull(rig.engine.strokeInProgressLayerId)

            rig.send(PointerEvents.cancel(c))

            assertSamePixels("cancel did not restore the pre-stroke layer (${brush.id})", before, rig.layerPixels())
            assertNull("cancel left the stroke marker set (${brush.id})", rig.engine.strokeInProgressLayerId)
            assertFalse("a cancelled stroke must not be undoable (${brush.id})", rig.engine.undoManager.canUndo)
            assertEquals("a cancelled stroke must not report a commit (${brush.id})", 0, rig.commits)
            assertEquals(listOf(true, false), rig.activeChanges)

            // A late pen-up for the cancelled pointer must not resurrect / commit anything.
            rig.send(PointerEvents.up(c))
            assertSamePixels("a pen-up after cancel changed the layer (${brush.id})", before, rig.layerPixels())
            assertEquals(0, rig.commits)
            assertFalse(rig.engine.undoManager.canUndo)

            // The engine is usable again, and the next stroke carries none of the cancelled one.
            val clean = Rig(brush, prefillLeftHalf = true)
            val d1 = stylusAt(clean, floatArrayOf(20f, 140f))
            val d2 = stylusAt(clean, floatArrayOf(120f, 140f))
            clean.send(PointerEvents.down(d1)); clean.send(PointerEvents.move(d2)); clean.send(PointerEvents.up(d2))
            val e1 = stylusAt(rig, floatArrayOf(20f, 140f))
            val e2 = stylusAt(rig, floatArrayOf(120f, 140f))
            rig.send(PointerEvents.down(e1)); rig.send(PointerEvents.move(e2)); rig.send(PointerEvents.up(e2))
            assertSamePixels("a stroke after a cancel differs from the same stroke without one (${brush.id})", clean.layerPixels(), rig.layerPixels())
            assertEquals(1, rig.commits)
        }
    }

    @Test fun `ACTION_CANCEL also drops tracked fingers so a stray move cannot pan`() {
        val rig = Rig(BrushPresets.InkPen)
        val before = rig.matrixValues()
        val f = PointerEvents.finger(0, 60f, 60f)
        rig.send(PointerEvents.down(f))
        rig.send(PointerEvents.cancel(f))
        rig.send(PointerEvents.move(f.at(60f, 60f)))
        rig.send(PointerEvents.move(f.at(120f, 120f)))
        assertSameMatrix("cancel must forget the finger", before, rig.matrixValues())
    }

    @Test fun `ACTION_CANCEL reverts an in-flight selection gesture`() {
        val rig = Rig(BrushPresets.InkPen, tool = ToolMode.SELECT)
        val a = stylusAt(rig, floatArrayOf(20f, 20f))
        val b = stylusAt(rig, floatArrayOf(80f, 80f))
        rig.send(PointerEvents.down(a))
        rig.send(PointerEvents.move(b))
        assertNotNull("a selection is being defined", rig.engine.selectionRect)
        rig.send(PointerEvents.cancel(b))
        assertNull("cancel must not leave a half-defined selection", rig.engine.selectionRect)
        assertFalse(rig.engine.undoManager.canUndo)
    }

    // ================================================================ locked layer

    @Test fun `locking the layer mid-stroke cancels the stroke and rolls its pixels back`() {
        val rig = Rig(BrushPresets.FeltMarker, prefillLeftHalf = true)
        val before = rig.layerPixels()
        val a = stylusAt(rig, pathA)
        val b = stylusAt(rig, pathB)
        val c = stylusAt(rig, pathC)
        val d = stylusAt(rig, pathD)

        rig.send(PointerEvents.down(a))
        rig.send(PointerEvents.move(b))
        assertNotEquals("precondition: ink landed", before.toList(), rig.layerPixels().toList())

        // The Layers panel locks the layer the pen is drawing on (a second pointer, or the keyboard).
        rig.engine.setLayerLocked(rig.layer, true)
        rig.send(PointerEvents.move(c))

        assertSamePixels("a stroke kept landing on a locked layer", before, rig.layerPixels())
        assertNull(rig.engine.strokeInProgressLayerId)
        assertEquals(listOf(true, false), rig.activeChanges)

        // The rest of the gesture is inert: no ink, no commit, no pan.
        val matrix = rig.matrixValues()
        rig.send(PointerEvents.move(d))
        rig.send(PointerEvents.up(d))
        assertSamePixels("the remainder of the gesture drew on a locked layer", before, rig.layerPixels())
        assertEquals("a cancelled-by-lock stroke must not commit", 0, rig.commits)
        assertSameMatrix("the leftover pen pointer must not pan the view", matrix, rig.matrixValues())

        // History holds only the lock itself: undoing it unlocks and there is NO pixel step below it.
        assertTrue(rig.engine.undo())
        assertFalse(rig.layer.locked)
        assertFalse("no stroke step was recorded", rig.engine.undoManager.canUndo)
    }

    @Test fun `a stylus on an already locked layer does nothing in every tool`() {
        for (tool in listOf(ToolMode.BRUSH, ToolMode.FILL, ToolMode.SELECT)) {
            val rig = Rig(BrushPresets.InkPen, prefillLeftHalf = true, tool = tool)
            rig.layer.locked = true
            val before = rig.layerPixels()
            val a = stylusAt(rig, pathA)
            val b = stylusAt(rig, pathB)
            rig.send(PointerEvents.down(a))
            rig.send(PointerEvents.move(b))
            rig.send(PointerEvents.up(b))
            assertSamePixels("a stylus altered a locked layer under $tool", before, rig.layerPixels())
            assertNull(rig.engine.strokeInProgressLayerId)
            assertNull("no selection on a locked layer ($tool)", rig.engine.selectionRect)
            assertFalse(rig.engine.undoManager.canUndo)
            assertEquals(0, rig.commits)
            assertTrue(rig.activeChanges.isEmpty())
        }
    }

    // ================================================================ hardware eraser

    private fun eraseWith(current: Brush, hardwareEraser: Boolean): IntArray {
        val rig = Rig(current, prefillLeftHalf = false)
        // Solid red across the whole layer so an eraser's footprint shows as transparent pixels.
        android.graphics.Canvas(rig.layer.bitmap).drawColor(0xFFFF0000.toInt())
        val line: (P) -> P = if (hardwareEraser) { p -> asEraser(p) } else { p -> p }
        val a = line(stylusAt(rig, floatArrayOf(40f, 80f)))
        val b = line(stylusAt(rig, floatArrayOf(120f, 80f)))
        rig.send(PointerEvents.down(a)); rig.send(PointerEvents.move(b)); rig.send(PointerEvents.up(b))
        return rig.layerPixels()
    }

    @Test fun `TOOL_TYPE_ERASER selects FlatEraser unless an eraser is already the current brush`() {
        // Pencil selected, pen flipped: the hardware eraser is the shortcut to the plain default. The
        // result equals selecting FlatEraser and using the stylus tip, pixel for pixel.
        val hardwareOverPencil = eraseWith(BrushPresets.Pencil, hardwareEraser = true)
        val stylusFlat = eraseWith(BrushPresets.FlatEraser, hardwareEraser = false)
        assertSamePixels("hardware eraser over a non-eraser brush is not FlatEraser", stylusFlat, hardwareOverPencil)
        assertEquals("it really erased", 0, Color.alpha(hardwareOverPencil[80 * size + 80]))
        assertEquals("and only along its own footprint", 0xFFFF0000.toInt(), hardwareOverPencil[10 * size + 10])

        // A deliberately picked eraser variant wins over the default.
        val hardwareOverHard = eraseWith(BrushPresets.HardEraser, hardwareEraser = true)
        val stylusHard = eraseWith(BrushPresets.HardEraser, hardwareEraser = false)
        assertSamePixels("hardware eraser must use the selected eraser variant", stylusHard, hardwareOverHard)
        assertNotEquals(
            "HardEraser and FlatEraser have different footprints, so this proves the variant was honoured",
            stylusFlat.toList(), hardwareOverHard.toList(),
        )
    }

    @Test fun `a stylus tip with an eraser brush selected erases but with an ink brush selected it inks`() {
        val inked = eraseWith(BrushPresets.InkPen, hardwareEraser = false)
        assertEquals("the tip must not erase with InkPen selected", 255, Color.alpha(inked[80 * size + 80]))
        assertEquals("it inked in the brush colour", Color.BLACK, inked[80 * size + 80])
    }

    // ================================================================ mouse and unknown

    @Test fun `mouse and unknown pointers do nothing in any tool`() {
        val kinds = listOf<Pair<String, (Int, Float, Float) -> P>>(
            "mouse" to { id, x, y -> PointerEvents.mouse(id, x, y) },
            "unknown" to { id, x, y -> PointerEvents.unknown(id, x, y) },
        )
        for ((name, make) in kinds) {
            for (tool in ToolMode.entries) {
                val rig = Rig(BrushPresets.InkPen, prefillLeftHalf = true, tool = tool)
                val pixels = rig.layerPixels()
                val scratch = rig.scratchPixels()
                val matrix = rig.matrixValues()

                val p0 = rig.toView(40f, 40f)
                rig.send(PointerEvents.down(make(0, p0[0], p0[1])))
                rig.send(PointerEvents.move(make(0, p0[0], p0[1])))
                rig.send(PointerEvents.move(make(0, p0[0] + 40f, p0[1] + 30f)))
                rig.send(PointerEvents.move(make(0, p0[0] + 80f, p0[1] + 60f)))
                rig.send(PointerEvents.up(make(0, p0[0] + 80f, p0[1] + 60f)))

                assertSamePixels("$name drew under $tool", pixels, rig.layerPixels())
                assertSamePixels("$name stamped the scratch under $tool", scratch, rig.scratchPixels())
                assertSameMatrix("$name panned the view under $tool", matrix, rig.matrixValues())
                assertNull("$name defined a selection under $tool", rig.engine.selectionRect)
                assertNull(rig.engine.strokeInProgressLayerId)
                assertFalse("$name created history under $tool", rig.engine.undoManager.canUndo)
                assertEquals(0, rig.commits)
                assertTrue(rig.activeChanges.isEmpty())
            }
        }
    }

    @Test fun `a mouse joining a live stylus stroke is ignored`() {
        val control = Rig(BrushPresets.FeltMarker)
        drawStylusStroke(control)

        val rig = Rig(BrushPresets.FeltMarker)
        val matrix = rig.matrixValues()
        val a = stylusAt(rig, pathA)
        val b = stylusAt(rig, pathB)
        val c = stylusAt(rig, pathC)
        val d = stylusAt(rig, pathD)
        val mouse = PointerEvents.mouse(1, 20f, 20f)
        rig.send(PointerEvents.down(a))
        rig.send(PointerEvents.move(b))
        rig.send(PointerEvents.pointerDown(1, b, mouse))
        rig.send(PointerEvents.move(c, mouse.at(140f, 140f)))
        rig.send(PointerEvents.move(d, mouse.at(10f, 150f)))
        rig.send(PointerEvents.up(d))
        assertSamePixels("a mouse pointer changed the stroke", control.layerPixels(), rig.layerPixels())
        assertSameMatrix("a mouse pointer moved the view", matrix, rig.matrixValues())
    }

    // ================================================================ Fill / Select are stylus-only

    @Test fun `Fill and Select respond to the stylus and never to a finger`() {
        // Fill: a stylus tap floods the (empty) layer with the current colour; a finger tap is a no-op.
        val fillFinger = Rig(BrushPresets.InkPen, tool = ToolMode.FILL)
        val p = fillFinger.toView(80f, 80f)
        fillFinger.send(PointerEvents.down(PointerEvents.finger(0, p[0], p[1])))
        fillFinger.send(PointerEvents.up(PointerEvents.finger(0, p[0], p[1])))
        assertEquals("finger tap must not fill", 0, alphaAt(fillFinger, 80, 80))
        assertFalse(fillFinger.engine.undoManager.canUndo)

        val fillStylus = Rig(BrushPresets.InkPen, tool = ToolMode.FILL)
        val s = stylusAt(fillStylus, floatArrayOf(80f, 80f))
        fillStylus.send(PointerEvents.down(s))
        fillStylus.send(PointerEvents.up(s))
        assertEquals("stylus tap fills", Color.BLACK, fillStylus.layer.bitmap.getPixel(80, 80))
        assertTrue(fillStylus.engine.undoManager.canUndo)
        assertEquals(1, fillStylus.commits)

        // Select: a stylus drag defines a rect; a finger drag pans and defines nothing (covered by the
        // finger test above for every tool, asserted again here as the positive counterpart).
        val sel = Rig(BrushPresets.InkPen, tool = ToolMode.SELECT)
        val a = stylusAt(sel, floatArrayOf(20f, 20f))
        val b = stylusAt(sel, floatArrayOf(90f, 70f))
        sel.send(PointerEvents.down(a)); sel.send(PointerEvents.move(b)); sel.send(PointerEvents.up(b))
        val rect = sel.engine.selectionRect
        assertNotNull("a stylus drag defines a selection", rect)
        assertEquals(20f, rect!!.left, 0.05f)
        assertEquals(90f, rect.right, 0.05f)
    }

    // ================================================================ hover

    @Test fun `hover events never start a stroke change state or move the view`() {
        val rig = Rig(BrushPresets.FeltMarker, prefillLeftHalf = true)
        val pixels = rig.layerPixels()
        val matrix = rig.matrixValues()
        val h = rig.toView(60f, 60f)

        rig.hover(PointerEvents.hover(MotionEvent.ACTION_HOVER_ENTER, PointerEvents.stylus(0, h[0], h[1], pressure = 0f)))
        rig.hover(PointerEvents.hover(MotionEvent.ACTION_HOVER_MOVE, PointerEvents.stylus(0, h[0] + 20f, h[1] + 20f, pressure = 0f)))
        rig.hover(PointerEvents.hover(MotionEvent.ACTION_HOVER_MOVE, PointerEvents.eraser(0, h[0] + 30f, h[1] + 30f, pressure = 0f)))
        rig.hover(PointerEvents.hover(MotionEvent.ACTION_HOVER_MOVE, PointerEvents.finger(0, h[0] + 40f, h[1] + 40f, pressure = 0f)))
        rig.hover(PointerEvents.hover(MotionEvent.ACTION_HOVER_EXIT, PointerEvents.stylus(0, h[0], h[1], pressure = 0f)))

        assertSamePixels("hover altered pixels", pixels, rig.layerPixels())
        assertSameMatrix("hover moved the view", matrix, rig.matrixValues())
        assertNull(rig.engine.strokeInProgressLayerId)
        assertFalse(rig.engine.undoManager.canUndo)
        assertTrue(rig.activeChanges.isEmpty())

        // And hover in the middle of a real stroke does not disturb it.
        val control = Rig(BrushPresets.FeltMarker, prefillLeftHalf = true)
        drawStylusStroke(control)
        val a = stylusAt(rig, pathA)
        val b = stylusAt(rig, pathB)
        val c = stylusAt(rig, pathC)
        val d = stylusAt(rig, pathD)
        rig.send(PointerEvents.down(a))
        rig.hover(PointerEvents.hover(MotionEvent.ACTION_HOVER_MOVE, PointerEvents.stylus(0, 5f, 5f, pressure = 0f)))
        rig.send(PointerEvents.move(b))
        rig.hover(PointerEvents.hover(MotionEvent.ACTION_HOVER_MOVE, PointerEvents.stylus(0, 150f, 150f, pressure = 0f)))
        rig.send(PointerEvents.move(c))
        rig.send(PointerEvents.move(d))
        rig.send(PointerEvents.up(d))
        assertSamePixels("hover mid-stroke changed the stroke", control.layerPixels(), rig.layerPixels())
    }

    // ================================================================ historical samples

    @Test fun `batched historical samples are consumed in order and equal the same samples sent one by one`() {
        val batched = Rig(BrushPresets.InkPen)
        val sequential = Rig(BrushPresets.InkPen)
        val currentOnly = Rig(BrushPresets.InkPen)

        fun run(rig: Rig, mode: Int) {
            val a = stylusAt(rig, pathA)
            val b = stylusAt(rig, pathB)
            val c = stylusAt(rig, pathC)
            val d = stylusAt(rig, pathD)
            rig.send(PointerEvents.down(a))
            when (mode) {
                // ONE event carrying B and C as history and D as the current sample.
                0 -> rig.send(PointerEvents.move(d, history = listOf(listOf(b), listOf(c))))
                // The identical samples as three separate events.
                1 -> { rig.send(PointerEvents.move(b)); rig.send(PointerEvents.move(c)); rig.send(PointerEvents.move(d)) }
                // Historical points dropped on the floor: only the newest sample is used.
                2 -> rig.send(PointerEvents.move(d))
            }
            rig.send(PointerEvents.up(d))
        }
        run(batched, 0)
        run(sequential, 1)
        run(currentOnly, 2)

        assertSamePixels("history must be replayed exactly like separate MOVEs", sequential.layerPixels(), batched.layerPixels())
        assertNotEquals(
            "if history were ignored the dog-leg would collapse to a straight line",
            currentOnly.layerPixels().toList(), batched.layerPixels().toList(),
        )
    }

    // ================================================================ pressure gamma seam

    @Test fun `the pressure gamma provider defaults to Settings and an injected constant behaves identically`() {
        val settings = VellumApp.instance.settingsRepository
        settings.pressureCurveGamma = 1.8f

        // gamma = null keeps the view's DEFAULT provider: this is the production path, unchanged.
        val viaSettings = Rig(BrushPresets.InkPen, gamma = null)
        drawStylusStroke(viaSettings, pressure = 0.5f)

        val injectedSame = Rig(BrushPresets.InkPen, gamma = { 1.8f })
        drawStylusStroke(injectedSame, pressure = 0.5f)
        assertSamePixels("default provider must read SettingsRepository.pressureCurveGamma", injectedSame.layerPixels(), viaSettings.layerPixels())

        val linear = Rig(BrushPresets.InkPen, gamma = { 1f })
        drawStylusStroke(linear, pressure = 0.5f)
        assertNotEquals("the gamma really is applied (Firm thins a half-pressure line)", linear.layerPixels().toList(), viaSettings.layerPixels().toList())
        assertTrue("Firm gamma lays down less ink at half pressure than Linear", alphaSum(viaSettings.layerPixels()) < alphaSum(linear.layerPixels()))

        // The default follows a later Settings change with no re-attach: a live setting, not a snapshot.
        settings.pressureCurveGamma = 1f
        val nowLinear = Rig(BrushPresets.InkPen, gamma = null)
        drawStylusStroke(nowLinear, pressure = 0.5f)
        assertSamePixels("default provider must track Settings", linear.layerPixels(), nowLinear.layerPixels())
    }

    @Test fun `gamma is asked for once per gesture start and once per move never once per historical sample`() {
        var asked = 0
        val rig = Rig(BrushPresets.InkPen, gamma = { asked++; 1f })
        val a = stylusAt(rig, pathA)
        val b = stylusAt(rig, pathB)
        val c = stylusAt(rig, pathC)
        val d = stylusAt(rig, pathD)

        rig.send(PointerEvents.down(a))
        assertEquals("start", 1, asked)
        rig.send(PointerEvents.move(d, history = listOf(listOf(b), listOf(c)))) // 2 historical + 1 current
        assertEquals("a batched MOVE reads it once, not three times", 2, asked)
        rig.send(PointerEvents.move(d.at(d.x + 4f, d.y)))
        assertEquals(3, asked)
        rig.send(PointerEvents.up(d))
        assertEquals("pen-up never reads it", 3, asked)
    }
}
