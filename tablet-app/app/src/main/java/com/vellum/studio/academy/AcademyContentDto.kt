package com.vellum.studio.academy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-disk (JSON, bundled under `assets/academy/`) shape for one [Course]'s static content --
 * title/summary/body text and tip callouts. [AcademyContentLoader] parses, validates, and converts
 * this into the exact same [Course]/[Lesson]/[LessonBlock] domain model every hand-authored
 * `CourseXxx.kt` object builds directly, so nothing downstream of that conversion (AcademyLibrary,
 * AcademyScreen, CourseDetailScreen, LessonScreen, AcademyProgressRepository, DemoPlayer) needs to
 * know or care whether a given course came from a JSON file or a Kotlin object literal.
 *
 * Deliberately narrower than the full [LessonBlock] sealed hierarchy: [LessonBlock.Diagram] (a
 * `(Canvas, Int) -> Unit` drawing closure) and [LessonDemo] (real [android.graphics.Path] data fed
 * through the app's actual brush-rendering pipeline -- see DemoPlayer) are genuine CODE, not
 * authorable content, so there's no JSON shape for either one here on purpose. A course that needs
 * a hand-drawn diagram or a flagship playback demo stays a hand-authored `CourseXxx.kt` object,
 * same as today -- this format only covers the lessons (or the parts of a lesson) that are pure
 * text plus the occasional bundled reference image, which is most of what a course actually is.
 *
 * [schemaVersion] exists from day one, the same reasoning as [ProjectMeta.schemaVersion] in
 * model/Project.kt: the day this shape needs to change, every already-authored JSON file is either
 * migrated or explicitly re-versioned by [AcademyContentLoader], never silently misread by a loader
 * that assumes today's shape forever. Unlike [ProjectMeta] there's no migration engine here yet --
 * see [AcademyContentLoader]'s own doc for why that's the right amount of machinery for a format
 * with exactly one version and zero on-disk history to migrate.
 */
@Serializable
data class CourseContentDto(
    val schemaVersion: Int,
    val id: String,
    val title: String,
    val description: String,
    /** Must match a real [Instructor.id] from [Instructors] -- checked by [AcademyContentLoader], not just assumed. */
    val instructorId: String,
    val lessons: List<LessonContentDto> = emptyList(),
)

@Serializable
data class LessonContentDto(
    val id: String,
    val title: String,
    val summary: String,
    val blocks: List<LessonBlockDto> = emptyList(),
)

/**
 * Data-only mirror of [LessonBlock]'s text-authorable variants. kotlinx.serialization dispatches on
 * a `"type"` field by default for a `@Serializable sealed class` hierarchy like this one (no custom
 * discriminator config needed) -- the `@SerialName` on each case below is exactly the string a
 * lesson author writes as that block's `"type"` value in JSON.
 */
@Serializable
sealed class LessonBlockDto {
    @Serializable
    @SerialName("heading")
    data class Heading(val text: String) : LessonBlockDto()

    @Serializable
    @SerialName("paragraph")
    data class Paragraph(val text: String) : LessonBlockDto()

    @Serializable
    @SerialName("bulletList")
    data class BulletList(val items: List<String> = emptyList()) : LessonBlockDto()

    @Serializable
    @SerialName("tip")
    data class Tip(val text: String) : LessonBlockDto()

    @Serializable
    @SerialName("masterworkReference")
    data class MasterworkReference(
        val caption: String,
        val assetPath: String,
        val attribution: String,
    ) : LessonBlockDto()
}
