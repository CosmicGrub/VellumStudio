package com.vellum.studio.canvas

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Smart Shape Assist": a pure-heuristic, zero-ML/zero-CV geometry recognizer over a freehand
 * stroke's own captured point path. No classifier, no bundled model, no network call — this is
 * plain trigonometry against the same [PointF] path [DrawingCanvasView] already has in hand while
 * a stroke is live (see its `shapeAssistPoints` capture), which is exactly what makes this
 * feature free by construction rather than merely "free to run".
 *
 * Deliberately conservative: [recognize] only ever returns a [Candidate] when the fit is
 * genuinely close (see [MIN_CONFIDENCE]), and even then a caller only ever *offers* it (a
 * Snackbar action) rather than applying it automatically — forcing an unwanted snap is worse than
 * missing a real one. An unrecognized scribble simply returns null; this object never throws.
 */
object ShapeAssist {

    sealed class Candidate {
        abstract val confidence: Float
        data class Line(val start: PointF, val end: PointF, override val confidence: Float) : Candidate()
        data class Ellipse(val bounds: RectF, override val confidence: Float) : Candidate()
        /** [corners] are the 4 fitted vertices in path order, NOT an axis-aligned box — a real
         * freehand rectangle is very often drawn at a slight (or not so slight) tilt, and storing
         * only a [RectF] would silently fail to represent that. See [rectangleCandidate]. */
        data class Rectangle(val corners: List<PointF>, override val confidence: Float) : Candidate()
        data class Triangle(val a: PointF, val b: PointF, val c: PointF, override val confidence: Float) : Candidate()
    }

    private const val MIN_CONFIDENCE = 0.72f
    private const val MIN_POINTS = 6

    // Ignores taps/jitter too small to plausibly be an intentional shape, relative to canvas
    // space (the same space brush strokes rasterize into, so this is genuinely pixel-scale, not
    // screen-scale — a stroke this short is a dot or a slip, not a shape, at any zoom level).
    private const val MIN_SPAN_PX = 24f

    /**
     * [points] must already be in canvas (bitmap) space — the same space [StrokeRenderer] stamps
     * into — so a returned candidate's geometry (see [perimeterPoints]) can be redrawn straight
     * onto a layer with no further transform. Returns null on anything not confidently
     * recognized, including a too-short or too-sparse path; never throws.
     */
    fun recognize(points: List<PointF>): Candidate? {
        if (points.size < MIN_POINTS) return null
        val bounds = boundsOf(points)
        if (max(bounds.width(), bounds.height()) < MIN_SPAN_PX) return null

        val diag = hypot(bounds.width().toDouble(), bounds.height().toDouble()).toFloat()
        val simplified = douglasPeucker(points, epsilon = (diag * 0.03f).coerceAtLeast(2f))
        val closed = isClosed(points, diag)

        val candidates = buildList {
            lineCandidate(points)?.let { add(it) }
            if (closed) {
                ellipseCandidate(points, bounds)?.let { add(it) }
                rectangleCandidate(points, simplified)?.let { add(it) }
                triangleCandidate(points, simplified)?.let { add(it) }
            }
        }
        return candidates.filter { it.confidence >= MIN_CONFIDENCE }.maxByOrNull { it.confidence }
    }

    /** Plain-English label for a Snackbar/chip — e.g. "Snap to circle?". */
    fun labelFor(candidate: Candidate): String = when (candidate) {
        is Candidate.Line -> "straight line"
        is Candidate.Ellipse -> if (isNearCircle(candidate.bounds)) "circle" else "ellipse"
        is Candidate.Rectangle -> "rectangle"
        is Candidate.Triangle -> "triangle"
    }

    /**
     * Canvas-space points outlining [candidate]'s clean geometry, ready to feed straight into
     * [StrokeRenderer.start]/[StrokeRenderer.moveTo] in order — see
     * `DrawingCanvasView.applyPendingShapeSnap`. Rectangle/triangle are corners-only (the
     * renderer already draws a straight dab-spaced segment between consecutive samples, so
     * corners alone give a crisp edge); the ellipse is perimeter-sampled since it's a real curve.
     */
    fun perimeterPoints(candidate: Candidate): List<PointF> = when (candidate) {
        is Candidate.Line -> listOf(candidate.start, candidate.end)
        is Candidate.Rectangle -> candidate.corners + candidate.corners.first()
        is Candidate.Triangle -> listOf(candidate.a, candidate.b, candidate.c, candidate.a)
        is Candidate.Ellipse -> {
            val cx = candidate.bounds.centerX()
            val cy = candidate.bounds.centerY()
            val rx = candidate.bounds.width() / 2f
            val ry = candidate.bounds.height() / 2f
            val steps = 72
            (0..steps).map { i ->
                val angle = 2.0 * PI * i / steps
                PointF(cx + (rx * cos(angle)).toFloat(), cy + (ry * sin(angle)).toFloat())
            }
        }
    }

