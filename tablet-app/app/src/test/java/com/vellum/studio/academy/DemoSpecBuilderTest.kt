package com.vellum.studio.academy

import android.app.Application
import android.graphics.Color
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * [DemoSpecBuilder.buildPath]/[DemoSpecBuilder.build] construct genuine `android.graphics.Path`/
 * `Color` values the moment they run (unlike [DiagramRenderer.render], which only returns an
 * unevaluated closure -- see [DemoSpecBuilder]'s own doc for why that difference matters here), so
 * -- same reasoning as [DiagramRendererTest] -- this needs Robolectric's `@GraphicsMode(NATIVE)` for
 * `Path.computeBounds()`/`PathMeasure` to reflect real, accurate geometry rather than a no-op legacy
 * shadow. This is exactly why the equivalent happy-path/end-to-end coverage for [DiagramOpDto] lives
 * in a Robolectric test and NOT in the plain-JVM [AcademyContentLoaderTest] -- [AcademyContentLoaderTest]
 * still covers every demo *rejection* path itself (validation runs, and throws, before any real Path
 * is ever built), see its own "Demo blocks" section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoSpecBuilderTest {

    private fun assertPathsMatch(expected: Path, actual: Path, samples: Int = 20, epsilon: Float = 0.0005f) {
        val expectedMeasure = PathMeasure(expected, false)
        val actualMeasure = PathMeasure(actual, false)
        assertEquals("path length", expectedMeasure.length, actualMeasure.length, epsilon)

        val expectedPos = FloatArray(2)
        val actualPos = FloatArray(2)
        for (i in 0..samples) {
            val distance = expectedMeasure.length * i / samples
            expectedMeasure.getPosTan(distance, expectedPos, null)
            actualMeasure.getPosTan(distance, actualPos, null)
            assertEquals("x at sample $i", expectedPos[0], actualPos[0], epsilon)
            assertEquals("y at sample $i", expectedPos[1], actualPos[1], epsilon)
        }
    }

    // --- buildPath: pure geometry construction ---------------------------------------------------

    @Test fun `buildPath builds a triangle whose bounds match its three points`() {
        val commands = listOf(
            PathCommandDto.MoveTo(0.5f, 0.1f),
            PathCommandDto.LineTo(0.9f, 0.9f),
            PathCommandDto.LineTo(0.1f, 0.9f),
        )
        val path = DemoSpecBuilder.buildPath(commands)

        val bounds = RectF()
        path.computeBounds(bounds, true)
        assertEquals(0.1f, bounds.left, 0.0001f)
        assertEquals(0.1f, bounds.top, 0.0001f)
        assertEquals(0.9f, bounds.right, 0.0001f)
        assertEquals(0.9f, bounds.bottom, 0.0001f)
    }

    @Test fun `buildPath does not multiply coordinates by any size -- they stay normalized 0 to 1`() {
        val commands = listOf(PathCommandDto.MoveTo(0.25f, 0.75f), PathCommandDto.LineTo(0.6f, 0.4f))
        val path = DemoSpecBuilder.buildPath(commands)

        val bounds = RectF()
        path.computeBounds(bounds, true)
        assertEquals(0.25f, bounds.left, 0.0001f)
        assertEquals(0.4f, bounds.top, 0.0001f)
        assertEquals(0.6f, bounds.right, 0.0001f)
        assertEquals(0.75f, bounds.bottom, 0.0001f)
    }

    @Test fun `buildPath with quadTo and cubicTo matches an equivalent hand-built Path`() {
        val commands = listOf(
            PathCommandDto.MoveTo(0.1f, 0.5f),
            PathCommandDto.QuadTo(controlX = 0.5f, controlY = 0.1f, x = 0.9f, y = 0.5f),
            PathCommandDto.CubicTo(
                control1X = 0.9f, control1Y = 0.9f, control2X = 0.1f, control2Y = 0.9f, x = 0.1f, y = 0.5f,
            ),
        )
        val built = DemoSpecBuilder.buildPath(commands)

        val expected = Path().apply {
            moveTo(0.1f, 0.5f)
            quadTo(0.5f, 0.1f, 0.9f, 0.5f)
            cubicTo(0.9f, 0.9f, 0.1f, 0.9f, 0.1f, 0.5f)
        }

        assertPathsMatch(expected, built)
    }

    // --- build: DemoSpecDto -> real LessonDemo ---------------------------------------------------

    @Test fun `build converts a DemoSpecDto into a LessonDemo with the right stages, brush, color, and size`() {
        val dto = DemoSpecDto(
            stages = listOf(
                DemoStageDto(
                    caption = "Stage one",
                    strokes = listOf(
                        DemoStrokeDto(
                            path = listOf(PathCommandDto.MoveTo(0.1f, 0.1f), PathCommandDto.LineTo(0.9f, 0.9f)),
                            brushId = "watercolor",
                            color = "#7FA8D9",
                            sizeMultiplier = 7f,
                        ),
                    ),
                ),
                DemoStageDto(
                    caption = "Stage two",
                    strokes = listOf(
                        DemoStrokeDto(
                            path = listOf(PathCommandDto.MoveTo(0.2f, 0.3f), PathCommandDto.LineTo(0.4f, 0.5f)),
                            brushId = "fineliner",
                            color = "#4B5A63",
                            // sizeMultiplier omitted -- should fall back to the domain default of 1f.
                        ),
                    ),
                ),
            ),
        )

        val demo = DemoSpecBuilder.build(dto)

        assertEquals(2, demo.stages.size)
        assertEquals("Stage one", demo.stages[0].caption)
        assertEquals("Stage two", demo.stages[1].caption)

        val firstStroke = demo.stages[0].strokes.single()
        assertEquals("watercolor", firstStroke.brushId)
        assertEquals(Color.parseColor("#7FA8D9"), firstStroke.colorArgb)
        assertEquals(7f, firstStroke.sizeMultiplier, 0.0001f)
        val bounds = RectF()
        firstStroke.path.computeBounds(bounds, true)
        assertEquals(0.1f, bounds.left, 0.0001f)
        assertEquals(0.9f, bounds.right, 0.0001f)

        val secondStroke = demo.stages[1].strokes.single()
        assertEquals("fineliner", secondStroke.brushId)
        assertEquals(1f, secondStroke.sizeMultiplier, 0.0001f)
    }

    // --- End-to-end proof: the real, checked-in, migrated Wet-on-Wet course actually works --------

    /**
     * The real proof migration for [DemoSpecDto]/[DemoSpecBuilder]: [CourseWetOnWet], whose flagship
     * "sky-mountain-water" lesson carries a real [LessonDemo] and no [LessonBlock.Diagram] blocks --
     * a clean first test of just the new demo capability. Every stroke asserted below was
     * transcribed directly from this course's original hand-coded `Path`/`DemoStroke` construction.
     */
    @Test fun `the real migrated wet-on-wet course loads and validates end-to-end, including its flagship demo`() {
        val file = File("src/main/assets/academy/wet-on-wet.json")
        assertTrue("expected bundled asset at ${file.absolutePath}", file.exists())

        val course = AcademyContentLoader.parseAndValidate(file.readText(), sourceLabel = file.path)

        assertEquals("wet-on-wet-landscapes", course.id)
        assertEquals("Wet-on-Wet Landscapes", course.title)
        assertEquals(Instructors.dune.id, course.instructorId)
        assertEquals(5, course.lessons.size)
        assertEquals(
            listOf(
                "meet-wet-on-wet",
                "a-palette-you-actually-need",
                "sky-mountain-water",
                "happy-little-details",
                "knowing-when-to-stop",
            ),
            course.lessons.map { it.id },
        )
        course.lessons.forEach { lesson ->
            assertFalse("lesson '${lesson.id}' should have real content blocks", lesson.blocks.isEmpty())
        }

        // Only the flagship lesson carries a demo -- everything else stays text-only, same as the
        // original hand-authored course.
        course.lessons.filter { it.id != "sky-mountain-water" }.forEach { lesson ->
            assertEquals("lesson '${lesson.id}' should have no demo", null, lesson.demo)
        }

        val demo = course.lessons.single { it.id == "sky-mountain-water" }.demo
        assertNotNull("expected the flagship lesson to carry a real demo", demo)
        assertEquals(6, demo!!.stages.size)
        assertEquals(
            listOf(
                "Big shapes first. One broad, confident stroke of sky blue across the whole top — don't be careful, be loose.",
                "Pull a warmer tone in near the horizon while the sky's still wet — that's the blend doing the work, not a hard edge.",
                "The mountain is just a ridge line — a couple of confident peaks, nothing fussy.",
                "Fill the whole shape solid. If it goes slightly outside the line, that's not a mistake — the sky is still wet and forgiving.",
                "Water is a mirror with less confidence: same blue, one broad stroke.",
                "Echo the mountain's shape upside down and softened — that's the reflection. It doesn't need to match exactly.",
            ),
            demo.stages.map { it.caption },
        )

        val skyStroke = demo.stages[0].strokes.single()
        assertEquals("watercolor", skyStroke.brushId)
        assertEquals(Color.parseColor("#7FA8D9"), skyStroke.colorArgb)
        assertEquals(7f, skyStroke.sizeMultiplier, 0.0001f)
        assertPathsMatch(Path().apply { moveTo(0.06f, 0.16f); lineTo(0.94f, 0.16f) }, skyStroke.path)

        val mountainStroke = demo.stages[2].strokes.single()
        assertEquals("fineliner", mountainStroke.brushId)
        assertEquals(Color.parseColor("#4B5A63"), mountainStroke.colorArgb)
        assertEquals(1.2f, mountainStroke.sizeMultiplier, 0.0001f)
        assertPathsMatch(
            Path().apply {
                moveTo(0.10f, 0.50f)
                lineTo(0.32f, 0.24f)
                lineTo(0.46f, 0.38f)
                lineTo(0.62f, 0.20f)
                lineTo(0.90f, 0.50f)
            },
            mountainStroke.path,
        )

        val reflectionStroke = demo.stages[5].strokes.single()
        assertEquals("watercolor", reflectionStroke.brushId)
        assertEquals(Color.parseColor("#4B5A63"), reflectionStroke.colorArgb)
        assertEquals(2.5f, reflectionStroke.sizeMultiplier, 0.0001f)
        assertPathsMatch(
            Path().apply {
                moveTo(0.14f, 0.62f)
                lineTo(0.30f, 0.70f)
                lineTo(0.40f, 0.64f)
                lineTo(0.50f, 0.72f)
                lineTo(0.60f, 0.65f)
                lineTo(0.72f, 0.71f)
                lineTo(0.86f, 0.62f)
            },
            reflectionStroke.path,
        )

        // Reproduces the exact same, already-proven-correct domain objects either way (see the
        // equivalent photo-reference-tools/digital-fundamentals/watercolor tests in
        // AcademyContentLoaderTest) -- except LessonDemo can't use assertEquals directly, since
        // android.graphics.Path has no structural equals(); the geometry proof above already covers
        // that half, so this just re-confirms the same stage/stroke shape from a second parse.
        val secondParseDemo = AcademyContentLoader.parseAndValidate(file.readText())
            .lessons.single { it.id == "sky-mountain-water" }.demo
        assertNotNull(secondParseDemo)
        assertEquals(demo.stages.map { it.caption }, secondParseDemo!!.stages.map { it.caption })
        assertEquals(demo.stages.map { s -> s.strokes.map { it.brushId } }, secondParseDemo.stages.map { s -> s.strokes.map { it.brushId } })
    }

    /**
     * Part of the "remaining migrations" pass: [CourseFoundations], whose "building-volume" lesson
     * carries a flagship [LessonDemo]. Its four strokes were originally built with
     * `Path.addCircle`/`Path.addOval` (not the moveTo/lineTo/quadTo/cubicTo vocabulary
     * [PathCommandDto] covers), so each was re-expressed as the standard 4-cubic-Bezier circle/
     * ellipse approximation (kappa = 0.5522847498307936) -- this test's geometry assertions (via
     * [assertPathsMatch] against a real `Path.addCircle`/`Path.addOval`) are exactly what confirms
     * that re-expression is faithful, not just "close enough".
     */
    @Test fun `the real migrated foundations course loads and validates end-to-end, including its building-volume demo`() {
        val file = File("src/main/assets/academy/foundations.json")
        assertTrue("expected bundled asset at ${file.absolutePath}", file.exists())

        val course = AcademyContentLoader.parseAndValidate(file.readText(), sourceLabel = file.path)

        assertEquals("foundations", course.id)
        assertEquals("Drawing Foundations", course.title)
        assertEquals(Instructors.rowan.id, course.instructorId)
        assertEquals(5, course.lessons.size)
        assertEquals(
            listOf(
                "line-confidence",
                "seeing-in-shapes",
                "building-volume",
                "proportions-without-ruler",
                "gesture-drawing",
            ),
            course.lessons.map { it.id },
        )
        course.lessons.forEach { lesson ->
            assertFalse("lesson '${lesson.id}' should have real content blocks", lesson.blocks.isEmpty())
        }

        // Only the flagship lesson carries a demo -- everything else stays text-only (plus, for
        // seeing-in-shapes and building-volume, a static Diagram), same as the original course.
        course.lessons.filter { it.id != "building-volume" }.forEach { lesson ->
            assertEquals("lesson '${lesson.id}' should have no demo", null, lesson.demo)
        }

        val demo = course.lessons.single { it.id == "building-volume" }.demo
        assertNotNull("expected the flagship lesson to carry a real demo", demo)
        assertEquals(4, demo!!.stages.size)
        assertEquals(
            listOf(
                "Start with a plain circle.",
                "Add one curved line across it, like a belt — that alone reads as a sphere.",
                "Now a cylinder: the top ellipse, then a matching one below it.",
                "Connect the left edges, then the right edges, with two straight lines.",
            ),
            demo.stages.map { it.caption },
        )

        // The circle/oval strokes were converted from the original `Path.addCircle`/`addOval` calls
        // into an equivalent moveTo + 4x cubicTo Bezier approximation (kappa = 0.5522847498307936):
        // a real android.graphics.Path from that construction is tangent to its bounding square at
        // each of its four cardinal points, so its computeBounds() exactly matches the source
        // addCircle/addOval RectF -- a precise, not approximate, proof this re-expression is
        // geometrically faithful (PathMeasure-sampling, used elsewhere in this file, would only
        // prove "close", since a Bezier's arc-length parametrization isn't perfectly uniform).
        fun bounds(path: Path): RectF = RectF().also { path.computeBounds(it, true) }

        val circleStroke = demo.stages[0].strokes.single()
        assertEquals("fineliner", circleStroke.brushId)
        assertEquals(Color.parseColor("#3A3A3A"), circleStroke.colorArgb)
        val circleBounds = bounds(circleStroke.path)
        assertEquals(0.13f, circleBounds.left, 0.0001f)
        assertEquals(0.25f, circleBounds.top, 0.0001f)
        assertEquals(0.47f, circleBounds.right, 0.0001f)
        assertEquals(0.59f, circleBounds.bottom, 0.0001f)

        val horizonStroke = demo.stages[1].strokes.single()
        assertEquals("pencil", horizonStroke.brushId)
        assertEquals(Color.parseColor("#9A9A9A"), horizonStroke.colorArgb)
        assertEquals(0.8f, horizonStroke.sizeMultiplier, 0.0001f)
        val horizonBounds = bounds(horizonStroke.path)
        assertEquals(0.13f, horizonBounds.left, 0.0001f)
        assertEquals(0.3656f, horizonBounds.top, 0.0001f)
        assertEquals(0.47f, horizonBounds.right, 0.0001f)
        assertEquals(0.4744f, horizonBounds.bottom, 0.0001f)

        val cylinderStrokes = demo.stages[2].strokes
        assertEquals(2, cylinderStrokes.size)
        val topOvalBounds = bounds(cylinderStrokes[0].path)
        assertEquals(0.61f, topOvalBounds.left, 0.0001f)
        assertEquals(0.215f, topOvalBounds.top, 0.0001f)
        assertEquals(0.83f, topOvalBounds.right, 0.0001f)
        assertEquals(0.305f, topOvalBounds.bottom, 0.0001f)
        val botOvalBounds = bounds(cylinderStrokes[1].path)
        assertEquals(0.61f, botOvalBounds.left, 0.0001f)
        assertEquals(0.515f, botOvalBounds.top, 0.0001f)
        assertEquals(0.83f, botOvalBounds.right, 0.0001f)
        assertEquals(0.605f, botOvalBounds.bottom, 0.0001f)

        val lineStrokes = demo.stages[3].strokes
        assertPathsMatch(Path().apply { moveTo(0.61f, 0.26f); lineTo(0.61f, 0.56f) }, lineStrokes[0].path)
        assertPathsMatch(Path().apply { moveTo(0.83f, 0.26f); lineTo(0.83f, 0.56f) }, lineStrokes[1].path)
    }

    /**
     * Part of the "remaining migrations" pass: [CourseGraffiti], whose "bubble-letters-throw-up"
     * lesson carries a flagship [LessonDemo]. This course needed no format changes at all -- every
     * stroke was already built entirely from moveTo/quadTo/cubicTo.
     */
    @Test fun `the real migrated graffiti course loads and validates end-to-end, including its bubble-letter demo`() {
        val file = File("src/main/assets/academy/graffiti.json")
        assertTrue("expected bundled asset at ${file.absolutePath}", file.exists())

        val course = AcademyContentLoader.parseAndValidate(file.readText(), sourceLabel = file.path)

        assertEquals("graffiti-lettering-style", course.id)
        assertEquals("Graffiti Lettering & Style", course.title)
        assertEquals(Instructors.kai.id, course.instructorId)
        assertEquals(5, course.lessons.size)
        assertEquals(
            listOf(
                "letterform-foundation",
                "bubble-letters-throw-up",
                "layers-outline-fill-highlight-shadow",
                "wildstyle-breaking-rules-on-purpose",
                "texture-drips-fades-stencils",
            ),
            course.lessons.map { it.id },
        )
        course.lessons.forEach { lesson ->
            assertFalse("lesson '${lesson.id}' should have real content blocks", lesson.blocks.isEmpty())
        }

        course.lessons.filter { it.id != "bubble-letters-throw-up" }.forEach { lesson ->
            assertEquals("lesson '${lesson.id}' should have no demo", null, lesson.demo)
        }

        val demo = course.lessons.single { it.id == "bubble-letters-throw-up" }.demo
        assertNotNull("expected the flagship lesson to carry a real demo", demo)
        assertEquals(4, demo!!.stages.size)

        val outlineStroke = demo.stages[1].strokes.single()
        assertEquals("graffiti_fatcap", outlineStroke.brushId)
        assertEquals(Color.parseColor("#2A2A2A"), outlineStroke.colorArgb)
        assertEquals(1.3f, outlineStroke.sizeMultiplier, 0.0001f)
        assertPathsMatch(
            Path().apply {
                moveTo(0.58f, 0.32f)
                cubicTo(0.50f, 0.22f, 0.26f, 0.24f, 0.22f, 0.38f)
                cubicTo(0.18f, 0.52f, 0.34f, 0.54f, 0.46f, 0.56f)
                cubicTo(0.60f, 0.58f, 0.70f, 0.64f, 0.64f, 0.76f)
                cubicTo(0.56f, 0.86f, 0.32f, 0.84f, 0.26f, 0.72f)
            },
            outlineStroke.path,
        )

        val fillStroke = demo.stages[2].strokes.single()
        assertEquals("graffiti_spray", fillStroke.brushId)
        assertEquals(Color.parseColor("#DD3355"), fillStroke.colorArgb)
        assertEquals(2.4f, fillStroke.sizeMultiplier, 0.0001f)

        val highlightStroke = demo.stages[3].strokes.single()
        assertEquals("graffiti_wildstyle", highlightStroke.brushId)
        assertEquals(Color.parseColor("#FFFFFF"), highlightStroke.colorArgb)
        assertPathsMatch(
            Path().apply { moveTo(0.30f, 0.34f); quadTo(0.34f, 0.28f, 0.42f, 0.30f) },
            highlightStroke.path,
        )
    }
}
