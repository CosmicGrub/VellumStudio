package com.vellum.studio.academy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * [AcademyContentLoader.parseAndValidate] is plain Kotlin + kotlinx.serialization with zero
 * Android/Context involvement, so every one of these runs as a plain JVM test -- no Robolectric,
 * matching [PhotoConverterEligibilityTest]'s same reasoning for testing the pure-logic half of a
 * loader/converter directly rather than the untestable I/O shell around it.
 */
class AcademyContentLoaderTest {

    private val minimalValidJson = """
        {
          "schemaVersion": 1,
          "id": "test-course",
          "title": "Test Course",
          "description": "A course for testing.",
          "instructorId": "rowan",
          "lessons": [
            {
              "id": "lesson-one",
              "title": "Lesson One",
              "summary": "The first lesson.",
              "blocks": [
                {"type": "heading", "text": "A Heading"},
                {"type": "paragraph", "text": "A paragraph."},
                {"type": "bulletList", "items": ["one", "two"]},
                {"type": "tip", "text": "A tip."},
                {"type": "masterworkReference", "caption": "A painting", "assetPath": "masterworks/x.jpg", "attribution": "Someone, 1900"}
              ]
            }
          ]
        }
    """.trimIndent()

    // --- Happy path: valid content converts into the exact real domain model ---------------------

    @Test fun `valid content parses into the expected Course, Lesson, and block shape`() {
        val course = AcademyContentLoader.parseAndValidate(minimalValidJson)

        assertEquals("test-course", course.id)
        assertEquals("Test Course", course.title)
        assertEquals("A course for testing.", course.description)
        assertEquals("rowan", course.instructorId)
        assertEquals(1, course.lessons.size)

        val lesson = course.lessons.single()
        assertEquals("lesson-one", lesson.id)
        assertEquals("Lesson One", lesson.title)
        assertEquals("The first lesson.", lesson.summary)
        assertEquals(null, lesson.demo) // the JSON format never produces a demo -- see AcademyContentDto's doc

        assertEquals(5, lesson.blocks.size)
        assertEquals(LessonBlock.Heading("A Heading"), lesson.blocks[0])
        assertEquals(LessonBlock.Paragraph("A paragraph."), lesson.blocks[1])
        assertEquals(LessonBlock.BulletList(listOf("one", "two")), lesson.blocks[2])
        assertEquals(LessonBlock.Tip("A tip."), lesson.blocks[3])
        assertEquals(
            LessonBlock.MasterworkReference("A painting", "masterworks/x.jpg", "Someone, 1900"),
            lesson.blocks[4],
        )
    }

    // --- Malformed JSON / schema violations fail loudly, with a message pointing at the problem ---

