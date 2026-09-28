package com.vellum.studio.canvas

import android.graphics.Canvas
import android.graphics.RectF

/**
 * The seam between [DrawingCanvasView]'s input routing and whatever actually turns a stream of
 * [InputSample]s into pixels. Before this interface existed, the view held a concrete
 * [StrokeRenderer] field directly, which meant every future capability that needs to sit between
 * "the pen moved" and "dabs landed on a canvas" -- stabilization (smoothing/delaying the input
 * path before it reaches the dab loop), prediction, or anything that wraps the loop's OUTPUT via
 * [DabSinkCanvas] (grain, smudge, wet edges) -- would have had to either edit the frozen
 * [StrokeRenderer]/[BrushStampCache] pair directly (not allowed) or bolt itself onto the view with
 * no shared shape.
 *
 * [DabStrokeEngine] is the only implementation today, and it does nothing but forward to an
 * unmodified [StrokeRenderer] -- this interface changes zero behavior on its own. It exists purely
 * so the view (and tests) can depend on "something that turns samples into dabs" rather than that
 * one concrete class, and so a later engine (e.g. one that stabilizes the path before handing it to
 * a wrapped [StrokeRenderer]) can be swapped in at [DrawingCanvasView]'s two construction sites
 * (`startStroke`, `drawShapeOnto`) without touching the frozen dab loop at all.
 */
interface StrokeEngine {
    /** The brush this engine is stamping with -- read by the view for opacity-cap/erase/mix/wetness
     * decisions at commit time (see [StrokeCommit]) and for the live scratch-preview blend mode. */
    val brush: Brush

    /** Stamps the very first dab of the stroke and primes whatever spacing/smoothing state the
     * engine keeps internally. [target] is exactly the canvas the resulting dabs should land on --
     * the engine does not know or care whether that's the shared scratch buffer, a layer bitmap, or
     * a [DabSinkCanvas] wrapping either of those. */
    fun start(target: Canvas, sample: InputSample)

    /** Feeds one more sample (call once per historical + current point, in order). */
    fun moveTo(target: Canvas, sample: InputSample)

    /** Returns (and clears) whatever region has been touched since the last call -- null if
     * nothing new landed. Used to drive [DrawingCanvasView]'s per-frame `invalidate()` region. */
    fun takeDirtyBounds(): RectF?
}
