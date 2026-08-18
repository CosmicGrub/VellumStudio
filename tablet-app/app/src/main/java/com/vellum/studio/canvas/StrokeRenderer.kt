package com.vellum.studio.canvas

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.random.Random

/** One live pointer sample, already transformed into canvas (bitmap) space. */
data class InputSample(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltRadians: Float = 0f,
    val orientationRadians: Float = 0f,
)

/**
 * Walks incoming [InputSample]s for a single stroke, stamping a cached soft-edged "brush tip"
 * texture (see [BrushStampCache]) spaced along the path at a fraction of the current dab diameter.
 * Keeps a running distance accumulator so spacing stays consistent regardless of how far apart
 * consecutive touch samples land.
 *
 * Each dab is one `drawBitmap(stamp, matrix, paint)` call with zero allocation — no shader is built
 * per dab, and the interpolated per-dab position/pressure/tilt/orientation are passed straight to
 * [stampAt] as plain float parameters rather than boxed into a freshly-allocated `InputSample` per
 * dab. A fast stroke can lay down hundreds of dabs; building a new native-backed `RadialGradient`
 * for every one (the original design) — or even just a small `data class` copy per dab — is exactly
 * the kind of per-frame allocation churn that shows up as stutter/dropped frames under a moving S Pen.
 *
 * Non-buildUp brushes (pencil/ink/eraser) stamp into [strokeScratch] so overlapping dabs within one
 * stroke don't double-darken; the caller composites the scratch onto the real layer once, at stroke
 * end, capped at [Brush.strokeOpacityCap]. BuildUp brushes (airbrush/marker) stamp straight onto the
 * destination canvas so repeated passes visibly accumulate.
 *
 * Erasers are just another dab stamp into the scratch (always opaque white, RGB is irrelevant) —
 * the caller composites that scratch onto the layer with `DST_OUT` instead of `SRC_OVER`, so the
 * eraser's [Brush.hardness] produces a genuinely soft falloff at the edges.
 */
class StrokeRenderer(val brush: Brush, private val colorArgb: Int, private val sizeMultiplier: Float, private val opacityMultiplier: Float) {

    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stampMatrix = Matrix()
    private val stampBitmap = BrushStampCache.stampFor(brush)
    private val stampHalfTexture = stampBitmap.width / 2f

    private var havePrev = false
    private var prevX = 0f
    private var prevY = 0f
    private var prevPressure = 0f
    private var prevTilt = 0f
    private var prevOrientation = 0f
    private var pendingDistance = 0f

    // Region touched since the last takeDirtyBounds() call — NOT the whole stroke's lifetime bounds.
    // Accumulating for the stroke's full lifetime (the original bug) means the invalidated — and
    // therefore redrawn — region keeps growing as a stroke gets longer, so later dabs in a long
    // stroke cost more to render than earlier ones. Resetting on every consume keeps each frame's
    // redraw proportional to what actually changed since the last frame.
    private val dirtyBounds = RectF()
    private var boundsTouched = false

    /** Stamps the very first dab of the stroke and primes the spacing accumulator. */
    fun start(target: Canvas, sample: InputSample) {
        stampAt(target, sample.x, sample.y, sample.pressure, sample.tiltRadians, sample.orientationRadians)
        prevX = sample.x
        prevY = sample.y
        prevPressure = sample.pressure
        prevTilt = sample.tiltRadians
        prevOrientation = sample.orientationRadians
        havePrev = true
        pendingDistance = 0f
    }

