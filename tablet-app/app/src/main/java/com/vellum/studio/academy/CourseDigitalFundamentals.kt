package com.vellum.studio.academy

/**
 * The stylus-specific, practical skills that make Vellum Studio feel natural in your hand --
 * pressure control, navigation, layers, undo, and picking the right brush for the job. Taught by
 * Marisol (see Instructor.kt).
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * in the same pass that migrated CourseWatercolor: every block here was already plain text --
 * Heading, Paragraph, BulletList, Tip only, no [LessonBlock.Diagram] and no [LessonDemo] -- so it
 * fits the format's scope exactly, same as the pilot [CoursePhotoReference]. The actual content now
 * lives in `app/src/main/assets/academy/digital-fundamentals.json`; this object's only job is
 * loading and validating it once, eagerly, so a malformed edit to that JSON file fails loudly the
 * moment this object is first touched -- which [AcademyLibrary.all] touches unconditionally, so in
 * practice at first app launch or first test run, never silently at some later point a real user
 * reaches mid-lesson.
 */
object CourseDigitalFundamentals {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/digital-fundamentals.json")
}
