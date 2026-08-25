package com.vellum.studio.academy

/**
 * The newest tools in Vellum Studio, taught plainly: turning a photo from your own library into a
 * coloring page (line art, a full-color reference, and sometimes a paint-by-number activity), and
 * pulling up an on-device pose skeleton over a reference photo to guide figure drawing. Both tools
 * are genuinely new capabilities added in this expansion (see canvas/PhotoConverter.kt and
 * canvas/PoseOverlay.kt), so this course teaches their real, current mechanics -- not marketing
 * copy -- the same way every other Academy course teaches this app's actual buttons and workflows.
 * Taught by Rowan (see Instructor.kt) -- reused rather than inventing a new persona because Rowan's
 * "here's precisely why this works" style is the right fit for explaining on-device tools honestly,
 * including where they don't work, and Rowan already teaches this app's other figure-drawing course.
 *
 * This is the pilot course for the data-driven Academy content format (see AcademyContentDto /
 * AcademyContentLoader): picked because every block here is plain text -- Heading, Paragraph,
 * BulletList, Tip only, no [LessonBlock.Diagram] and no [LessonDemo] -- so it fits the new format's
 * scope exactly, with nothing left over that still needs to be hand-authored Kotlin. The actual
 * content now lives in `app/src/main/assets/academy/photo-reference-tools.json`; this object's only
 * job is loading and validating it once. Loading it eagerly here, rather than lazily deep inside
 * whichever screen first renders a lesson, means a malformed edit to that JSON file fails loudly
 * the moment this object is first touched -- which [AcademyLibrary.all] touches unconditionally, so
 * in practice at first app launch or first test run, never silently at some later point a real user
 * reaches mid-lesson.
 */
object CoursePhotoReference {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/photo-reference-tools.json")
}
