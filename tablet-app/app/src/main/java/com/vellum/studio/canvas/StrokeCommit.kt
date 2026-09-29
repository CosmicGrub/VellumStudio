package com.vellum.studio.canvas

import android.graphics.Canvas

/**
 * The one scratch-routing + commit-flatten decision every stroke draws through, whichever of the
 * two places it starts from: live pointer input ([DrawingCanvasView.endStroke]) or a synthesized
 * geometric path ([DrawingCanvasView.drawShapeOnto], used by Shape Assist's snap-to-shape). Before
 * this object existed those two call sites independently re-derived "does this brush use the
 * scratch buffer" and independently called [CanvasEngine.flattenScratchOnto] with the same four
 * brush-derived arguments -- harmless while there was nothing else to add, but exactly the kind of
 * duplication that would silently drift the moment wet/grain/smudge/stabilization work needed to
 * hook "what happens when a stroke's scratch gets folded onto the layer" and only got added to one
 * of the two copies.
 *
 * This intentionally does NOT touch [StrokeRenderer]/[BrushStampCache] (the frozen dab loop) or
 * change what gets composited -- it is a pure extraction of logic that already existed at both call
 * sites, byte-identical to what each one did before.
 */
object StrokeCommit {

    /**
     * True when [brush] routes through the shared scratch buffer rather than stamping straight onto
     * the layer. Non-buildUp brushes (pencil/ink/pastel/flat-fill/eraser) stamp into scratch so
     * overlapping dabs within one stroke don't double-darken, then commit once via [flatten];
     * buildUp brushes (airbrush/marker/watercolor/highlighter) stamp straight onto the destination so
     * repeated passes visibly accumulate, and have nothing to flatten. See [StrokeRenderer]'s class
     * doc for the full rationale.
     */
    fun usesScratch(brush: Brush): Boolean = !brush.buildUp

    /**
     * The canvas dabs for [brush] should be stamped onto: the engine's shared scratch buffer
     * (freshly cleared by [CanvasEngine.scratch]) for a scratch-routed brush, or a `Canvas` wrapping
     * [layer]'s own bitmap directly for a buildUp brush.
     */
    fun targetCanvas(eng: CanvasEngine, layer: Layer, brush: Brush): Canvas =
        if (usesScratch(brush)) eng.scratch() else Canvas(layer.bitmap)

    /**
     * Folds the scratch buffer onto [layer] at [brush]'s own opacity cap / erase / pigment-mix /
     * wetness settings -- a no-op for a buildUp brush, which already stamped straight onto the layer
     * and has nothing sitting in scratch to flatten. Call exactly once per stroke, after the last
     * dab has been stamped (both [DrawingCanvasView.endStroke] and [DrawingCanvasView.drawShapeOnto]
     * call this as their very last drawing step, before the layer/undo bookkeeping that follows).
     */
    fun flatten(eng: CanvasEngine, layer: Layer, brush: Brush) {
        if (!usesScratch(brush)) return
        eng.flattenScratchOnto(
            layer,
            brush.strokeOpacityCap,
            erasing = brush.category == BrushCategory.ERASER,
            mixing = brush.pigmentMixing,
            wetness = brush.wetness,
        )
    }
}