    /** Feeds one more sample (call once per historical + current point, in order). */
    fun moveTo(target: Canvas, sample: InputSample) {
        if (!havePrev) {
            start(target, sample)
            return
        }
        val dx = sample.x - prevX
        val dy = sample.y - prevY
        val segLen = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (segLen < 0.0001f) return

        // Spacing is derived from the segment's MIDPOINT pressure, not just its endpoint, so it
        // stays reasonably matched to the diameter of the dabs actually placed along this segment
        // even during a fast press-down/lift-off ramp - stampAt() correctly recomputes each dab's
        // own diameter from its own interpolated pressure; using only the endpoint pressure here
        // would spread the earliest (smallest, ramping-up) dabs in a segment much farther apart,
        // relative to their own size, than the brush's spacing setting actually calls for.
        val midPressure = (prevPressure + sample.pressure) * 0.5f
        val diameter = dabDiameter(midPressure)
        val spacingPx = (diameter * brush.spacing).coerceAtLeast(0.75f)

        // Guard against a runaway dab count in one call: segLen is in canvas space and scales with
        // 1/zoom (DrawingCanvasView allows zooming out to 5%), while spacingPx has a small fixed
        // floor - without this, a single ACTION_MOVE at low zoom could synchronously stamp thousands
        // of dabs on the UI thread, stalling a frame or risking an ANR. Widening the effective
        // spacing for just this one segment (never below the brush's own spacingPx) keeps dab
        // density correct at normal zoom and only kicks in for genuinely extreme segments; the next
        // call's pendingDistance naturally reverts to normal spacing once segments are sane again.
        val effectiveSpacingPx = spacingPx.coerceAtLeast(segLen / MAX_DABS_PER_SEGMENT)

        val dPressure = sample.pressure - prevPressure
        val dTilt = sample.tiltRadians - prevTilt
        // Orientation is an angle, not a plain scalar - interpolating a raw linear delta breaks the
        // instant two consecutive samples straddle the +pi/-pi seam (e.g. +3.10 rad then -3.10 rad,
        // a real S Pen azimuth that only actually moved ~0.08 rad the short way): a naive delta of
        // -6.20 rad sweeps the interpolated stamps through ~180 degrees of spurious rotation instead
        // of the tiny true movement. Wrapping the delta into (-pi, pi] always takes the short path.
        val dOrientation = wrapAngle(sample.orientationRadians - prevOrientation)

        // carryIn: distance already banked past the last stamped dab, carried over from the
        // previous segment - the first dab in THIS segment belongs at (effectiveSpacingPx - carryIn)
        // from the segment's start, not a full effectiveSpacingPx from it. Starting `travelled` at
        // -carryIn (instead of 0) makes the loop's first increment land there correctly; omitting
        // this silently produced uneven, clustered-then-gapped dab spacing at every segment boundary
        // for the rest of any multi-sample stroke (which is most real S Pen strokes).
        val carryIn = pendingDistance
        pendingDistance += segLen
        var travelled = -carryIn
        while (pendingDistance >= effectiveSpacingPx) {
            travelled += effectiveSpacingPx
            pendingDistance -= effectiveSpacingPx
            val t = (travelled / segLen).coerceIn(0f, 1f)
            // Pressure/tilt/orientation are interpolated across the segment, not frozen at its
            // endpoint - a segment needing several dabs (a thin/fast brush, or any brush at low
            // zoom where one raw sample spans a lot of canvas-space distance) would otherwise jump
            // straight to the new value only on the LAST dab, reading as a visibly stepped rather
            // than smooth pressure/tilt ramp.
            stampAt(
                target,
                prevX + dx * t,
                prevY + dy * t,
                prevPressure + dPressure * t,
                prevTilt + dTilt * t,
                prevOrientation + dOrientation * t,
            )
        }
        prevX = sample.x
        prevY = sample.y
        prevPressure = sample.pressure
        prevTilt = sample.tiltRadians
        prevOrientation = sample.orientationRadians
    }

    /** Returns (and clears) whatever's been touched since the last call — null if nothing new. */
    fun takeDirtyBounds(): RectF? {
        if (!boundsTouched) return null
        val result = RectF(dirtyBounds)
        boundsTouched = false
        dirtyBounds.setEmpty()
        return result
    }

    private fun dabDiameter(pressure: Float): Float {
        val pressureSizeFactor = brush.minSizeFactor + (1f - brush.minSizeFactor) * pressure.coerceIn(0f, 1f)
        val sizeFactor = lerp(1f, pressureSizeFactor, brush.pressureToSize)
        return (brush.baseSizePx * sizeFactor * sizeMultiplier).coerceAtLeast(1f)
    }

