package com.vellum.studio.academy

/**
 * The friendly, approachable basics for sketching people with confidence -- simple construction
 * tricks for heads, faces, and full figures, kept practical rather than an exhaustive
 * medical-illustration deep dive.
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * as part of the "remaining migrations" pass. Unlike CourseFoundations/CourseColorTheory, this
 * course's two diagrams (the Loomis head construction and the 7.5-head-tall figure ruler) needed no
 * format changes at all -- every drawing call in both (confirmed by reading the original closures)
 * is already within the existing [DiagramOpDto] vocabulary: `drawCircle`, `drawLine`,
 * `drawPath`(moveTo/quadTo/lineTo), and `drawText`. Every diagram op below was transcribed directly
 * from this object's own prior hand-coded `Canvas`/`Paint` closures. The actual content now lives in
 * `app/src/main/assets/academy/anatomy.json`.
 */
object CourseAnatomy {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/anatomy.json")
}
