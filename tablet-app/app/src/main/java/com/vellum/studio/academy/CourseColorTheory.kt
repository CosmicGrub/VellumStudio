package com.vellum.studio.academy

/**
 * "Color Theory" - five lessons that demystify the app's own HSV picker and Palette feature,
 * then build practical color-scheme habits (complementary/analogous, warm/cool, and planning a
 * palette upfront) on top of that foundation.
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * as part of the "remaining migrations" pass. This course needed [LessonBlockDto.Diagram]'s new
 * [DiagramOpDto.Arc] case (added specifically for this migration): the "color-wheel" lesson's
 * twelve-wedge color wheel is drawn as twelve `canvas.drawArc(..., useCenter = true, ...)` pie
 * slices -- a small, direct generalization of [DiagramOpDto.Rect]'s own fill/stroke shape (a wedge
 * of an ellipse instead of the whole rect), not a new class of capability. Every diagram op below,
 * including the trigonometry behind the wedge boundaries and callout lines, was transcribed
 * directly from this object's own prior hand-coded `drawColorWheel` closure (same `angleToPoint`
 * math, same colors, same angles). The actual content now lives in
 * `app/src/main/assets/academy/color-theory.json`.
 */
object CourseColorTheory {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/color-theory.json")
}
