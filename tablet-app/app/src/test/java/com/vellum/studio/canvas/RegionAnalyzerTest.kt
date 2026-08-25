package com.vellum.studio.canvas

import android.app.Application
import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [RegionAnalyzer.analyze] takes a real [android.graphics.Bitmap] and needs actual `getPixels`
 * behavior over it, not just a non-throwing stub -- so, like [ShapeAssistTest], this runs under
 * Robolectric (real android-all framework bytecode, pixel-accurate native-graphics-backed Bitmap)
 * rather than as a plain JVM unit test.
 *
 * Every boundary bitmap here is built pixel-by-pixel from an explicit wall predicate (not drawn
 * via `Canvas`/`Paint`), so the exact wall/open pixel layout -- and therefore the exact expected
 * region pixel counts and centroids -- is known analytically up front rather than approximated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RegionAnalyzerTest {

    private fun buildBoundaryBitmap(width: Int, height: Int, isWall: (x: Int, y: Int) -> Boolean): Bitmap {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (isWall(x, y)) 0xFF000000.toInt() else 0x00000000
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /** True on the 1px-thick outline of a `size x size` square whose top-left corner is (x0, y0). */
    private fun ringWall(x: Int, y: Int, x0: Int, y0: Int, size: Int): Boolean {
        val x1 = x0 + size - 1
        val y1 = y0 + size - 1
        if (x < x0 || x > x1 || y < y0 || y > y1) return false
        return x == x0 || x == x1 || y == y0 || y == y1
    }

    @Test fun `closed rectangular boundary produces two regions with the expected centroids`() {
        val width = 100
        val height = 100
        val x0 = 10
        val y0 = 20
        val size = 40 // square spans x in [10,49], y in [20,59]

        val bitmap = buildBoundaryBitmap(width, height) { x, y -> ringWall(x, y, x0, y0, size) }
        val regionMap = RegionAnalyzer.analyze(bitmap)

        assertEquals(2, regionMap.regions.size)
        val interior = regionMap.regions.minByOrNull { it.pixelCount }!!
        val exterior = regionMap.regions.maxByOrNull { it.pixelCount }!!

        // Interior: the 38x38 open square strictly inside the 1px ring.
        assertEquals(38 * 38, interior.pixelCount)
        // Exterior: everything else in the 100x100 canvas outside the full 40x40 block (ring + interior).
        assertEquals(width * height - size * size, exterior.pixelCount)

        // Analytic centroids (integer-truncated by RegionAnalyzer's own sumX/count math, hence the
        // +/-1px tolerance rather than an exact float match):
        //   interior center = ((11+48)/2, (21+58)/2) = (29.5, 39.5)
        //   exterior center = (canvas sumX - block sumX) / exterior count, likewise for Y.
        assertEquals(29.5f, interior.centroidX, 1f)
        assertEquals(39.5f, interior.centroidY, 1f)
        assertEquals(53.31f, exterior.centroidX, 1f)
        assertEquals(51.40f, exterior.centroidY, 1f)

        // A point deep inside the enclosed square resolves to the interior region...
        assertEquals(interior.number, regionMap.regionAt(25, 35)?.number)
        // ...a point far outside the block resolves to the exterior region...
        assertEquals(exterior.number, regionMap.regionAt(0, 0)?.number)
        // ...and a wall pixel itself belongs to no region at all.
        assertNull(regionMap.regionAt(x0, y0 + 5))
    }

    @Test fun `a single-pixel gap in the boundary merges the two regions into one`() {
        val width = 100
        val height = 100
        val x0 = 10
        val y0 = 20
        val size = 40
        val gapX = x0 + size / 2 // one missing pixel partway along the top edge -- the wall is unclosed

        val bitmap = buildBoundaryBitmap(width, height) { x, y ->
            if (x == gapX && y == y0) false else ringWall(x, y, x0, y0, size)
        }
        val regionMap = RegionAnalyzer.analyze(bitmap)

        // The breach lets the flood fill leak between what would otherwise be two separate rooms --
        // fewer regions than the closed case above, not more or the same.
        assertEquals(1, regionMap.regions.size)
        val ringPixelCount = 4 * size - 4
        assertEquals(width * height - (ringPixelCount - 1), regionMap.regions.single().pixelCount)

        // Both what used to be "inside" and "outside" the square now resolve to the same region.
        assertEquals(regionMap.regionAt(0, 0)?.number, regionMap.regionAt(25, 35)?.number)
    }

    @Test fun `an enclosed area smaller than the minimum region size yields no valid regions`() {
        val width = 40
        val height = 40
        val pocketX0 = 15
        val pocketY0 = 15
        val pocketSize = 10 // 100px, well under the default 400px minRegionPixels floor

        // Wall everywhere except a small sealed-off pocket in the middle.
        val bitmap = buildBoundaryBitmap(width, height) { x, y ->
            !(x in pocketX0 until pocketX0 + pocketSize && y in pocketY0 until pocketY0 + pocketSize)
        }
        val regionMap = RegionAnalyzer.analyze(bitmap)

        assertEquals(0, regionMap.regions.size)
        // The pocket was discovered by the flood fill (it's real open space) but dropped for being
        // too small -- regionAt must still report it as belonging to no numbered region.
        assertNull(regionMap.regionAt(pocketX0 + 1, pocketY0 + 1))
    }
}
