package com.vellum.studio.academy

/**
 * Wet-on-wet ("alla prima") landscape painting: build a whole simple landscape in one sitting by
 * blending colors while they're still wet on the canvas, instead of waiting for layers to dry.
 * Taught by Dune (see Instructor.kt) — original persona, original demo content. The *technique*
 * this course teaches is real and long predates any one teacher of it; nothing here is modeled on
 * or attributed to any real person's specific paintings, name, or likeness.
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * as the proof migration for [DemoSpecDto]/[DemoSpecBuilder]: this course was chosen specifically
 * because its flagship "sky-mountain-water" lesson carries a real [LessonDemo] and no
 * [LessonBlock.Diagram] blocks, making it a clean first test of just the new demo capability with
 * nothing else in the way. Every stroke below was transcribed directly from this object's own prior
 * hand-coded `Path`/`DemoStroke` construction — the exact same moveTo/lineTo coordinates,
 * `brushId`s, `0xFFrrggbb` colors (now written as plain "#RRGGBB" hex, since the format's color
 * fields are always fully opaque, matching how every one of these colors was already opaque), and
 * `sizeMultiplier`s that were already here, just expressed as data instead of code. The actual
 * content now lives in `app/src/main/assets/academy/wet-on-wet.json`; this object's only job is
 * loading and validating it once, eagerly, so a malformed edit to that JSON file fails loudly the
 * moment this object is first touched -- which [AcademyLibrary.all] touches unconditionally, so in
 * practice at first app launch or first test run, never silently at some later point a real user
 * reaches mid-lesson.
 */
object CourseWetOnWet {
    val course: Course = AcademyContentLoader.loadFromAssets("academy/wet-on-wet.json")
}
