package com.vellum.studio.canvas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DabSinkCanvas]'s two contracts: (1) a bare instance with no [DabSinkCanvas.onDab] override draws
 * byte-identical pixels to a plain `Canvas(bitmap)` -- required for it to ever be droppable in as a
 * stroke's target with zero visible change -- and (2) a subclass genuinely controls whether, and
 * how, the frozen dab loop's per-dab `drawBitmap(Bitmap, Matrix, Paint?)` call reaches the real
 * bitmap, which is the entire point of introducing this seam (grain/smudge/wet-edge work composes
 * here in later waves; this item only proves the seam itself works).
 *
 * `@GraphicsMode(NATIVE)` for real rasterization -- see [StrokePreviewCompositorTest]'s class doc for
 * why the legacy Robolectric Canvas shadow can't be trusted for a pixel assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DabSinkCanvasTest {

    private val size = 64

    private fun bitmap(): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private fun pixelsOf(bmp: Bitmap): IntArray =
        IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }

    /** Drives a jitter-free InkPen stroke's dabs through [target] -- the exact call pattern
     * [DrawingCanvasView] makes via [DabStrokeEngine.start]/[DabStrokeEngine.moveTo]. */
    private fun drawInkStroke(target: Canvas) {
        val engine = DabStrokeEngine(BrushPresets.InkPen, Color.BLACK, 1f, 1f)
        engine.start(target, InputSample(10f, 10f, pressure = 1f))
        engine.moveTo(target, InputSample(50f, 40f, pressure = 1f))
        engine.moveTo(target, InputSample(20f, 55f, pressure = 0.6f))
    }

    @Test fun `bare DabSinkCanvas draws byte-identical pixels to a plain Canvas`() {
        val plainBitmap = bitmap()
        drawInkStroke(Canvas(plainBitmap))

        val sinkBitmap = bitmap()
        drawInkStroke(DabSinkCanvas(sinkBitmap))

        assertArrayEquals(pixelsOf(plainBitmap), pixelsOf(sinkBitmap))
    }

    private class CountingDabSinkCanvas(bitmap: Bitmap) : DabSinkCanvas(bitmap) {
        var dabCount = 0
            private set

        override fun onDab(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            dabCount++
            super.onDab(bitmap, matrix, paint)
        }
    }

    @Test fun `a subclass intercepts every dab the frozen loop stamps`() {
        val sink = CountingDabSinkCanvas(bitmap())
        drawInkStroke(sink)
        assertTrue(
            "expected multiple intercepted dabs for a 3-sample stroke, got ${sink.dabCount}",
            sink.dabCount > 1,
        )
    }

    private class SuppressingDabSinkCanvas(bitmap: Bitmap) : DabSinkCanvas(bitmap) {
        // Deliberately does NOT call super.onDab -- drops every dab instead of forwarding it, which
        // proves onDab genuinely controls whether the real rasterizer ever runs, not just an
        // observing side channel a future grain/smudge implementation couldn't actually act through.
        override fun onDab(bitmap: Bitmap, matrix: Matrix, paint: Paint?) = Unit
    }

    @Test fun `a subclass can replace the dab entirely -- nothing reaches the bitmap`() {
        val bmp = bitmap()
        drawInkStroke(SuppressingDabSinkCanvas(bmp))
        assertTrue(
            "expected an untouched (all-transparent) bitmap when every dab is swallowed",
            pixelsOf(bmp).all { it == 0 },
        )
    }
}
