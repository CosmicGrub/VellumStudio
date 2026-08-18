package com.vellum.studio.academy

import android.graphics.Canvas

/**
 * One structured piece of lesson content, rendered top-to-bottom in a lesson screen. Kept as a
 * small closed set of block types (not raw markup/HTML) so the renderer stays simple and every
 * lesson — regardless of who authored it — looks visually consistent with the rest of the app.
 */
sealed class LessonBlock {
    data class Heading(val text: String) : LessonBlock()
    data class Paragraph(val text: String) : LessonBlock()
    data class BulletList(val items: List<String>) : LessonBlock()

    /** A short encouraging callout — a tip, a reassurance, a "don't worry if this feels hard yet". */
    data class Tip(val text: String) : LessonBlock()

    /**
     * A simple illustrative diagram, drawn fresh each time it's shown (same technique as the
     * coloring-book templates: plain android.graphics Path/Canvas, no bitmap assets). Unlike
     * coloring-page line art, diagrams MAY use filled shapes/gradients where that's the clearest
     * way to teach something (e.g. actually shading a sphere to demonstrate light logic) — the
     * "stroke only" rule is specific to coloring pages, not teaching diagrams.
     */
    data class Diagram(val caption: String, val draw: (canvas: Canvas, size: Int) -> Unit) : LessonBlock()

    /**
     * A real reference image bundled under assets/ (a genuine public-domain painting scan, not
     * procedural art) — for "study the real thing" moments a hand-drawn Diagram can't provide.
     * [assetPath] is relative to assets/, e.g. "masterworks/starry_night_reference.jpg".
     */
    data class MasterworkReference(
        val caption: String,
        val assetPath: String,
        val attribution: String,
    ) : LessonBlock()
}

data class Lesson(
    val id: String,
    val title: String,
    /** One sentence shown in the lesson list, before the user taps in. */
    val summary: String,
    val blocks: List<LessonBlock>,
    /** Optional hand-authored demo for this lesson's flagship exercise — see DemoPlayer. */
    val demo: LessonDemo? = null,
)

data class Course(
    val id: String,
    val title: String,
    val description: String,
    val lessons: List<Lesson>,
    /** Which [Instructor] teaches this course — see Instructor.kt for the persona roster. */
    val instructorId: String,
)

/**
 * A recorded, replayable drawing demo: one or more [DemoStage]s, each a caption plus the strokes
 * that illustrate it. [DemoStroke.path] lives in normalized 0..1 coordinates (independent of
 * whatever canvas size actually plays it back on) and is walked via PathMeasure at playback time,
 * feeding real points through the app's actual brush-rendering pipeline — see DemoPlayer — so a
 * demo looks exactly like a real hand-drawn stroke, not a canned animation.
 */
data class LessonDemo(val stages: List<DemoStage>)

data class DemoStage(val caption: String, val strokes: List<DemoStroke>)

data class DemoStroke(
    val path: android.graphics.Path,
    val brushId: String,
    val colorArgb: Int,
    val sizeMultiplier: Float = 1f,
)
