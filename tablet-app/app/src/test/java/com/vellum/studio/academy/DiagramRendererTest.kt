package com.vellum.studio.academy

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DiagramRenderer.render] produces a real `(Canvas, Int) -> Unit` closure built entirely out of
 * real `android.graphics.Paint`/`Canvas` drawing calls, so -- unlike [RegionAnalyzerTest]/
 * [ShapeAssistTest] (which only ever construct `Bitmap`/`PointF`/`RectF` values directly, never
 * actually invoke `Canvas.drawXxx`) -- this needs Robolectric's `@GraphicsMode(NATIVE)`: plain
 * Robolectric's default (legacy) `Canvas` shadow does not rasterize real pixels at all (every
 * `drawXxx` call is a no-op against the backing `Bitmap`), which would make every pixel assertion
 * below trivially pass against a still-blank bitmap without actually proving anything. NATIVE mode
 * runs real Skia-backed drawing, so a read-back pixel is the real, rendered result.
 *
 * Sample points are always chosen well inside a fill/stroke region (never right on an edge), so
 * anti-aliasing (every [DiagramRenderer] Paint sets `ANTI_ALIAS_FLAG`, matching every hand-coded
 * Diagram closure) never makes an assertion flaky.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagramRendererTest {

    private fun renderToBitmap(ops: List<DiagramOpDto>, size: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        DiagramRenderer.render(ops)(canvas, size)
        return bitmap
    }

    // --- line -----------------------------------------------------------------------------------

    @Test fun `a solid line draws its color along its length and leaves the rest transparent`() {
        val bitmap = renderToBitmap(
            listOf(DiagramOpDto.Line(x1 = 0.1f, y1 = 0.5f, x2 = 0.9f, y2 = 0.5f, color = "#000000", strokeWidth = 0.2f))
        )
        // Thick (20px at size=100) horizontal line centered on y=50 -- well inside its stroke band.
        assertEquals(Color.BLACK, bitmap.getPixel(50, 50))
        // Untouched corner, far from the line.
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(5, 5))
    }

    @Test fun `a dashed line alternates drawn and gap segments`() {
        val size = 200
        val bitmap = renderToBitmap(
            listOf(
                DiagramOpDto.Line(
                    x1 = 0.1f, y1 = 0.5f, x2 = 0.9f, y2 = 0.5f,
                    color = "#FF0000", strokeWidth = 0.08f, dash = listOf(0.1f, 0.1f),
                )
            ),
            size = size,
        )
        // Dash starts at x=20 (0.1*200): first "on" segment covers [20,40) -- sample well inside it.
        assertEquals(Color.RED, bitmap.getPixel(30, 100))
        // First gap covers [40,60) -- sample well inside it, should be untouched.
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(50, 100))
        // Second "on" segment covers [60,80).
        assertEquals(Color.RED, bitmap.getPixel(70, 100))
    }

    // --- circle -----------------------------------------------------------------------------------

    @Test fun `a filled circle colors its interior and leaves the outside transparent`() {
        val bitmap = renderToBitmap(
            listOf(DiagramOpDto.Circle(cx = 0.5f, cy = 0.5f, r = 0.3f, fillColor = "#0000FF"))
        )
        assertEquals(Color.BLUE, bitmap.getPixel(50, 50)) // dead center
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(5, 5)) // outside the circle entirely
    }

    @Test fun `a stroke-only circle draws a ring, not a filled disc`() {
        val bitmap = renderToBitmap(
            listOf(
                DiagramOpDto.Circle(cx = 0.5f, cy = 0.5f, r = 0.3f, strokeColor = "#00AA00", strokeWidth = 0.04f)
            )
        )
        assertEquals(Color.rgb(0, 170, 0), bitmap.getPixel(80, 50)) // on the ring (cx + r*size = 50 + 30)
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(50, 50)) // center: not filled
    }

    // --- rect -----------------------------------------------------------------------------------

    @Test fun `a rect with both fill and stroke draws the fill under a distinct stroke border`() {
        val bitmap = renderToBitmap(
            listOf(
                DiagramOpDto.Rect(
                    left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f,
                    fillColor = "#EEEEEE", strokeColor = "#111111", strokeWidth = 0.04f,
                )
            )
        )
        assertEquals(Color.rgb(0xEE, 0xEE, 0xEE), bitmap.getPixel(50, 50)) // interior: fill color
        assertEquals(Color.rgb(0x11, 0x11, 0x11), bitmap.getPixel(50, 20)) // top edge: stroke color
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(5, 5)) // outside the rect entirely
    }

    // --- text -----------------------------------------------------------------------------------

    @Test fun `text draws its color somewhere near its anchor point`() {
        val bitmap = renderToBitmap(
            listOf(DiagramOpDto.Text(x = 0.5f, y = 0.5f, text = "VP", color = "#FF00FF", textSize = 0.3f))
        )
        var found = false
        for (x in 20..80) {
            for (y in 20..55) {
                if (bitmap.getPixel(x, y) == Color.rgb(0xFF, 0x00, 0xFF)) found = true
            }
        }
        assertTrue("expected at least one full-color text pixel near the anchor", found)
    }

    // --- path -----------------------------------------------------------------------------------

    @Test fun `a closed filled path colors its interior and a stroke-only path does not`() {
        // A triangle spanning most of the canvas, built the same way every hand-coded Diagram path is:
        // moveTo then lineTo segments, closed back to the start.
        val triangle = listOf(
            PathCommandDto.MoveTo(0.5f, 0.1f),
            PathCommandDto.LineTo(0.9f, 0.9f),
            PathCommandDto.LineTo(0.1f, 0.9f),
        )
        val filled = renderToBitmap(
            listOf(DiagramOpDto.Path(commands = triangle, closed = true, fillColor = "#00FFFF"))
        )
        assertEquals(Color.CYAN, filled.getPixel(50, 70)) // well inside the triangle

        val strokedOnly = renderToBitmap(
            listOf(DiagramOpDto.Path(commands = triangle, closed = true, strokeColor = "#00FFFF", strokeWidth = 0.02f))
        )
        assertEquals(Color.TRANSPARENT, strokedOnly.getPixel(50, 70)) // same interior point: not filled this time
    }

    @Test fun `a quadTo and cubicTo path renders without error and fills its interior`() {
        val curved = listOf(
            PathCommandDto.MoveTo(0.1f, 0.5f),
            PathCommandDto.QuadTo(controlX = 0.5f, controlY = 0.1f, x = 0.9f, y = 0.5f),
            PathCommandDto.CubicTo(
                control1X = 0.9f, control1Y = 0.9f, control2X = 0.1f, control2Y = 0.9f, x = 0.1f, y = 0.5f,
            ),
        )
        val bitmap = renderToBitmap(listOf(DiagramOpDto.Path(commands = curved, closed = true, fillColor = "#AA00AA")))
        assertEquals(Color.rgb(0xAA, 0x00, 0xAA), bitmap.getPixel(50, 55))
    }

    // --- alpha and op order -----------------------------------------------------------------------

    @Test fun `alpha blends the fill color with whatever is already drawn underneath`() {
        val bitmap = renderToBitmap(
            listOf(
                DiagramOpDto.Rect(left = 0f, top = 0f, right = 1f, bottom = 1f, fillColor = "#FFFFFF"),
                DiagramOpDto.Rect(left = 0.2f, top = 0.2f, right = 0.8f, bottom = 0.8f, fillColor = "#FF0000", alpha = 0.5f),
            )
        )
        val pixel = bitmap.getPixel(50, 50)
        // 50% red over a white background: red channel stays saturated, green/blue drop by ~half.
        assertEquals(255, Color.red(pixel))
        assertTrue("expected green channel near 128, was ${Color.green(pixel)}", Color.green(pixel) in 120..136)
        assertTrue("expected blue channel near 128, was ${Color.blue(pixel)}", Color.blue(pixel) in 120..136)
    }

    @Test fun `later ops paint over earlier ops at the same location`() {
        val bitmap = renderToBitmap(
            listOf(
                DiagramOpDto.Circle(cx = 0.5f, cy = 0.5f, r = 0.3f, fillColor = "#0000FF"),
                DiagramOpDto.Circle(cx = 0.5f, cy = 0.5f, r = 0.15f, fillColor = "#FF0000"),
            )
        )
        assertEquals(Color.RED, bitmap.getPixel(50, 50)) // the smaller, later red circle wins at center
        assertEquals(Color.BLUE, bitmap.getPixel(50, 25)) // still inside the larger blue circle only
    }
}
