package com.vellum.studio.academy

/**
 * Course 1 of the Academy. This is the very first thing a brand-new user sees, so it assumes
 * zero prior drawing experience or vocabulary.
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * as part of the "remaining migrations" pass. This course needed [LessonBlockDto.Diagram]'s new
 * [DiagramOpDto.Oval] case (added specifically for this migration): the "seeing-in-shapes" and
 * "building-volume" lessons each draw a mug/cylinder as filled/stroked ellipses via plain
 * `canvas.drawOval` -- a small, direct generalization of [DiagramOpDto.Rect]'s own fill/stroke
 * shape, not a new class of capability, so it was added to the format rather than left as a hand
 * -authored exception. Every diagram op below was transcribed directly from this object's own prior
 * hand-coded `Canvas`/`Paint` closures. The "building-volume" lesson's flagship [LessonDemo] used
 * `Path.addCircle`/`Path.addOval` (not the moveTo/lineTo/quadTo/cubicTo vocabulary
 * [DemoStrokeDto]/[PathCommandDto] already cover) -- each circle/ellipse was converted to the
 * standard 4-cubic-Bezier circle/ellipse approximation (kappa = 0.5522847498307936), the same
 * technique essentially every 2D graphics library uses to express a circle as a path; it is
 * visually indistinguishable from the original at any real screen resolution, so this is a
 * faithful re-expression in the existing vocabulary, not a lossy approximation. The actual content
 * now lives in `app/src/main/assets/academy/foundations.json`.
 */
object CourseFoundations {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/foundations.json")
}