    @Test fun `missing required field is rejected with a clear message`() {
        val missingTitle = """
            {"schemaVersion": 1, "id": "c", "description": "d", "instructorId": "rowan",
             "lessons": [{"id": "l", "title": "L", "summary": "s", "blocks": [{"type": "paragraph", "text": "t"}]}]}
        """.trimIndent()
        val e = assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(missingTitle) }
        assertTrue(e.message.orEmpty().contains("title", ignoreCase = true))
    }

    @Test fun `unrecognized block type is rejected`() {
        val badBlockType = minimalValidJson.replace("\"heading\"", "\"bogus-block-type\"")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(badBlockType) }
    }

    @Test fun `unsupported schema version is rejected`() {
        val futureVersion = minimalValidJson.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val e = assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(futureVersion) }
        assertTrue(e.message.orEmpty().contains("99"))
    }

    @Test fun `unknown instructorId is rejected and lists the real known instructors`() {
        val badInstructor = minimalValidJson.replace("\"rowan\"", "\"someone-who-does-not-exist\"")
        val e = assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(badInstructor) }
        assertTrue(e.message.orEmpty().contains("someone-who-does-not-exist"))
        Instructors.all.forEach { assertTrue(e.message.orEmpty().contains(it.id)) }
    }

    @Test fun `course with no lessons is rejected`() {
        val noLessons = minimalValidJson.replace(
            Regex("\"lessons\":\\s*\\[.*\\]", RegexOption.DOT_MATCHES_ALL),
            "\"lessons\": []",
        )
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(noLessons) }
    }

    @Test fun `lesson with no content blocks is rejected`() {
        val noBlocks = minimalValidJson.replace(
            Regex("\"blocks\":\\s*\\[.*\\]", RegexOption.DOT_MATCHES_ALL),
            "\"blocks\": []",
        )
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(noBlocks) }
    }

    @Test fun `duplicate lesson ids within one course are rejected`() {
        val duplicateJson = """
            {
              "schemaVersion": 1, "id": "c", "title": "C", "description": "d", "instructorId": "rowan",
              "lessons": [
                {"id": "same-id", "title": "L1", "summary": "s1", "blocks": [{"type": "paragraph", "text": "t1"}]},
                {"id": "same-id", "title": "L2", "summary": "s2", "blocks": [{"type": "paragraph", "text": "t2"}]}
              ]
            }
        """.trimIndent()
        val e = assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(duplicateJson) }
        assertTrue(e.message.orEmpty().contains("same-id"))
    }

    @Test fun `blank paragraph text is rejected`() {
        val blankParagraph = minimalValidJson.replace("\"A paragraph.\"", "\"   \"")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(blankParagraph) }
    }

    @Test fun `empty bulletList items are rejected`() {
        val emptyBullets = minimalValidJson.replace("[\"one\", \"two\"]", "[]")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(emptyBullets) }
    }

    @Test fun `an unknown field name is rejected rather than silently ignored`() {
        // Authored content is reviewed before it ships -- a typo'd key (e.g. "titel") should fail
        // loudly during development, not silently decode as if the field were never there.
        val typo = minimalValidJson.replaceFirst("\"title\": \"Test Course\"", "\"titel\": \"Test Course\"")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(typo) }
    }

    private fun assertThrowsAcademyContentException(block: () -> Unit): AcademyContentException {
        try {
            block()
        } catch (e: AcademyContentException) {
            return e
        }
        fail("Expected AcademyContentException but nothing was thrown")
        error("unreachable")
    }

    // --- Diagram blocks: happy path plus the same "fail loudly" discipline as every other block ---

    private val diagramLineOp =
        """{"type": "line", "x1": 0.1, "y1": 0.1, "x2": 0.9, "y2": 0.9, "color": "#FF0000", "strokeWidth": 0.01}"""

    private fun courseWithDiagramOps(opsJson: String, caption: String = "A test diagram") = """
        {
          "schemaVersion": 1,
          "id": "test-course",
          "title": "Test Course",
          "description": "A course for testing.",
          "instructorId": "rowan",
          "lessons": [
            {
              "id": "lesson-one",
              "title": "Lesson One",
              "summary": "The first lesson.",
              "blocks": [
                {"type": "diagram", "caption": "$caption", "ops": [$opsJson]}
              ]
            }
          ]
        }
    """.trimIndent()

    @Test fun `a diagram block with a valid op parses into a LessonBlock Diagram with the right caption`() {
        val course = AcademyContentLoader.parseAndValidate(courseWithDiagramOps(diagramLineOp))
        val block = course.lessons.single().blocks.single()
        assertTrue(block is LessonBlock.Diagram)
        assertEquals("A test diagram", (block as LessonBlock.Diagram).caption)
    }

    @Test fun `a diagram block with no ops is rejected`() {
        val emptyOps = courseWithDiagramOps(diagramLineOp).replace("\"ops\": [$diagramLineOp]", "\"ops\": []")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(emptyOps) }
    }

    @Test fun `a blank diagram caption is rejected`() {
        val json = courseWithDiagramOps(diagramLineOp, caption = "   ")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(json) }
    }

    @Test fun `a diagram op with an invalid hex color is rejected`() {
        val badColor = courseWithDiagramOps(diagramLineOp.replace("\"#FF0000\"", "\"red\""))
        val e = assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(badColor) }
        assertTrue(e.message.orEmpty().contains("hex", ignoreCase = true))
    }

    @Test fun `a diagram op coordinate outside 0 to 1 is rejected`() {
        val outOfRange = courseWithDiagramOps(diagramLineOp.replace("\"x1\": 0.1", "\"x1\": 1.5"))
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(outOfRange) }
    }

    @Test fun `a line op with an out-of-range alpha is rejected`() {
        val badAlpha = courseWithDiagramOps(diagramLineOp.dropLast(1) + ", \"alpha\": 1.5}")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(badAlpha) }
    }

    @Test fun `a line op with a malformed dash pattern is rejected`() {
        val badDash = courseWithDiagramOps(diagramLineOp.dropLast(1) + ", \"dash\": [0.1]}")
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(badDash) }
    }

    @Test fun `a circle op with neither fillColor nor strokeColor is rejected`() {
        val bareCircle = """{"type": "circle", "cx": 0.5, "cy": 0.5, "r": 0.1}"""
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(courseWithDiagramOps(bareCircle)) }
    }

    @Test fun `a rect op with a strokeColor but no positive strokeWidth is rejected`() {
        val badRect =
            """{"type": "rect", "left": 0.1, "top": 0.1, "right": 0.9, "bottom": 0.9, "strokeColor": "#000000"}"""
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(courseWithDiagramOps(badRect)) }
    }

    @Test fun `a path op with commands that don't start with moveTo is rejected`() {
        val badPath =
            """{"type": "path", "commands": [{"type": "lineTo", "x": 0.5, "y": 0.5}], "fillColor": "#000000"}"""
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(courseWithDiagramOps(badPath)) }
    }

    @Test fun `a path op with no commands is rejected`() {
        val emptyPath = """{"type": "path", "commands": [], "fillColor": "#000000"}"""
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(courseWithDiagramOps(emptyPath)) }
    }

    @Test fun `a text op with blank text is rejected`() {
        val blankText =
            """{"type": "text", "x": 0.5, "y": 0.5, "text": "   ", "color": "#000000", "textSize": 0.05}"""
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(courseWithDiagramOps(blankText)) }
    }

    @Test fun `an unrecognized diagram op type is rejected as malformed`() {
        val bogusOp = """{"type": "hexagon", "cx": 0.5}"""
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(courseWithDiagramOps(bogusOp)) }
    }

    @Test fun `an unrecognized text align value is rejected as malformed`() {
        val badAlign =
            """{"type": "text", "x": 0.5, "y": 0.5, "text": "hi", "color": "#000000", "textSize": 0.05, "align": "middle"}"""
        assertThrowsAcademyContentException { AcademyContentLoader.parseAndValidate(courseWithDiagramOps(badAlign)) }
    }

    // --- End-to-end proof: the real, checked-in, migrated course actually works -------------------

    /**
     * Reads the real bundled `assets/academy/photo-reference-tools.json` straight off disk (plain
     * [java.io.File], relative to Gradle's own default JVM-test working directory of the `:app`
     * module -- no [android.content.Context]/AssetManager needed, exactly like
     * [AcademyContentLoader.loadFromAssets] would read it at runtime, just without the Android
     * asset-manager step in between) and proves the migrated course parses, validates, and matches
     * [CoursePhotoReference]'s real, original hand-authored shape -- not just a synthetic fixture.
     */
    @Test fun `the real migrated photo-reference-tools course loads and validates end-to-end`() {
        val file = File("src/main/assets/academy/photo-reference-tools.json")
        assertTrue("expected bundled asset at ${file.absolutePath}", file.exists())

        val course = AcademyContentLoader.parseAndValidate(file.readText(), sourceLabel = file.path)

        assertEquals("photo-reference-tools", course.id)
        assertEquals("Drawing From Your Own Photos", course.title)
        assertEquals(Instructors.rowan.id, course.instructorId)
        assertEquals(3, course.lessons.size)
        assertEquals(
            listOf("turning-a-photo-into-a-coloring-page", "why-paint-by-number-sometimes", "pose-reference-guide"),
            course.lessons.map { it.id },
        )
        course.lessons.forEach { lesson ->
            assertFalse("lesson '${lesson.id}' should have real content blocks", lesson.blocks.isEmpty())
            assertEquals(null, lesson.demo)
        }

        // Loading it through the object under test (CoursePhotoReference.course would go through
        // VellumApp.instance.assets, which isn't available outside a real app/Robolectric run --
        // see AcademyContentLoader's own doc for why that thin I/O shell is deliberately untested
        // here) reproduces the exact same, already-proven-correct domain objects either way.
        assertEquals(course, AcademyContentLoader.parseAndValidate(file.readText()))
    }

    /**
     * Same end-to-end proof as the photo-reference-tools test above, for the second and third
     * courses migrated to this format: [CourseDigitalFundamentals] and [CourseWatercolor]. Both were
     * picked for the same reason the pilot was -- every block in each was already plain
     * Heading/Paragraph/BulletList/Tip content, no [LessonBlock.Diagram] and no [LessonDemo].
     */
    @Test fun `the real migrated digital-fundamentals course loads and validates end-to-end`() {
        val file = File("src/main/assets/academy/digital-fundamentals.json")
        assertTrue("expected bundled asset at ${file.absolutePath}", file.exists())

        val course = AcademyContentLoader.parseAndValidate(file.readText(), sourceLabel = file.path)

        assertEquals("digital-fundamentals", course.id)
        assertEquals("Digital Drawing Fundamentals", course.title)
        assertEquals(Instructors.marisol.id, course.instructorId)
        assertEquals(5, course.lessons.size)
        assertEquals(
            listOf(
                "pressure-sensitivity",
                "stylus-only-palm-rejection",
                "working-with-layers",
                "undo-generously-save-often",
                "choosing-the-right-brush",
            ),
            course.lessons.map { it.id },
        )
        course.lessons.forEach { lesson ->
            assertFalse("lesson '${lesson.id}' should have real content blocks", lesson.blocks.isEmpty())
            assertEquals(null, lesson.demo)
        }
        assertEquals(course, AcademyContentLoader.parseAndValidate(file.readText()))
    }

    @Test fun `the real migrated watercolor course loads and validates end-to-end`() {
        val file = File("src/main/assets/academy/watercolor.json")
        assertTrue("expected bundled asset at ${file.absolutePath}", file.exists())

        val course = AcademyContentLoader.parseAndValidate(file.readText(), sourceLabel = file.path)

        assertEquals("watercolor", course.id)
        assertEquals("Watercolor Technique", course.title)
        assertEquals(Instructors.dune.id, course.instructorId)
        assertEquals(4, course.lessons.size)
        assertEquals(
            listOf(
                "digital-watercolor-behavior",
                "layering-washes",
                "soft-vs-defined-edges",
                "color-mixing-on-page",
            ),
            course.lessons.map { it.id },
        )
        course.lessons.forEach { lesson ->
            assertFalse("lesson '${lesson.id}' should have real content blocks", lesson.blocks.isEmpty())
            assertEquals(null, lesson.demo)
        }
        assertEquals(course, AcademyContentLoader.parseAndValidate(file.readText()))
    }

    /**
     * The real proof migration for [LessonBlockDto.Diagram]: [CoursePerspective], the first course
     * whose content includes real diagrams (not just Heading/Paragraph/BulletList/Tip). Picked for
     * exactly that reason -- it's Diagram-heavy and has no [LessonDemo], a clean first test of just
     * the new diagram capability.
     */
    @Test fun `the real migrated perspective course loads and validates end-to-end, including its two diagrams`() {
        val file = File("src/main/assets/academy/perspective.json")
        assertTrue("expected bundled asset at ${file.absolutePath}", file.exists())

        val course = AcademyContentLoader.parseAndValidate(file.readText(), sourceLabel = file.path)

        assertEquals("perspective", course.id)
        assertEquals("Perspective Basics", course.title)
        assertEquals(Instructors.rowan.id, course.instructorId)
        assertEquals(5, course.lessons.size)
        assertEquals(
            listOf(
                "what-perspective-solves",
                "one-point-perspective",
                "two-point-perspective",
                "eye-level-and-horizon",
                "perspective-everyday-objects",
            ),
            course.lessons.map { it.id },
        )
        course.lessons.forEach { lesson ->
            assertFalse("lesson '${lesson.id}' should have real content blocks", lesson.blocks.isEmpty())
            assertEquals(null, lesson.demo)
        }

        // Unlike the end-to-end tests above, this one does NOT re-parse and `assertEquals` the
        // whole Course: LessonBlock.Diagram wraps a real `(Canvas, Int) -> Unit` closure, and two
        // separately-built closures for the identical op list are never `==` to each other (Kotlin
        // function types compare by reference, not content) -- a property of LessonBlock.Diagram
        // itself, unrelated to this migration. Instead, confirm the two real diagrams landed as
        // real LessonBlock.Diagram values carrying their real captions.
        val onePointDiagram = course.lessons.single { it.id == "one-point-perspective" }.blocks
            .filterIsInstance<LessonBlock.Diagram>().single()
        assertEquals("A horizon line, one vanishing point, and a box built from it", onePointDiagram.caption)

        val twoPointDiagram = course.lessons.single { it.id == "two-point-perspective" }.blocks
            .filterIsInstance<LessonBlock.Diagram>().single()
        assertEquals("One vertical corner edge, two vanishing points, two receding faces", twoPointDiagram.caption)
    }
}
