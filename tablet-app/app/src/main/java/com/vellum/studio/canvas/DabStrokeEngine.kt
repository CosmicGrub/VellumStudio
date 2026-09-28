package com.vellum.studio.canvas

import android.graphics.Canvas
import android.graphics.RectF

/**
 * The default, and today only, [StrokeEngine]: a pure adapter over an UNMODIFIED [StrokeRenderer].
 * Every method is a one-line forward -- this class adds no behavior, no state and no branching of
 * its own, on purpose. Its entire job is to let [DrawingCanvasView] depend on the [StrokeEngine]
 * interface instead of the concrete [StrokeRenderer] type, so the frozen dab loop stays completely
 * untouched (this file, not [StrokeRenderer], is what a diff of this change touches) while still
 * being exactly as fast and byte-identical as constructing a [StrokeRenderer] directly.
 *
 * The secondary constructor mirrors [StrokeRenderer]'s own constructor signature so call sites that
 * used to write `StrokeRenderer(brush, colorArgb, sizeMultiplier, opacityMultiplier)` change to
 * `DabStrokeEngine(brush, colorArgb, sizeMultiplier, opacityMultiplier)` and nothing else.
 */
class DabStrokeEngine(private val renderer: StrokeRenderer) : StrokeEngine {

    constructor(brush: Brush, colorArgb: Int, sizeMultiplier: Float, opacityMultiplier: Float) :
        this(StrokeRenderer(brush, colorArgb, sizeMultiplier, opacityMultiplier))

    override val brush: Brush get() = renderer.brush

    override fun start(target: Canvas, sample: InputSample) = renderer.start(target, sample)

    override fun moveTo(target: Canvas, sample: InputSample) = renderer.moveTo(target, sample)

    override fun takeDirtyBounds(): RectF? = renderer.takeDirtyBounds()
}