    private fun stampAt(target: Canvas, x: Float, y: Float, pressure: Float, tiltRadians: Float, orientationRadians: Float) {
        val diameter = dabDiameter(pressure)
        val radius = diameter.coerceAtLeast(1f) / 2f

        val pressureOpacityFactor = 0.35f + 0.65f * pressure.coerceIn(0f, 1f)
        val opacityFactor = lerp(1f, pressureOpacityFactor, brush.pressureToOpacity)
        var stampAlpha = (brush.baseOpacity * opacityFactor * opacityMultiplier).coerceIn(0f, 1f)
        if (brush.opacityJitter > 0f) {
            // Grainy, non-uniform coverage — real chalk/pastel and a loaded watercolor brush never
            // lay down perfectly flat opacity the way a hard ink tip does.
            stampAlpha *= (1f - brush.opacityJitter * Random.nextFloat()).coerceIn(0f, 1f)
        }

        // A tilted nib doesn't just foreshorten into a thinner ellipse - more of it lays flat
        // against the page, so the stamp's footprint genuinely GROWS along the tilt direction as
        // well as narrowing across it, the way pencil shading or a chisel marker tip actually
        // behaves (this is what every brush's tiltToSize doc comment already promises - "shades
        // broader", "genuinely widens", "the width swing... bigger" - a plain squash-only ellipse
        // can never deliver that, since it can only ever shrink, never grow, the stamp's footprint).
        val tiltNorm = (tiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val widen = 1f + (brush.tiltToSize * tiltNorm * 0.9f).coerceIn(0f, 1.6f)
        val squash = 1f - (brush.tiltToSize * tiltNorm * 0.35f).coerceIn(0f, 0.6f)

        var jx = 0f
        var jy = 0f
        if (brush.jitter > 0f) {
            val j = diameter * brush.jitter * 0.3f
            jx = (Random.nextFloat() - 0.5f) * j
            jy = (Random.nextFloat() - 0.5f) * j
        }
        val cx = x + jx
        val cy = y + jy

        // Eraser dabs paint into a mask (composited via DST_OUT at stroke end, see class doc), so the
        // RGB channel is irrelevant here — only the alpha falloff baked into the stamp texture matters.
        dabPaint.color = if (brush.category == BrushCategory.ERASER) -0x1 else colorArgb
        dabPaint.alpha = (stampAlpha * 255).toInt().coerceIn(0, 255)
        // MULTIPLY only belongs here for buildUp brushes (Watercolor), whose dabs land straight on
        // the real layer and are meant to keep darkening against whatever's really underneath. A
        // non-buildUp pigment-mixing brush (Pastel) routes through the shared scratch buffer instead
        // (see class doc), so applying MULTIPLY per-dab here would multiply the stroke against
        // ITSELF as overlapping dabs accumulate - MULTIPLY has no floor the way SRC_OVER does, so a
        // dense stroke (Pastel's spacing=0.1 means ~90% dab overlap) visibly darkens/muddies as you
        // draw instead of laying down a flat tone. For those brushes, mixing with the real canvas
        // happens exactly once, at CanvasEngine.flattenScratchOnto's stroke-end commit - not here.
        dabPaint.blendMode = if (brush.pigmentMixing && brush.buildUp) BlendMode.MULTIPLY else null

        val baseScale = radius / stampHalfTexture
        val scaleX = baseScale * widen
        val scaleY = baseScale * squash
        stampMatrix.reset()
        stampMatrix.postTranslate(-stampHalfTexture, -stampHalfTexture)
        stampMatrix.postScale(scaleX, scaleY)
        if (orientationRadians != 0f) {
            stampMatrix.postRotate(Math.toDegrees(orientationRadians.toDouble()).toFloat())
        }
        stampMatrix.postTranslate(cx, cy)
        target.drawBitmap(stampBitmap, stampMatrix, dabPaint)

        // The dab's on-screen footprint is now elongated (up to +60% radius) along the tilt axis,
        // not just the base radius - grow the dirty rect by the wider dimension so a heavily tilted
        // stroke's redraw region actually covers what was drawn instead of clipping its own edge.
        val boundsRadius = radius * widen
        growBounds(cx - boundsRadius, cy - boundsRadius, cx + boundsRadius, cy + boundsRadius)
    }

    private fun growBounds(l: Float, t: Float, r: Float, b: Float) {
        if (!boundsTouched) {
            dirtyBounds.set(l, t, r, b)
            boundsTouched = true
        } else {
            dirtyBounds.union(l, t, r, b)
        }
    }

    companion object {
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private const val MAX_DABS_PER_SEGMENT = 256f
        private val TWO_PI = (2.0 * PI).toFloat()

        /** Wraps an angular delta into (-pi, pi] so interpolating it always takes the short way around. */
        private fun wrapAngle(deltaRadians: Float): Float {
            var d = deltaRadians % TWO_PI
            if (d > PI.toFloat()) d -= TWO_PI
            if (d < -PI.toFloat()) d += TWO_PI
            return d
        }
    }
}
