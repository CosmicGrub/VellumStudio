package com.vellum.studio.academy

/**
 * Course 2 of the Academy. Builds directly on Drawing Foundations — assumes the reader has already
 * done the loosening-up, basic-shapes, and volume lessons from that course.
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * as the proof migration for [LessonBlockDto.Diagram]/[DiagramOpDto]/[DiagramRenderer]: this course
 * was chosen specifically because it's Diagram-heavy (the "one-point-perspective" and
 * "two-point-perspective" lessons each carry one) and has no [LessonDemo], making it a clean first
 * test of just the new diagram capability with nothing else in the way. Every diagram op below was
 * transcribed directly from this object's own prior hand-coded `Canvas`/`Paint` closures --
 * coordinates, colors, and stroke widths are the exact same `s * 0.NNNf` arithmetic and
 * `Color.rgb(...)` triples that were already here, just expressed as data instead of code. The
 * actual content now lives in `app/src/main/assets/academy/perspective.json`; this object's only
 * job is loading and validating it once, eagerly, so a malformed edit to that JSON file fails
 * loudly the moment this object is first touched -- which [AcademyLibrary.all] touches
 * unconditionally, so in practice at first app launch or first test run, never silently at some
 * later point a real user reaches mid-lesson.
 */
object CoursePerspective {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/perspective.json")
}
