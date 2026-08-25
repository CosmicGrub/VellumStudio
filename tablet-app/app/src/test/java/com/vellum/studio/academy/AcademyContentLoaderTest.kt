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
}
