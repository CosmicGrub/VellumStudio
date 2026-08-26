package com.vellum.studio.academy

/**
 * Graffiti lettering and style: tag anatomy, the construction order a piece is actually built in
 * (sketch, outline, fill, highlight, texture), and the five graffiti-specific brushes
 * (BrushPresets.SprayCan/FatCapOutline/WildstyleChisel/Drip/Stencil) that aren't locked to this
 * course -- they're just five more entries in the app's normal brush lineup, usable anywhere.
 * Taught by Kai (see Instructor.kt) -- original persona.
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * as part of the "remaining migrations" pass. This course has no [LessonBlock.Diagram] blocks at
 * all -- only its flagship "bubble-letters-throw-up" lesson's [LessonDemo], whose four strokes
 * (skeleton/outline/fill/highlight) were already built entirely from moveTo/quadTo/cubicTo, the
 * exact vocabulary [DemoStrokeDto]/[PathCommandDto] already cover, so no format changes were needed
 * here. Every stroke below was transcribed directly from this object's own prior hand-coded `Path`/
 * `DemoStroke` construction -- the exact same coordinates, `brushId`s, and colors (now "#RRGGBB"
 * hex, since they were already fully opaque). The actual content now lives in
 * `app/src/main/assets/academy/graffiti.json`.
 */
object CourseGraffiti {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/graffiti.json")
}