    private fun isNearCircle(bounds: RectF): Boolean {
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0f || h <= 0f) return false
        return min(w, h) / max(w, h) > 0.85f
    }

    private fun boundsOf(points: List<PointF>): RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun isClosed(points: List<PointF>, diag: Float): Boolean {
        if (diag <= 0f) return false
        val first = points.first()
        val last = points.last()
        val gap = hypot((last.x - first.x).toDouble(), (last.y - first.y).toDouble()).toFloat()
        return gap / diag < 0.22f
    }

    private fun dist(a: PointF, b: PointF) = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()

    // ---- Ramer-Douglas-Peucker polyline simplification ----

    private fun douglasPeucker(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size < 3) return points
        var maxDist = -1f
        var index = 0
        val start = points.first()
        val end = points.last()
        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], start, end)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }
        return if (maxDist > epsilon) {
            val left = douglasPeucker(points.subList(0, index + 1), epsilon)
            val right = douglasPeucker(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return dist(p, a)
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot((p.x - projX).toDouble(), (p.y - projY).toDouble()).toFloat()
    }

    /** Like [perpendicularDistance] but to the finite SEGMENT a-b (t clamped to [0,1]) rather than
     * the infinite line through it — what [rectangleCandidate]/[triangleCandidate] need to measure
     * how well the raw path actually hugs a candidate shape's edges, not just their extensions. */
    private fun segmentDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return hypot((p.x - projX).toDouble(), (p.y - projY).toDouble()).toFloat()
    }

    // ---- Line ----

    private fun lineCandidate(points: List<PointF>): Candidate.Line? {
        val start = points.first()
        val end = points.last()
        val lineLen = dist(start, end)
        if (lineLen < MIN_SPAN_PX) return null
        var maxDev = 0f
        for (p in points) {
            val d = perpendicularDistance(p, start, end)
            if (d > maxDev) maxDev = d
        }
        // A confidently straight line keeps every sample within a small fraction of its own
        // length from the start-end chord.
        val ratio = maxDev / lineLen
        val confidence = (1f - ratio / 0.12f).coerceIn(0f, 1f)
        return Candidate.Line(start, end, confidence)
    }

    // ---- Ellipse / circle ----

    private fun ellipseCandidate(points: List<PointF>, bounds: RectF): Candidate.Ellipse? {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val rx = bounds.width() / 2f
        val ry = bounds.height() / 2f
        if (rx < 4f || ry < 4f) return null
        // Normalize each point onto a unit circle by the candidate ellipse's own radii and
        // measure how close the normalized radius stays to 1 — a real circle/ellipse traced
        // freehand keeps this tight; a rectangle or triangle's corners blow it out.
        var errSum = 0f
        for (p in points) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            errSum += abs(hypot(nx.toDouble(), ny.toDouble()).toFloat() - 1f)
        }
        val meanErr = errSum / points.size
        val confidence = (1f - meanErr / 0.22f).coerceIn(0f, 1f)
        return Candidate.Ellipse(RectF(bounds), confidence)
    }

    // ---- Rectangle ----

    /**
     * Deliberately rotation-invariant: an earlier version scored a candidate's 4 dominant corners
     * against the raw path's AXIS-ALIGNED bounding box, which only ever matched a rectangle drawn
     * near-perfectly upright — a real freehand rectangle is very often tilted at least a little,
     * and that version simply never fired for one. [angleScore] (corner angles vs. 90°) is
     * already rotation-invariant on its own; [fitScore] replaces the old bbox comparison with "how
     * closely does the RAW path hug the quad these 4 corners actually form", which works at any
     * rotation and — just as importantly — is what actually rules out a circle or scribble that
     * [dominantVertices] can always reduce down to *some* 4 points (angle alone can't catch that:
     * see [triangleCandidate]'s equivalent fix for a shape where this mattered even more).
     */
    private fun rectangleCandidate(points: List<PointF>, simplified: List<PointF>): Candidate.Rectangle? {
        val corners = dominantVertices(simplified, 4) ?: return null
        val angleScore = rightAngleScore(corners)
        val edges = listOf(
            corners[0] to corners[1], corners[1] to corners[2],
            corners[2] to corners[3], corners[3] to corners[0],
        )
        val fitScore = pathFitScore(points, edges)
        val confidence = angleScore * 0.5f + fitScore * 0.5f
        return Candidate.Rectangle(corners, confidence)
    }

    /** Mean distance from every point in [points] to whichever of [edges] it's nearest to,
     * normalized by the shape's own average edge length and turned into a 0..1 score — a real
     * hand-drawn shape keeps this tight; a different shape (or noise) forced through
     * [dominantVertices] into this many corners does not. Shared by [rectangleCandidate] and
     * [triangleCandidate]. */
    private fun pathFitScore(points: List<PointF>, edges: List<Pair<PointF, PointF>>): Float {
        var errSum = 0f
        for (p in points) {
            var best = Float.MAX_VALUE
            for ((a, b) in edges) {
                val d = segmentDistance(p, a, b)
                if (d < best) best = d
            }
            errSum += best
        }
        val meanErr = errSum / points.size
        val avgEdgeLen = (edges.sumOf { (a, b) -> dist(a, b).toDouble() } / edges.size).toFloat().coerceAtLeast(1f)
        return (1f - (meanErr / avgEdgeLen) / 0.12f).coerceIn(0f, 1f)
    }

    private fun rightAngleScore(corners: List<PointF>): Float {
        val n = corners.size
        var total = 0f
        for (i in corners.indices) {
            val prev = corners[(i - 1 + n) % n]
            val curr = corners[i]
            val next = corners[(i + 1) % n]
            val v1x = prev.x - curr.x
            val v1y = prev.y - curr.y
            val v2x = next.x - curr.x
            val v2y = next.y - curr.y
            val dot = v1x * v2x + v1y * v2y
            val mag = hypot(v1x.toDouble(), v1y.toDouble()).toFloat() * hypot(v2x.toDouble(), v2y.toDouble()).toFloat()
            if (mag < 1e-3f) return 0f
            val cosAngle = (dot / mag).coerceIn(-1f, 1f)
            val angleDeg = Math.toDegrees(acos(cosAngle).toDouble()).toFloat()
            total += abs(angleDeg - 90f)
        }
        val avgDeviation = total / n
        return (1f - avgDeviation / 30f).coerceIn(0f, 1f)
    }

    // ---- Triangle ----

    /**
     * [shapeScore] alone (non-degenerate-area check) is NOT enough on its own: [dominantVertices]
     * can always reduce ANY closed path — a circle, a rectangle, a scribble — down to *some* 3
     * points with real area, so without also checking the raw path against the resulting triangle
     * ([fitScore], via the same [pathFitScore] rectangle uses), triangle detection used to fire on
     * every closed shape rather than just actual triangles.
     */
    private fun triangleCandidate(points: List<PointF>, simplified: List<PointF>): Candidate.Triangle? {
        val corners = dominantVertices(simplified, 3) ?: return null
        val a = corners[0]
        val b = corners[1]
        val c = corners[2]
        val area = abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) / 2f
        val perim = dist(a, b) + dist(b, c) + dist(c, a)
        if (perim < 1f) return null
        // Rejects near-degenerate (collinear-ish) triangles — a real triangle traced freehand has
        // meaningful area relative to its own perimeter. An equilateral triangle's normalized
        // area (area / perimeter^2) is ~0.048; a much lower bar than that still rules out slivers.
        val normalizedArea = area / (perim * perim)
        val shapeScore = (normalizedArea / 0.02f).coerceIn(0f, 1f)
        val fitScore = pathFitScore(points, listOf(a to b, b to c, c to a))
        val confidence = shapeScore * 0.3f + fitScore * 0.7f
        return Candidate.Triangle(a, b, c, confidence)
    }

    /**
     * Reduces an already-simplified CLOSED path down to exactly [n] dominant corners by
     * repeatedly dropping whichever vertex contributes the least perpendicular deviation to its
     * two neighbors — the same idea as Douglas-Peucker, but corner-count-targeted instead of
     * epsilon-targeted. Returns null if the path never had at least [n] distinct vertices.
     */
    private fun dominantVertices(simplified: List<PointF>, n: Int): List<PointF>? {
        // douglasPeucker's input is an open start->...->end path even for a visually "closed"
        // stroke, so its output often repeats the start point as the final point — drop that
        // duplicate before doing ring (wrap-around neighbor) math below.
        var ring = if (simplified.size > 1 && dist(simplified.first(), simplified.last()) < 1e-3f) {
            simplified.dropLast(1)
        } else {
            simplified
        }
        if (ring.size < n) return null
        while (ring.size > n) {
            var minDev = Float.MAX_VALUE
            var dropIndex = 0
            val m = ring.size
            for (i in ring.indices) {
                val prev = ring[(i - 1 + m) % m]
                val curr = ring[i]
                val next = ring[(i + 1) % m]
                val d = perpendicularDistance(curr, prev, next)
                if (d < minDev) {
                    minDev = d
                    dropIndex = i
                }
            }
            ring = ring.filterIndexed { idx, _ -> idx != dropIndex }
        }
        return ring
    }
}
