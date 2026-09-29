package com.vellum.studio.canvas

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [FloodFillTool] had no test at all, yet it is the paint-bucket AND the paint-by-number commit and it
 * is where a leak ("the fill flooded the whole canvas") or a double-composite ("a seam of darker
 * pixels") shows up. Pure pixel logic, so a plain Robolectric app is enough -- no [com.vellum.studio.VellumApp].
 * `@GraphicsMode(NATIVE)` gives real ARGB_8888 storage, so the getPixel/setPixel values under test are
 * what the device stores (legacy shadow bitmaps do not reliably round-trip pixels).
 *
 * The convention under test: the fill is bounded ONLY by the alpha of the separate `boundary` bitmap
 * (the composite of the layers above the target -- see CanvasEngine.boundaryMaskAbove); what is already
 * on the target layer never blocks it. Connectivity is 4-way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloodFillToolTest {

    private val w = 40
    private val h = 30
    private val wall = Color.BLACK
    private val red = 0xFFFF0000.toInt()

    private fun blank(width: Int = w, height: Int = h): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** A hollow rectangle wall with corners (x0,y0)-(x1,y1) inclusive, [thickness] px thick. */
    private fun box(boundary: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, thickness: Int = 1) {
        for (x in x0..x1) for (y in y0..y1) {
            val onEdge = x - x0 < thickness || x1 - x < thickness || y - y0 < thickness || y1 - y < thickness
            if (onEdge) boundary.setPixel(x, y, wall)
        }
    }

    private fun fill(
        target: Bitmap, boundary: Bitmap, x: Int, y: Int,
        color: Int = red, alpha: Float = 1f, threshold: Int = 40,
    ) = FloodFillTool.fill(target, boundary, x, y, color, alpha, threshold)

    private fun countFilled(target: Bitmap): Int {
        var n = 0
        for (y in 0 until target.height) for (x in 0 until target.width) if (Color.alpha(target.getPixel(x, y)) != 0) n++
        return n
    }

    @Test fun `a closed box fills exactly its interior and nothing else`() {
        val target = blank(); val boundary = blank()
        box(boundary, 10, 5, 25, 20)

        assertTrue(fill(target, boundary, 15, 10))

        // Interior of a 1px-thick box spanning 10..25 x 5..20 is 11..24 x 6..19.
        val interior = (24 - 11 + 1) * (19 - 6 + 1)
        assertEquals(interior, countFilled(target))
        assertEquals(red, target.getPixel(11, 6))
        assertEquals(red, target.getPixel(24, 19))
        assertEquals("the wall itself is never painted", 0, Color.alpha(target.getPixel(10, 5)))
        assertEquals("outside the wall is untouched", 0, Color.alpha(target.getPixel(2, 2)))
    }

    @Test fun `a one pixel gap leaks the fill to the whole canvas`() {
        val target = blank(); val boundary = blank()
        box(boundary, 10, 5, 25, 20)
        boundary.setPixel(25, 12, Color.TRANSPARENT) // a hole in the right wall

        assertTrue(fill(target, boundary, 15, 10))

        val wallPixels = countWalls(boundary)
        assertEquals("with a gap everything but the wall is reachable", w * h - wallPixels, countFilled(target))
        assertEquals("the fill escaped through the gap", red, target.getPixel(2, 2))
    }

    private fun countWalls(boundary: Bitmap): Int {
        var n = 0
        for (y in 0 until boundary.height) for (x in 0 until boundary.width) if (Color.alpha(boundary.getPixel(x, y)) >= 40) n++
        return n
    }

    @Test fun `a diagonal wall closes the region because connectivity is four-way`() {
        val target = blank(20, 20); val boundary = blank(20, 20)
        // A 1px diagonal from the top edge to the left edge, pixels touching only at corners.
        for (i in 0 until 10) boundary.setPixel(9 - i, i, wall)

        assertTrue(fill(target, boundary, 0, 0)) // the small triangle in the top-left corner

        assertEquals(red, target.getPixel(0, 0))
        assertEquals("8-way leak through the diagonal would reach here", 0, Color.alpha(target.getPixel(15, 15)))
        // Triangle above the diagonal: for row y, columns x < 9 - y are inside.
        var expected = 0
        for (y in 0 until 10) expected += (9 - y).coerceAtLeast(0)
        assertEquals(expected, countFilled(target))
    }

    @Test fun `tapping directly on a wall does nothing and reports no change`() {
        val target = blank(); val boundary = blank()
        box(boundary, 10, 5, 25, 20)
        assertFalse(fill(target, boundary, 10, 12))
        assertEquals(0, countFilled(target))
    }

    @Test fun `the wall alpha threshold is inclusive at 40 and faint lines below it do not block`() {
        for ((alpha, blocks) in listOf(39 to false, 40 to true)) {
            val target = blank(); val boundary = blank()
            val faint = Color.argb(alpha, 0, 0, 0)
            for (y in 0 until h) boundary.setPixel(20, y, faint) // a full-height vertical line
            fill(target, boundary, 5, 10)
            val leaked = Color.alpha(target.getPixel(30, 10)) != 0
            assertEquals("alpha $alpha blocks=$blocks", blocks, !leaked)
        }
    }

    @Test fun `a custom wall threshold is honoured`() {
        val target = blank(); val boundary = blank()
        for (y in 0 until h) boundary.setPixel(20, y, Color.argb(100, 0, 0, 0))
        fill(target, boundary, 5, 10, threshold = 101)
        assertEquals("100 < 101, so the line is not a wall", red, target.getPixel(30, 10))
    }

    @Test fun `out of range seeds and mismatched boundary sizes are rejected without touching the target`() {
        val target = blank(); val boundary = blank()
        assertFalse(fill(target, boundary, -1, 0))
        assertFalse(fill(target, boundary, 0, -1))
        assertFalse(fill(target, boundary, w, 0))
        assertFalse(fill(target, boundary, 0, h))
        assertFalse(fill(target, blank(w + 1, h), 0, 0))
        assertFalse(fill(target, blank(w, h - 1), 0, 0))
        assertEquals(0, countFilled(target))
    }

    @Test fun `existing ink on the target layer does not block the fill only the boundary bitmap does`() {
        val target = blank(); val boundary = blank()
        for (y in 0 until h) target.setPixel(20, y, Color.BLUE) // ink on the TARGET, not in the boundary
        assertTrue(fill(target, boundary, 5, 10))
        assertEquals("the fill continued across the target's own line", red, target.getPixel(30, 10))
        assertEquals("and painted over it", red, target.getPixel(20, 10))
    }

    @Test fun `a translucent fill composites over existing pixels exactly once per pixel`() {
        // A U-shaped region: the fill has to come back up the second arm, the classic place a
        // scanline fill re-visits a span and applies the translucent colour twice (a darker seam).
        val target = blank(30, 20); val boundary = blank(30, 20)
        for (y in 0 until 20) for (x in 0 until 30) boundary.setPixel(x, y, wall)
        for (y in 2..15) { for (x in 3..6) boundary.setPixel(x, y, Color.TRANSPARENT); for (x in 12..15) boundary.setPixel(x, y, Color.TRANSPARENT) }
        for (x in 3..15) for (y in 14..17) boundary.setPixel(x, y, Color.TRANSPARENT)
        for (y in 0 until 20) for (x in 0 until 30) target.setPixel(x, y, Color.WHITE)

        assertTrue(fill(target, boundary, 4, 3, color = red, alpha = 0.5f))

        // Red at 50% over opaque white: (255, 127, 127) +-1 for premultiplied rounding.
        var filled = 0
        for (y in 0 until 20) for (x in 0 until 30) {
            val p = target.getPixel(x, y)
            if (p == Color.WHITE) continue
            filled++
            assertEquals("pixel ($x,$y) must be opaque", 255, Color.alpha(p))
            assertEquals("pixel ($x,$y) red", 255, Color.red(p))
            assertTrue("pixel ($x,$y) green ${Color.green(p)} (a second composite would be ~64)", abs(Color.green(p) - 127) <= 1)
            assertTrue("pixel ($x,$y) blue ${Color.blue(p)}", abs(Color.blue(p) - 127) <= 1)
        }
        // Left arm x3..6 (y2..15) + right arm x12..15 (y2..15) + base x3..15 (y14..17), overlaps counted once.
        var expected = 0
        for (y in 0 until 20) for (x in 0 until 30) if (Color.alpha(boundary.getPixel(x, y)) < 40) expected++
        assertEquals(expected, filled)
    }

    @Test fun `filling an open canvas covers every pixel without recursion trouble`() {
        val target = blank(300, 300); val boundary = blank(300, 300)
        assertTrue(fill(target, boundary, 150, 150))
        assertEquals(300 * 300, countFilled(target))
    }
}
