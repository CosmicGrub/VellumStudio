package com.vellum.studio.academy

/**
 * Get real, practical mileage out of Vellum Studio's Watercolor brush -- how its pigment mixing and
 * layering actually behave, and how to pair it with other brushes for a finished look. Taught by
 * Dune (see Instructor.kt).
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * in the same pass that migrated CourseDigitalFundamentals: every block here was already plain text
 * -- Heading, Paragraph, BulletList, Tip only, no [LessonBlock.Diagram] and no [LessonDemo] -- so it
 * fits the format's scope exactly, same as the pilot [CoursePhotoReference]. The actual content now
 * lives in `app/src/main/assets/academy/watercolor.json`; this object's only job is loading and
 * validating it once, eagerly, so a malformed edit to that JSON file fails loudly the moment this
 * object is first touched -- which [AcademyLibrary.all] touches unconditionally, so in practice at
 * first app launch or first test run, never silently at some later point a real user reaches
 * mid-lesson.
 */
object CourseWatercolor {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/watercolor.json")
}
