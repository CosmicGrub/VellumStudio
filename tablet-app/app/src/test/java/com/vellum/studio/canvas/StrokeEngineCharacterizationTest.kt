package com.vellum.studio.canvas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Characterization tests for the frozen [StrokeRenderer] dab loop, pinning the behavior every future
 * wrapper (stabilization, grain, smudge, wet edges) is built on top of -- written FIRST, against the
 * current (pre- and post-seam) code, per this item's acceptance criteria. Nothing here exercises
 * [StrokeRenderer] directly by class name; everything goes through [DabStrokeEngine] (the
 * [StrokeEngine] seam) and [DabSinkCanvas] (to observe individual dabs), which is exactly how
 * [DrawingCanvasView] drives it post-refactor -- so a regression in either wrapper would fail here
 * too, not just a regression in the frozen loop itself.
 *
 * Restricted to jitter-free brushes (InkPen, Fineliner: `jitter = opacityJitter = 0` by default) --
 * [StrokeRenderer] draws its per-dab jitter from the unseedable global `kotlin.random.Random`, which
 * this suite does not touch or rely on (see [DrawingCanvasViewInputRoutingTest]'s class doc for the
 * same constraint applied to the input-routing net).
 *
 * `@GraphicsMode(NATIVE)` only where a test actually rasterizes; most of these only need the dab
 * COUNT / geometry [DabSinkCanvas] reports, which works under the legacy shadow too, but NATIVE is
 * used throughout for consistency with the rest of the canvas test package and so a bitmap-backed
 * `Canvas` is always safe to construct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StrokeEngineCharacterizationTest {

    private val size = 512

    private fun bitmap(): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    private fun engineFor(brush: Brush) = DabStrokeEngine(brush, Color.BLACK, 1f, 1f)

    /** Records every dab's matrix without altering what actually gets drawn (forwards to super). */
    private class RecordingDabSinkCanvas(bitmap: Bitmap) : DabSinkCanvas(bitmap) {
        val matrices = mutableListOf<Matrix>()

        override fun onDab(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            matrices.add(Matrix(matrix))
            super.onDab(bitmap, matrix, paint)
        }
    }

    // ---------------------------------------------------------------- dab count carry-in

    /**
     * pendingDistance (see StrokeRenderer.moveTo's "carryIn" doc comment) must carry over correctly
     * between segments: feeding the SAME total straight-line displacement as one long segment versus
     * several shorter collinear ones must stamp exactly the same number of dabs. Getting carry-in
     * wrong (the historical bug the comment describes) clusters-then-gaps dabs at every extra segment
     * boundary WITHOUT necessarily changing the total count in an obvious way, so this also cross-
     * checks against the closed-form expected count as a second, independent signal.
     *
     * Total displacement (150px) and brush (InkPen, spacing 0.1 * 8px diameter at full pressure =
     * 0.8px pitch) are chosen so MAX_DABS_PER_SEGMENT's per-call floor (segLen / 256) never exceeds
     * the brush's own spacing for either the single 150px segment or any of the three 50px ones --
     * otherwise the guard would legitimately produce different per-segment spacing for the two
     * chunkings and this test would no longer be isolating carry-in.
     */
    @Test fun `dab count is identical whether a stroke is fed as one long segment or split into several`() {
        val brush = BrushPresets.InkPen
        val totalDx = 150f
        val totalDy = 0f

        val singleSegmentSink = CountingDabSinkCanvas(bitmap())
        val single = engineFor(brush)
        single.start(singleSegmentSink, InputSample(0f, 0f, pressure = 1f))
        single.moveTo(singleSegmentSink, InputSample(totalDx, totalDy, pressure = 1f))

        val splitSink = CountingDabSinkCanvas(bitmap())
        val split = engineFor(brush)
        split.start(splitSink, InputSample(0f, 0f, pressure = 1f))
        split.moveTo(splitSink, InputSample(totalDx / 3f, totalDy / 3f, pressure = 1f))
        split.moveTo(splitSink, InputSample(2f * totalDx / 3f, 2f * totalDy / 3f, pressure = 1f))
        split.moveTo(splitSink, InputSample(totalDx, totalDy, pressure = 1f))

        assertEquals(
            "splitting the same straight-line stroke into 3 segments must not change the dab count",
            singleSegmentSink.dabCount,
            splitSink.dabCount,
        )

        // Independent cross-check: at full, constant pressure, diameter and spacingPx are constant
        // (dabDiameter(1f) = baseSizePx; spacingPx = (diameter * brush.spacing).coerceAtLeast(0.75f)),
        // so total dabs over a straight run of totalDx should land within one dab of
        // totalDx / spacingPx (the start() dab plus however many full spacingPx pitches fit).
        val diameter = brush.baseSizePx // pressure = 1f -> sizeFactor = 1f, sizeMultiplier = 1f
        val spacingPx = (diameter * brush.spacing).coerceAtLeast(0.75f)
        val expected = 1 + (totalDx / spacingPx).toInt()
        assertTrue(
            "expected dab count near $expected (spacingPx=$spacingPx), got ${singleSegmentSink.dabCount}",
            abs(singleSegmentSink.dabCount - expected) <= 1,
        )
    }

    // ---------------------------------------------------------------- MAX_DABS_PER_SEGMENT bound

    /**
     * A single call must never stamp an unbounded number of dabs no matter how long the segment or
     * how small the brush's own spacing -- StrokeRenderer's `effectiveSpacingPx` guard (widening
     * spacing to at least `segLen / MAX_DABS_PER_SEGMENT`) exists specifically to keep one
     * synchronous `moveTo` call from stalling a frame. MAX_DABS_PER_SEGMENT is 256 in the frozen file
     * (StrokeRenderer.kt); this test does not import that private constant (it can't -- it's
     * `private const val` in the class's companion object) but instead proves the OBSERVABLE contract
     * the constant exists to guarantee: a segment orders of magnitude longer than the brush's natural
     * spacing still produces, at most, a small bounded number of dabs.
     */
    @Test fun `one segment never stamps more than a small bounded number of dabs regardless of length`() {
        val brush = BrushPresets.Fineliner // spacingPx ~= 4px * 0.08 = 0.32px -- a very dense brush
        val sink = CountingDabSinkCanvas(bitmap())
        val stroke = engineFor(brush)
        stroke.start(sink, InputSample(0f, 0f, pressure = 1f))
        // Without the guard this would demand roughly 100_000 / 0.32 ~= 312_500 dabs from one call.
        stroke.moveTo(sink, InputSample(100_000f, 0f, pressure = 1f))

        assertTrue(
            "expected the MAX_DABS_PER_SEGMENT guard to bound one call's dab count well under 1000, got ${sink.dabCount}",
            sink.dabCount in 1..1000,
        )
    }

    // ---------------------------------------------------------------- orientation +/-pi seam

    /**
     * Two consecutive samples whose orientation straddles the +pi/-pi seam (a real S Pen azimuth that
     * only actually moved a few degrees the short way, e.g. +3.10 rad then -3.10 rad) must interpolate
     * through that short path, not sweep through ~180 degrees of spurious rotation the naive linear
     * delta would produce. `atan2` naturally re-wraps a rotation past +/-pi back into (-pi, pi], which
     * would make even a CORRECT short-path interpolation look like it jumps in the raw per-dab
     * readings -- so this test un-wraps the recorded rotations first (same technique
     * [StrokeRenderer.wrapAngle] itself uses, applied here to the OBSERVED angles) and asserts the
     * true, continuous angular distance swept across the whole segment is small.
     */
    @Test fun `orientation interpolation across the +pi -pi seam takes the short path`() {
        val brush = BrushPresets.InkPen
        val sink = RecordingDabSinkCanvas(bitmap())
        val stroke = engineFor(brush)
        val nearPositivePi = 3.10f
        val nearNegativePi = -3.10f
        // tiltRadians = 0 keeps the per-dab scale uniform (widen == squash == 1), so the matrix's
        // linear part is a pure rotation-by-orientation times a scalar -- rotationOf() below can then
        // recover the exact orientation angle from the matrix with no tilt-shape ambiguity.
        stroke.start(sink, InputSample(50f, 50f, pressure = 1f, tiltRadians = 0f, orientationRadians = nearPositivePi))
        stroke.moveTo(sink, InputSample(60f, 50f, pressure = 1f, tiltRadians = 0f, orientationRadians = nearNegativePi))

        assertTrue("need multiple dabs across the segment to observe interpolation", sink.matrices.size >= 4)

        val rawAngles = sink.matrices.map { rotationOf(it) }
        val unwrapped = unwrap(rawAngles)
        val totalSweep = abs(unwrapped.last() - unwrapped.first())

        // The true short-path delta here is |wrapAngle(-3.10 - 3.10)| ~= 0.083 rad (~4.8 degrees).
        // A regression that reintroduced the naive linear delta would sweep ~6.2 rad (~355 degrees)
        // instead -- this threshold sits comfortably between the two, well above float slop and well
        // below what an un-wrapped naive delta would produce.
        assertTrue(
            "expected a short ~0.08rad sweep across the +pi/-pi seam, got ${totalSweep}rad (raw=$rawAngles)",
            totalSweep < 0.5f,
        )
    }

    /** The rotation angle a dab's matrix applies, recovered from its linear part (translation is
     * irrelevant to a direction vector) by mapping the unit vector (1,0) and reading its angle. Valid
     * whenever the matrix's scale is uniform (no tilt-driven widen/squash asymmetry), which is why the
     * orientation-seam test above fixes tiltRadians = 0. */
    private fun rotationOf(matrix: Matrix): Float {
        val vec = floatArrayOf(1f, 0f)
        matrix.mapVectors(vec)
        return atan2(vec[1].toDouble(), vec[0].toDouble()).toFloat()
    }

    /** Removes atan2's artificial +/-pi wrap from a sequence of angle readings, so consecutive values
     * that are actually close together (a real short rotation that merely crosses the seam) stay
     * close together numerically instead of jumping by ~2pi. */
    private fun unwrap(raw: List<Float>): List<Float> {
        val out = mutableListOf(raw[0])
        val twoPi = (2.0 * PI).toFloat()
        for (i in 1 until raw.size) {
            var d = raw[i] - raw[i - 1]
            while (d > PI.toFloat()) d -= twoPi
            while (d < -PI.toFloat()) d += twoPi
            out.add(out.last() + d)
        }
        return out
    }

    // ---------------------------------------------------------------- symmetry mirroring

    /** [SymmetryMode.copyCount] (how many total copies -- including the original -- a mode draws per
     * dab) must always equal `mirrorTransforms().size + 1` (the additional copies [mirrorTransforms]
     * returns, plus the original DrawingCanvasView stamps itself). [DrawingCanvasView.startStroke]
     * builds exactly one [DabStrokeEngine] per entry in `mirrorTransforms()` on top of the real
     * stroke's own renderer -- a mismatch here would mean either a mirrored copy silently missing or a
     * phantom extra one, for every mode at once. */
    @Test fun `SymmetryMode copyCount always equals mirrorTransforms size plus the original`() {
        for (mode in SymmetryMode.entries) {
            val transforms = mode.mirrorTransforms(256f, 256f)
            assertEquals(mode.name, mode.copyCount, transforms.size + 1)
        }
    }

    // ---------------------------------------------------------------- buildUp vs scratch-routed

    /** A buildUp brush (e.g. FeltMarker) stamps every dab straight onto whatever canvas it's given,
     * with no offscreen scratch involved at the dab-loop level at all -- [StrokeEngine]/
     * [DabStrokeEngine] never reference scratch; that routing decision is entirely the CALLER's
     * (see [StrokeCommit], tested separately). This test pins that a buildUp brush's dabs simply
     * accumulate directly wherever `target` points, which is what makes "stamp straight onto the
     * destination so repeated passes visibly accumulate" (BuildUp's contract) true regardless of
     * which canvas the caller chose. */
    @Test fun `a buildUp brush stamps directly onto whatever canvas it is given, with no scratch indirection`() {
        val brush = BrushPresets.FeltMarker
        val bmp = bitmap()
        val canvas = android.graphics.Canvas(bmp)
        val stroke = engineFor(brush)
        stroke.start(canvas, InputSample(size / 2f, size / 2f, pressure = 1f))
        assertTrue(
            "a buildUp brush's very first dab should already be visible on the canvas it was given",
            bmp.getPixel(size / 2, size / 2) != Color.TRANSPARENT,
        )
    }

    private class CountingDabSinkCanvas(bitmap: Bitmap) : DabSinkCanvas(bitmap) {
        var dabCount = 0
            private set

        override fun onDab(bitmap: Bitmap, matrix: Matrix, paint: Paint?) {
            dabCount++
            super.onDab(bitmap, matrix, paint)
        }
    }
}
