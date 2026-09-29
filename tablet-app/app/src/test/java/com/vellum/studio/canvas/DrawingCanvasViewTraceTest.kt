package com.vellum.studio.canvas

import com.vellum.studio.VellumApp
import com.vellum.studio.testing.PointerEvents
import com.vellum.studio.util.TraceSections
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowTrace

/**
 * Regression coverage for this roadmap item's `android.os.Trace` instrumentation: before this item,
 * a real stroke/fill/save left literally nothing for Perfetto to attribute time to, so this asserts
 * on the actual recorded sections (via Robolectric's `ShadowTrace`, which intercepts the same
 * `Trace.beginSection`/`endSection` calls a real device would report to Perfetto) rather than just
 * on the drawn pixels -- a correct stroke with no [TraceSections] wrapping would still pass every
 * pixel assertion elsewhere in [DrawingCanvasViewInputRoutingTest] while failing this file.
 *
 * `ShadowTrace.getCurrentSections()` is asserted empty after every scenario: a leftover open section
 * there means a `beginSection` somewhere was never matched by an `endSection` (e.g. an early return
 * added on one side of a `trace { }` wrapper without going through it) -- exactly the kind of bug
 * that would silently corrupt every section reported for the rest of a real trace capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawingCanvasViewTraceTest {

    private val size = 64

    private lateinit var engine: CanvasEngine
    private lateinit var view: DrawingCanvasView

    @Before
    fun setUp() {
        ShadowTrace.reset()
        engine = CanvasEngine(size, size).also { it.addLayer("base") }
        engine.currentBrush = BrushPresets.InkPen
        view = DrawingCanvasView(RuntimeEnvironment.getApplication())
        view.layout(0, 0, size, size)
        view.attachEngine(engine)
        view.resetView()
        view.pressureGammaProvider = { 1f }
    }

    private fun send(ev: android.view.MotionEvent) {
        view.onTouchEvent(ev)
        ev.recycle()
    }

    private fun assertSectionsBalanced() {
        assertTrue(
            "a Trace section was begun but never ended: ${ShadowTrace.getCurrentSections()}",
            ShadowTrace.getCurrentSections().isEmpty(),
        )
    }

    @Test fun `a committed stroke reports Stroke down, Stroke move and Stroke commit`() {
        val a = PointerEvents.stylus(0, 10f, 10f)
        val b = PointerEvents.stylus(0, 20f, 20f)
        send(PointerEvents.down(a))
        send(PointerEvents.move(b))
        send(PointerEvents.up(b))

        val recorded = ShadowTrace.getPreviousSections()
        assertTrue("expected ${TraceSections.STROKE_DOWN} in $recorded", recorded.contains(TraceSections.STROKE_DOWN))
        assertTrue("expected ${TraceSections.STROKE_MOVE} in $recorded", recorded.contains(TraceSections.STROKE_MOVE))
        assertTrue("expected ${TraceSections.STROKE_COMMIT} in $recorded", recorded.contains(TraceSections.STROKE_COMMIT))
        assertSectionsBalanced()
    }

    @Test fun `a cancelled stroke still reports Stroke down and rolls back through a balanced Layer restore section`() {
        val a = PointerEvents.stylus(0, 10f, 10f)
        val b = PointerEvents.stylus(0, 20f, 20f)
        send(PointerEvents.down(a))
        send(PointerEvents.move(b))
        send(PointerEvents.cancel(b))

        val recorded = ShadowTrace.getPreviousSections()
        assertTrue(recorded.contains(TraceSections.STROKE_DOWN))
        assertTrue("cancel rolls back via Layer.restore -- expected it in $recorded", recorded.contains(TraceSections.LAYER_RESTORE))
        assertSectionsBalanced()
    }

    @Test fun `a bucket fill reports Fill perform`() {
        engine.currentTool = ToolMode.FILL
        send(PointerEvents.down(PointerEvents.stylus(0, 5f, 5f)))
        send(PointerEvents.up(PointerEvents.stylus(0, 5f, 5f)))

        assertTrue(ShadowTrace.getPreviousSections().contains(TraceSections.FILL_PERFORM))
        assertSectionsBalanced()
    }

    @Test fun `undo of a committed stroke reports Layer restore`() {
        val a = PointerEvents.stylus(0, 10f, 10f)
        val b = PointerEvents.stylus(0, 20f, 20f)
        send(PointerEvents.down(a))
        send(PointerEvents.move(b))
        send(PointerEvents.up(b))
        ShadowTrace.reset() // isolate: only what `undo()` itself records

        assertTrue(engine.undo())

        assertTrue(ShadowTrace.getPreviousSections().contains(TraceSections.LAYER_RESTORE))
        assertSectionsBalanced()
    }
}
