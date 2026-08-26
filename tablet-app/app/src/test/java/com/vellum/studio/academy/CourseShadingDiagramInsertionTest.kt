package com.vellum.studio.academy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [CourseShading.withHandAuthoredDiagram] is plain list-splicing logic over [Lesson]/[LessonBlock]
 * -- it never invokes a [LessonBlock.Diagram.draw] closure, only stores it -- so, like every other
 * pure-Kotlin piece of the Academy content pipeline, it's directly testable here with a synthetic
 * [Lesson] and a dummy (never-invoked) [LessonBlock.Diagram], no Robolectric required. This covers
 * the one piece of [CourseShading]'s partial migration [AcademyContentLoaderTest] can't: where the
 * hand-authored diagram actually lands back in the lesson's block list.
 */
class CourseShadingDiagramInsertionTest {

    private val dummyDiagram = LessonBlock.Diagram(caption = "dummy", draw = { _, _ -> })

    private fun lessonWithBlocks(vararg blocks: LessonBlock) = Lesson(
        id = "test-lesson",
        title = "Test Lesson",
        summary = "A lesson for testing.",
        blocks = blocks.toList(),
    )

    @Test fun `inserts the diagram immediately before the first Tip block`() {
        val lesson = lessonWithBlocks(
            LessonBlock.Paragraph("intro"),
            LessonBlock.Heading("heading"),
            LessonBlock.Paragraph("more text"),
            LessonBlock.Tip("first tip"),
            LessonBlock.Tip("second tip"),
        )

        val patched = withHandAuthoredDiagram(lesson, dummyDiagram)

        assertEquals(
            listOf("paragraph", "heading", "paragraph", "diagram", "tip", "tip"),
            patched.blocks.map {
                when (it) {
                    is LessonBlock.Paragraph -> "paragraph"
                    is LessonBlock.Heading -> "heading"
                    is LessonBlock.Diagram -> "diagram"
                    is LessonBlock.Tip -> "tip"
                    else -> "other"
                }
            },
        )
        assertSame(dummyDiagram, patched.blocks[3])
    }

    @Test fun `appends the diagram at the end when the lesson has no Tip block`() {
        val lesson = lessonWithBlocks(
            LessonBlock.Paragraph("intro"),
            LessonBlock.Heading("heading"),
        )

        val patched = withHandAuthoredDiagram(lesson, dummyDiagram)

        assertEquals(3, patched.blocks.size)
        assertSame(dummyDiagram, patched.blocks.last())
    }

    @Test fun `does not mutate the fields other than blocks`() {
        val lesson = lessonWithBlocks(LessonBlock.Tip("only tip"))

        val patched = withHandAuthoredDiagram(lesson, dummyDiagram)

        assertEquals(lesson.id, patched.id)
        assertEquals(lesson.title, patched.title)
        assertEquals(lesson.summary, patched.summary)
        assertEquals(lesson.demo, patched.demo)
    }
}
