package com.vellum.studio.canvas

import android.graphics.Color
import android.graphics.Paint
import com.vellum.studio.VellumApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [StrokeCommit] is a pure extraction of logic that used to be independently duplicated in
 * [DrawingCanvasView.endStroke] and [DrawingCanvasView.drawShapeOnto] -- these tests pin exactly the
 * behavior both call sites relied on before the extraction: which brushes route through the shared
 * scratch buffer, what canvas dabs for each kind land on, and that flattening the scratch onto the
 * layer only happens (and only touches the layer) for a scratch-routed brush. [CanvasEngine]'s
 * constructor reads [com.vellum.studio.util.DeviceCapabilities], which needs [VellumApp] as the
 * Robolectric application (same reason [DrawingCanvasViewInputRoutingTest] and
 * [UndoIntegrityTest] use it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeCommitTest {

    private val size = 32

    private fun engine(): CanvasEngine = CanvasEngine(size, size).also { it.addLayer("base") }

    @Test fun `usesScratch is the inverse of buildUp for every brush preset`() {
        for (brush in BrushPresets.all) {
            assertEquals(brush.name, !brush.buildUp, StrokeCommit.usesScratch(brush))
        }
    }

    @Test fun `a scratch-routed brush's target canvas is the engine's own shared scratch canvas`() {
        val eng = engine()
        val layer = eng.activeLayer()!!
        // eng.scratch() clears-and-returns the SAME Canvas instance every call -- see its own doc --
        // so calling it a second time here to compare must still refer to that one shared instance.
        val expected = eng.scratch()
        val target = StrokeCommit.targetCanvas(eng, layer, BrushPresets.InkPen)
        assertSame(expected, target)
    }

    @Test fun `a buildUp brush's target canvas draws straight onto the layer bitmap`() {
        val eng = engine()
        val layer = eng.activeLayer()!!
        val target = StrokeCommit.targetCanvas(eng, layer, BrushPresets.FeltMarker)
        target.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply { color = Color.RED })
        assertEquals(Color.RED, layer.bitmap.getPixel(size / 2, size / 2))
    }

    @Test fun `flatten composites the scratch onto the layer for a scratch-routed brush`() {
        val eng = engine()
        val layer = eng.activeLayer()!!
        eng.scratch().drawColor(Color.BLUE)
        StrokeCommit.flatten(eng, layer, BrushPresets.InkPen)
        assertEquals(Color.BLUE, layer.bitmap.getPixel(size / 2, size / 2))
    }

    @Test fun `flatten is a no-op for a buildUp brush -- nothing in scratch reaches the layer`() {
        val eng = engine()
        val layer = eng.activeLayer()!!
        eng.scratch().drawColor(Color.BLUE)
        // A buildUp brush never routes through scratch (it stamps straight onto the layer while
        // live), so calling flatten for one must leave the layer exactly as-is -- this no-op is what
        // lets drawShapeOnto/endStroke call StrokeCommit.flatten unconditionally instead of each
        // re-deriving usesScratch themselves.
        StrokeCommit.flatten(eng, layer, BrushPresets.FeltMarker)
        assertEquals(Color.TRANSPARENT, layer.bitmap.getPixel(size / 2, size / 2))
    }
}
