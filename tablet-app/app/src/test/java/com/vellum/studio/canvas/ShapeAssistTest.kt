package com.vellum.studio.canvas

import android.app.Application
import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Re-implements, as real JUnit assertions over synthetic point lists built in-test, the same
 * geometry-scoring validation that was originally run informally against [ShapeAssist] via a
 * throwaway Python simulation script: circle/rectangle/triangle/line positives at various
 * rotations and noise levels, plus 5-point-star/pentagon/scribble negatives.
 *
 * Runs under Robolectric (not a plain JVM unit test) because [ShapeAssist]'s public API is built
 * on real `android.graphics.PointF`/`RectF` -- their `width()`/`centerX()`/etc. math is stripped
 * out of AGP's plain "mockable" unit-test android.jar, but present (via Robolectric's real
 * android-all framework bytecode) here. [Application] is used directly rather than this project's
 * own `VellumApp` since none of these tests touch app-level state -- no reason to pull its lazily
 * constructed repositories into a pure-geometry test's setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ShapeAssistTest {

    // ---- synthetic point-cloud generators (mirror the original Python throwaway script) ----

    private fun rotated(p: PointF, cx: Float, cy: Float, deg: Float): PointF {
        if (deg == 0f) return p
        val rad = Math.toRadians(deg.toDouble())
        val dx = (p.x - cx).toDouble()
        val dy = (p.y - cy).toDouble()
        val x = cx + (dx * cos(rad) - dy * sin(rad)).toFloat()
        val y = cy + (dx * sin(rad) + dy * cos(rad)).toFloat()
        return PointF(x, y)
    }

    private fun jitter(rnd: Random, noise: Float): Float =
        if (noise > 0f) rnd.nextFloat() * 2 * noise - noise else 0f

    private fun circlePoints(
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float = rx,
        n: Int = 120,
        noise: Float = 0f,
        seed: Int = 1,
    ): List<PointF> {
        val rnd = Random(seed)
        return (0..n).map { i ->
            val angle = 2.0 * Math.PI * i / n
            PointF(
                cx + (rx * cos(angle)).toFloat() + jitter(rnd, noise),
                cy + (ry * sin(angle)).toFloat() + jitter(rnd, noise),
            )
        }
    }

    private fun rectanglePoints(
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        rotDeg: Float = 0f,
        perEdge: Int = 15,
        noise: Float = 0f,
        seed: Int = 2,
    ): List<PointF> {
        val rnd = Random(seed)
        val hw = w / 2f
        val hh = h / 2f
        val corners = listOf(PointF(-hw, -hh), PointF(hw, -hh), PointF(hw, hh), PointF(-hw, hh))
        val pts = mutableListOf<PointF>()
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            for (t in 0 until perEdge) {
                val f = t.toFloat() / perEdge
                val x = a.x + (b.x - a.x) * f + jitter(rnd, noise)
                val y = a.y + (b.y - a.y) * f + jitter(rnd, noise)
                pts.add(rotated(PointF(cx + x, cy + y), cx, cy, rotDeg))
            }
        }
        pts.add(pts.first())
        return pts
    }

    private fun trianglePoints(
        cx: Float,
        cy: Float,
        size: Float,
        rotDeg: Float = 0f,
        perEdge: Int = 20,
        noise: Float = 0f,
        seed: Int = 3,
    ): List<PointF> {
        val rnd = Random(seed)
        val verts = (0 until 3).map { i ->
            val angle = Math.toRadians((-90 + i * 120).toDouble())
            PointF(cx + (size * cos(angle)).toFloat(), cy + (size * sin(angle)).toFloat())
        }
        val pts = mutableListOf<PointF>()
        for (i in 0 until 3) {
            val a = verts[i]
            val b = verts[(i + 1) % 3]
            for (t in 0 until perEdge) {
                val f = t.toFloat() / perEdge
                val x = a.x + (b.x - a.x) * f + jitter(rnd, noise)
                val y = a.y + (b.y - a.y) * f + jitter(rnd, noise)
                pts.add(rotated(PointF(x, y), cx, cy, rotDeg))
            }
        }
        pts.add(pts.first())
        return pts
    }

    private fun linePoints(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        n: Int = 30,
        noise: Float = 0f,
        seed: Int = 4,
    ): List<PointF> {
        val rnd = Random(seed)
        return (0..n).map { i ->
            val t = i.toFloat() / n
            PointF(x0 + (x1 - x0) * t + jitter(rnd, noise), y0 + (y1 - y0) * t + jitter(rnd, noise))
        }
    }

    private fun starPoints(
        cx: Float,
        cy: Float,
        rOuter: Float,
        rInner: Float,
        points: Int = 5,
        perEdge: Int = 8,
        rotDeg: Float = 0f,
    ): List<PointF> {
        val verts = (0 until points * 2).map { i ->
            val r = if (i % 2 == 0) rOuter else rInner
            val angle = Math.toRadians(-90.0 + i * (360.0 / (points * 2)))
            PointF(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat())
        }
        val pts = mutableListOf<PointF>()
        val n = verts.size
        for (i in 0 until n) {
            val a = verts[i]
            val b = verts[(i + 1) % n]
            for (t in 0 until perEdge) {
                val f = t.toFloat() / perEdge
                val x = a.x + (b.x - a.x) * f
                val y = a.y + (b.y - a.y) * f
                pts.add(rotated(PointF(x, y), cx, cy, rotDeg))
            }
        }
        pts.add(pts.first())
        return pts
    }

    private fun pentagonPoints(
        cx: Float,
        cy: Float,
        r: Float,
        perEdge: Int = 15,
        rotDeg: Float = 0f,
    ): List<PointF> {
        val verts = (0 until 5).map { i ->
            val angle = Math.toRadians((-90 + i * 72).toDouble())
            PointF(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat())
        }
        val pts = mutableListOf<PointF>()
        for (i in 0 until 5) {
            val a = verts[i]
            val b = verts[(i + 1) % 5]
            for (t in 0 until perEdge) {
                val f = t.toFloat() / perEdge
                val x = a.x + (b.x - a.x) * f
                val y = a.y + (b.y - a.y) * f
                pts.add(rotated(PointF(x, y), cx, cy, rotDeg))
            }
        }
        pts.add(pts.first())
        return pts
    }

    private fun scribblePoints(cx: Float, cy: Float, span: Float, n: Int = 80, seed: Int = 7): List<PointF> {
        val rnd = Random(seed)
        var x = cx
        var y = cy
        val pts = mutableListOf<PointF>()
        repeat(n) {
            x += rnd.nextFloat() * (span / 3f) - span / 6f
            y += rnd.nextFloat() * (span / 3f) - span / 6f
            pts.add(PointF(x, y))
        }
        return pts
    }

    private inline fun <reified T : ShapeAssist.Candidate> expectType(candidate: ShapeAssist.Candidate?): T {
        assertNotNull("expected a ${T::class.simpleName} candidate, got null", candidate)
        assertTrue("expected a ${T::class.simpleName} candidate, got $candidate", candidate is T)
        return candidate as T
    }

    // ---- positive: circle / ellipse ----

    @Test fun `circle recognized with no noise`() {
        val ellipse = expectType<ShapeAssist.Candidate.Ellipse>(
            ShapeAssist.recognize(circlePoints(200f, 200f, 80f)),
        )
        assertEquals("circle", ShapeAssist.labelFor(ellipse))
    }

    @Test fun `circle recognized with moderate noise`() {
        val ellipse = expectType<ShapeAssist.Candidate.Ellipse>(
            ShapeAssist.recognize(circlePoints(200f, 200f, 80f, noise = 2f)),
        )
        assertTrue(ellipse.confidence >= 0.72f)
    }

    @Test fun `wide ellipse recognized and labeled ellipse not circle`() {
        val ellipse = expectType<ShapeAssist.Candidate.Ellipse>(
            ShapeAssist.recognize(circlePoints(200f, 200f, rx = 100f, ry = 60f)),
        )
        assertEquals("ellipse", ShapeAssist.labelFor(ellipse))
    }

    // ---- positive: rectangle, at rotations + noise ----

    @Test fun `rectangle recognized at various rotations`() {
        for (rot in listOf(0f, 15f, 30f, 45f, 60f, 75f, 89f)) {
            val rectangle = expectType<ShapeAssist.Candidate.Rectangle>(
                ShapeAssist.recognize(rectanglePoints(200f, 200f, 160f, 100f, rotDeg = rot)),
            )
            assertTrue("rotation $rot confidence too low: ${rectangle.confidence}", rectangle.confidence >= 0.72f)
        }
    }

    @Test fun `rectangle recognized with noise`() {
        val rectangle = expectType<ShapeAssist.Candidate.Rectangle>(
            ShapeAssist.recognize(rectanglePoints(200f, 200f, 160f, 100f, rotDeg = 30f, noise = 2f)),
        )
        assertTrue(rectangle.confidence >= 0.72f)
    }

    // ---- positive: triangle, at rotations + noise ----

    @Test fun `triangle recognized at various rotations`() {
        for (rot in listOf(0f, 20f, 45f, 90f, 150f)) {
            val triangle = expectType<ShapeAssist.Candidate.Triangle>(
                ShapeAssist.recognize(trianglePoints(200f, 200f, 100f, rotDeg = rot)),
            )
            assertTrue("rotation $rot confidence too low: ${triangle.confidence}", triangle.confidence >= 0.72f)
        }
    }

    @Test fun `triangle recognized with noise`() {
        val triangle = expectType<ShapeAssist.Candidate.Triangle>(
            ShapeAssist.recognize(trianglePoints(200f, 200f, 100f, rotDeg = 30f, noise = 2f)),
        )
        assertTrue(triangle.confidence >= 0.72f)
    }

    // ---- positive: line ----

    @Test fun `straight line recognized`() {
        expectType<ShapeAssist.Candidate.Line>(ShapeAssist.recognize(linePoints(50f, 50f, 250f, 250f)))
    }

    @Test fun `near straight line recognized with light noise`() {
        val line = expectType<ShapeAssist.Candidate.Line>(
            ShapeAssist.recognize(linePoints(50f, 200f, 250f, 205f, noise = 1f)),
        )
        assertTrue(line.confidence >= 0.72f)
    }

    // ---- negative cases ----

    @Test fun `5-point star is rejected at every rotation`() {
        for (rot in listOf(0f, 20f, 40f)) {
            val candidate = ShapeAssist.recognize(starPoints(200f, 200f, 90f, 35f, rotDeg = rot))
            assertNull("star at rotation $rot should not be recognized, got $candidate", candidate)
        }
    }

    @Test fun `pentagon is rejected at every rotation`() {
        for (rot in listOf(0f, 20f, 40f)) {
            val candidate = ShapeAssist.recognize(pentagonPoints(200f, 200f, 90f, rotDeg = rot))
            assertNull("pentagon at rotation $rot should not be recognized, got $candidate", candidate)
        }
    }

    @Test fun `random scribble is rejected`() {
        for (seed in listOf(1, 2, 3)) {
            val candidate = ShapeAssist.recognize(scribblePoints(200f, 200f, 150f, seed = seed))
            assertNull("scribble seed $seed should not be recognized, got $candidate", candidate)
        }
    }

    @Test fun `too few points returns null`() {
        val points = listOf(PointF(0f, 0f), PointF(10f, 10f), PointF(20f, 0f))
        assertNull(ShapeAssist.recognize(points))
    }

    @Test fun `too small a span returns null`() {
        // A tap/jitter well under MIN_SPAN_PX -- not an intentional shape.
        val points = (0..10).map { PointF(100f + it * 0.5f, 100f + it * 0.3f) }
        assertNull(ShapeAssist.recognize(points))
    }
}
