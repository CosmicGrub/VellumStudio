package com.vellum.studio.canvas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel tests for [StrokePreviewCompositor] -- the live mid-stroke preview of a scratch-routed
 * stroke. Needs `@GraphicsMode(NATIVE)` for the same reason [com.vellum.studio.academy.DiagramRendererTest]
 * does: the legacy Robolectric Canvas shadow doesn't rasterize, so every pixel assertion would pass
 * against a blank bitmap. NATIVE runs real Skia (software) -- the same saveLayer/clipOutRect/blend
 * semantics the app's canvas has, but NOT HWUI itself, so what is proven here is the compositing
 * math and the saveLayer-clips-its-contents semantics; that the real device frame loop damages the
 * whole view every frame (which is what makes the old clip visible) is inferred from the
 * invalidate() contract and still needs the on-device check listed in the fix's report.
 *
 * "Reference" throughout is what stroke commit produces: the scratch baked into a copy of the layer
 * bitmap (mirroring CanvasEngine.flattenScratchOnto -- CanvasEngine itself can't be constructed in a
 * JVM test, its init reads app-singleton device capabilities), and only then that layer composited
 * over the backdrop with the layer's opacity and blend mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokePreviewCompositorTest {

    private val size = 64
    private val backdropColor = 0xFF8899AA.toInt()
    private val leftLayerColor = 0xFFCC6633.toInt()

    // Two dabs, "older" first: the stroke has already moved on from the first when the frame under
    // test is drawn, which is exactly the mid-stroke situation the old per-frame bounds got wrong.
    private val olderDab = RectF(8f, 8f, 20f, 20f)
    private val newerDab = RectF(26f, 14f, 38f, 26f)

    private fun backdrop(): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(backdropColor) }

    /** Left half opaque orange, right half 50%-alpha blue -- so blend modes act on both a solid and
     * a translucent layer, not just a trivially opaque one. */
    private fun layerBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.color = leftLayerColor
        c.drawRect(0f, 0f, size / 2f, size.toFloat(), p)
        p.color = 0x80336699.toInt()
        c.drawRect(size / 2f, 0f, size.toFloat(), size.toFloat(), p)
        return bmp
    }

    /** The scratch a mid-stroke frame would hold: an older, fully opaque dab and a newer one. */
    private fun scratchWithBothDabs(): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD02020.toInt() }
        c.drawOval(olderDab, p)
        c.drawOval(newerDab, p)
        return bmp
    }

    private fun layerPaint(mode: LayerBlendMode, opacity: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = (opacity * 255).toInt()
        blendMode = mode.blendMode
    }

    /** Stroke commit, then the layer composited onto [backdrop]. */
    private fun commitThenComposite(
        mode: LayerBlendMode,
        opacity: Float,
        scratch: Bitmap,
        scratchMode: BlendMode?,
        cap: Float,
    ): Bitmap {
        val committed = layerBitmap()
        val flatten = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (cap * 255).toInt()
            blendMode = scratchMode
        }
        Canvas(committed).drawBitmap(scratch, 0f, 0f, flatten)
        val out = backdrop()
        Canvas(out).drawBitmap(committed, 0f, 0f, layerPaint(mode, opacity))
        return out
    }

    /** The production preview after the stroke's two dabs have each been reported dirty on their own frame. */
    private fun previewMidStroke(
        mode: LayerBlendMode,
        opacity: Float,
        scratch: Bitmap,
        scratchMode: BlendMode?,
        cap: Float,
    ): Bitmap {
        val compositor = StrokePreviewCompositor()
        compositor.beginStroke()
        val out = backdrop()
        val canvas = Canvas(out)
        // Frame 1 (only the older dab known so far) drawn and thrown away, exactly as a real earlier
        // frame would have been; the assertions are on frame 2, when the pen is at the newer dab.
        compositor.noteDirty(olderDab)
        compositor.drawLayerWithScratch(Canvas(backdrop()), layerBitmap(), scratch, layerPaint(mode, opacity), scratchMode, cap, 4f)
        compositor.noteDirty(newerDab)
        compositor.drawLayerWithScratch(canvas, layerBitmap(), scratch, layerPaint(mode, opacity), scratchMode, cap, 4f)
        return out
    }

    /** The onDraw this replaces: layer drawn ONLY inside a saveLayer bounded by the last frame's dirty rect. */
    private fun legacyEraserPreview(lastFrameDirty: RectF, scratch: Bitmap): Bitmap {
        val out = backdrop()
        val canvas = Canvas(out)
        val bounds = RectF(lastFrameDirty).apply { inset(-4f, -4f) }
        val scratchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.DST_OUT }
        val save = canvas.saveLayer(bounds, layerPaint(LayerBlendMode.NORMAL, 1f))
        canvas.drawBitmap(layerBitmap(), 0f, 0f, null)
        canvas.drawBitmap(scratch, 0f, 0f, scratchPaint)
        canvas.restoreToCount(save)
        return out
    }

    private fun assertPixelsClose(expected: Bitmap, actual: Bitmap, tolerance: Int, what: String) {
        var worst = 0
        var worstAt = ""
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val e = expected.getPixel(x, y)
            val a = actual.getPixel(x, y)
            val d = maxOf(
                abs(Color.alpha(e) - Color.alpha(a)), abs(Color.red(e) - Color.red(a)),
                abs(Color.green(e) - Color.green(a)), abs(Color.blue(e) - Color.blue(a)),
            )
            if (d > worst) { worst = d; worstAt = "($x,$y) expected=${Integer.toHexString(e)} actual=${Integer.toHexString(a)}" }
        }
        assertTrue("$what: worst channel diff $worst > $tolerance at $worstAt", worst <= tolerance)
    }

    // --- the defect, proven against real Skia -----------------------------------------------------

    @Test fun `premise - a saveLayer bounded by the last frame's dirty rect draws the layer nowhere else`() {
        val scratch = scratchWithBothDabs()
        val legacy = legacyEraserPreview(lastFrameDirty = newerDab, scratch = scratch)
        // Far from the pen, on the opaque orange half: the layer is simply not drawn, so the backdrop shows.
        assertEquals("legacy path lost the layer away from the dab", backdropColor, legacy.getPixel(4, 58))
    }

    @Test fun `eraser preview keeps the layer everywhere outside the stroke and erases the whole stroke so far`() {
        val preview = previewMidStroke(LayerBlendMode.NORMAL, 1f, scratchWithBothDabs(), BlendMode.DST_OUT, 1f)
        // Outside the cumulative bounds: the layer is untouched, on both halves of the layer.
        assertEquals(leftLayerColor, preview.getPixel(4, 58))
        assertEquals(leftLayerColor, preview.getPixel(4, 40))
        // Older dab (already left behind by the pen) is still erased -- the legacy path lost this too.
        assertEquals(backdropColor, preview.getPixel(14, 14))
        // Newer dab (under the pen) erased.
        assertEquals(backdropColor, preview.getPixel(32, 20))
    }

    @Test fun `eraser preview far from the pen is pixel-identical to the unerased layer`() {
        val scratch = scratchWithBothDabs()
        val preview = previewMidStroke(LayerBlendMode.NORMAL, 1f, scratch, BlendMode.DST_OUT, 1f)
        val untouched = backdrop().also { Canvas(it).drawBitmap(layerBitmap(), 0f, 0f, layerPaint(LayerBlendMode.NORMAL, 1f)) }
        // Whole bottom band (y >= 40) is outside the stroke's padded cumulative bounds.
        for (y in 40 until size) for (x in 0 until size) {
            assertEquals("pixel ($x,$y) changed outside the stroke", untouched.getPixel(x, y), preview.getPixel(x, y))
        }
    }

    @Test fun `cumulative bounds survive later frames - the earlier dab is not forgotten`() {
        val compositor = StrokePreviewCompositor()
        compositor.beginStroke()
        val scratch = scratchWithBothDabs()
        val paint = layerPaint(LayerBlendMode.NORMAL, 1f)
        // Many frames, each reporting a dab far from where the first one landed.
        compositor.noteDirty(olderDab)
        compositor.drawLayerWithScratch(Canvas(backdrop()), layerBitmap(), scratch, paint, BlendMode.DST_OUT, 1f, 4f)
        for (i in 0 until 5) compositor.noteDirty(RectF(30f + i, 30f, 34f + i, 34f))
        val out = backdrop()
        compositor.drawLayerWithScratch(Canvas(out), layerBitmap(), scratch, paint, BlendMode.DST_OUT, 1f, 4f)
        assertEquals(backdropColor, out.getPixel(14, 14))
    }

    @Test fun `beginStroke forgets the previous stroke's bounds`() {
        val compositor = StrokePreviewCompositor()
        compositor.beginStroke()
        compositor.noteDirty(RectF(0f, 0f, size.toFloat(), size.toFloat()))
        compositor.beginStroke()
        compositor.noteDirty(newerDab)
        val out = backdrop()
        // The scratch still holds a mark at the older dab (in real use startStroke clears it, so this
        // is synthetic) but only the new stroke's bounds are live: the region-limited buffer must NOT
        // reach it. Had the previous stroke's full-canvas bounds survived beginStroke(), that mark
        // would be erased here -- so this pins the reset, which is what bounds the per-frame cost.
        compositor.drawLayerWithScratch(
            Canvas(out), layerBitmap(), scratchWithBothDabs(),
            layerPaint(LayerBlendMode.NORMAL, 1f), BlendMode.DST_OUT, 1f, 4f,
        )
        assertEquals(leftLayerColor, out.getPixel(14, 14))
        assertEquals(backdropColor, out.getPixel(32, 20))
    }

    @Test fun `a stroke entirely off the canvas draws the plain layer`() {
        val compositor = StrokePreviewCompositor()
        compositor.beginStroke()
        compositor.noteDirty(RectF(-200f, -200f, -150f, -150f))
        val out = backdrop()
        compositor.drawLayerWithScratch(
            Canvas(out), layerBitmap(), Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888),
            layerPaint(LayerBlendMode.NORMAL, 1f), BlendMode.DST_OUT, 1f, 4f,
        )
        assertEquals(leftLayerColor, out.getPixel(4, 4))
    }

    // --- preview == commit-then-composite ---------------------------------------------------------

    private val scratchModes: List<Pair<String, BlendMode?>> = listOf(
        "ink" to null,
        "eraser DST_OUT" to BlendMode.DST_OUT,
        "pastel MULTIPLY" to BlendMode.MULTIPLY,
    )

    @Test fun `preview equals commit-then-composite for every layer blend mode and brush kind at full opacity`() {
        val scratch = scratchWithBothDabs()
        for (mode in LayerBlendMode.entries) for ((label, scratchMode) in scratchModes) {
            val expected = commitThenComposite(mode, 1f, scratch, scratchMode, cap = 1f)
            val actual = previewMidStroke(mode, 1f, scratch, scratchMode, cap = 1f)
            assertPixelsClose(expected, actual, 2, "layer=${mode.label} brush=$label")
        }
    }

    @Test fun `preview equals commit-then-composite with layer opacity and a stroke opacity cap`() {
        val scratch = scratchWithBothDabs()
        for (mode in LayerBlendMode.entries) for ((label, scratchMode) in scratchModes) {
            val expected = commitThenComposite(mode, 0.6f, scratch, scratchMode, cap = 0.7f)
            val actual = previewMidStroke(mode, 0.6f, scratch, scratchMode, cap = 0.7f)
            assertPixelsClose(expected, actual, 3, "layer=${mode.label} brush=$label opacity=0.6 cap=0.7")
        }
    }

    @Test fun `the old plain path really did diverge from commit on a Multiply layer`() {
        // What onDraw used to do for plain ink on a non-Normal layer: layer with its blend mode, then the
        // scratch straight on top, Normal. Documents the layers:G1 defect against the same reference.
        val scratch = scratchWithBothDabs()
        val old = backdrop()
        val c = Canvas(old)
        c.drawBitmap(layerBitmap(), 0f, 0f, layerPaint(LayerBlendMode.MULTIPLY, 1f))
        c.drawBitmap(scratch, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        val expected = commitThenComposite(LayerBlendMode.MULTIPLY, 1f, scratch, null, cap = 1f)
        // Inside the older dab: commit multiplies the red ink into the layer; the old preview showed
        // opaque red ink over the backdrop instead.
        val e = expected.getPixel(14, 14)
        val o = old.getPixel(14, 14)
        assertTrue("old preview should differ visibly at the dab (expected=${Integer.toHexString(e)} old=${Integer.toHexString(o)})", abs(Color.red(e) - Color.red(o)) > 8 || abs(Color.green(e) - Color.green(o)) > 8)
        // ...and the new compositor matches commit there.
        val actual = previewMidStroke(LayerBlendMode.MULTIPLY, 1f, scratch, null, cap = 1f)
        assertPixelsClose(expected, actual, 2, "multiply layer, plain ink")
    }

    @Test fun `normal ink on a fully opaque Normal layer keeps the plain path and matches commit`() {
        val scratch = scratchWithBothDabs()
        val expected = commitThenComposite(LayerBlendMode.NORMAL, 1f, scratch, null, cap = 0.7f)
        val actual = previewMidStroke(LayerBlendMode.NORMAL, 1f, scratch, null, cap = 0.7f)
        assertPixelsClose(expected, actual, 2, "normal ink, normal opaque layer")
    }

    @Test fun `ink over existing content on a dimmed Normal layer matches commit`() {
        val scratch = scratchWithBothDabs()
        val expected = commitThenComposite(LayerBlendMode.NORMAL, 0.6f, scratch, null, cap = 0.7f)
        val actual = previewMidStroke(LayerBlendMode.NORMAL, 0.6f, scratch, null, cap = 0.7f)
        assertPixelsClose(expected, actual, 3, "normal ink, layer opacity 0.6")
    }

    // --- scaled view: no seam between the plain region and the offscreen region -------------------

    @Test fun `zoomed and panned eraser preview stays close to commit with no seam at the bounds edge`() {
        val scratch = scratchWithBothDabs()
        val view = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(backdropColor) }
        val vc = Canvas(view)
        vc.translate(7f, 5f)
        vc.scale(1.5f, 1.5f)
        val compositor = StrokePreviewCompositor()
        compositor.beginStroke()
        compositor.noteDirty(olderDab)
        compositor.noteDirty(newerDab)
        compositor.drawLayerWithScratch(vc, layerBitmap(), scratch, layerPaint(LayerBlendMode.NORMAL, 1f), BlendMode.DST_OUT, 1f, 4f)

        val committed = layerBitmap()
        Canvas(committed).drawBitmap(scratch, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.DST_OUT })
        val ref = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888).apply { eraseColor(backdropColor) }
        val rc = Canvas(ref)
        rc.translate(7f, 5f)
        rc.scale(1.5f, 1.5f)
        rc.drawBitmap(committed, 0f, 0f, layerPaint(LayerBlendMode.NORMAL, 1f))
        // Filtering happens on the merged layer in the reference and on layer/scratch separately in
        // the preview, so allow a little slack -- a seam (a doubled or missing row along the region
        // edge) would blow far past it on the opaque orange half.
        assertPixelsClose(ref, view, 24, "scaled eraser preview")
    }
}
