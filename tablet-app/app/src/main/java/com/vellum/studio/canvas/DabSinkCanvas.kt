package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

/**
 * A [Canvas] subclass that intercepts the frozen dab loop's per-dab draw call with zero edits to
 * [StrokeRenderer]. [StrokeRenderer.stampAt] makes exactly one drawing call per dab --
 * `target.drawBitmap(stampBitmap, stampMatrix, dabPaint)` -- against whatever [Canvas] it was
 * handed; it was already written to accept any `Canvas`, so it never needed to know or care that
 * the object behind that reference might not be a plain platform `Canvas`. Because `Canvas.
 * drawBitmap(Bitmap, Matrix, Paint?)` is an ordinary (non-final) method, giving [StrokeRenderer] an
 * instance of THIS class instead of a bare `Canvas` makes that one call dispatch here via normal
 * virtual method resolution -- no reflection, no wrapping of the loop itself, nothing StrokeRenderer
 * has to opt into.
 *
 * This is the composition seam later waves need: stabilization/prediction sit upstream of a
 * [StrokeEngine] (they reshape the SAMPLES before [StrokeEngine.moveTo] ever sees them), but grain,
 * smudge and wet-edge work all need to see or alter individual DABS as they're stamped -- exactly
 * what overriding [onDab] gives them, without a fork of the geometry/spacing/pressure/tilt/jitter
 * math [StrokeRenderer] already gets right.
 *
 * The default [onDab] forwards unchanged to the real rasterizer, so simply wrapping a stroke's
 * target bitmap in a bare `DabSinkCanvas` (no override) is byte-for-byte identical to drawing
 * straight onto a plain `Canvas(bitmap)` -- proven by [DabSinkCanvasTest]'s pass-through case. A
 * subclass overrides [onDab] to inspect the dab (recording, characterization tests), redirect it
 * (a different tip bitmap), or layer additional drawing around it (a smudge kernel read before the
 * dab lands, a grain multiply after) -- see the class doc above for why no such subclass exists yet
 * in production: this item only introduces the seam, it doesn't wire a consumer into it.
 *
 * Only the one three-argument `drawBitmap(Bitmap, Matrix, Paint?)` overload is overridden, because
 * that is the only overload the frozen loop calls (confirmed by reading `StrokeRenderer.stampAt`);
 * every other `Canvas` method -- including the other `drawBitmap` overloads -- passes straight
 * through to the real platform `Canvas` this class extends, unmodified.
 */
open class DabSinkCanvas(bitmap: Bitmap) : Canvas(bitmap) {

    final override fun drawBitmap(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        onDab(bitmap, matrix, paint)
    }

    /**
     * Called once per dab the frozen loop stamps: [bitmap] is the cached brush-tip texture from
     * [BrushStampCache] (the SAME instance every dab in a stroke reuses -- callers must not mutate
     * it), [matrix] is the fully-computed per-dab placement (translate + tilt-aware scale +
     * orientation rotate), and [paint] carries the per-dab color/alpha/blend-mode StrokeRenderer
     * already worked out. The default implementation is a pure pass-through.
     */
    protected open fun onDab(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
        super.drawBitmap(bitmap, matrix, paint)
    }
}
