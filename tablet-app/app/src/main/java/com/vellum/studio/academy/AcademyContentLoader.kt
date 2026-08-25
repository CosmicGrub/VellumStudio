package com.vellum.studio.academy

import com.vellum.studio.VellumApp
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Thrown for any problem with a piece of Academy JSON content -- malformed JSON, a missing/blank
 * required field, an unrecognized block `type`, an `instructorId` that doesn't match any real
 * [Instructor], a duplicate or blank lesson id, an unsupported [CourseContentDto.schemaVersion], or
 * an empty lessons/blocks list. A plain, unchecked [RuntimeException] thrown straight out of
 * [AcademyContentLoader], deliberately never caught internally: this content is authored and
 * bundled at build time, never user-supplied, so the right place for a mistake here to surface is a
 * failed build/test run during development -- exactly the "fails loudly, not silently at runtime
 * for a user" goal -- not a caught-and-logged error path a real user could ever actually reach.
 */
class AcademyContentException(message: String) : RuntimeException(message)

/**
 * Loads and validates data-driven Academy course content -- see [CourseContentDto] for the on-disk
 * shape and exactly which [LessonBlock] variants it can express -- and converts it into the same
 * [Course]/[Lesson]/[LessonBlock] domain model every hand-authored `CourseXxx.kt` object builds
 * directly.
 *
 * Split into two layers on purpose, the same "pure logic vs. untestable I/O shell" separation
 * [PhotoConverter.eligibleForPaintByNumber] uses for the same reason: [parseAndValidate] is plain
 * Kotlin + kotlinx.serialization, no [android.content.Context] anywhere in it, so every validation
 * rule is directly unit-testable with a JSON string literal and zero Robolectric/Android
 * scaffolding. [loadFromAssets] is the thin, untested-by-necessity shell around it that does the
 * one thing that actually needs a running app: read the bytes out of the APK's bundled assets.
 *
 * No migration engine here (contrast [com.vellum.studio.model.ProjectSchemaMigrator]) -- there is
 * exactly one schema version and zero on-disk history to migrate, the same starting point
 * [com.vellum.studio.model.ProjectMeta] itself was at before its own first real migration. The day
 * this shape needs to change for a real reason, that's a [CURRENT_SCHEMA_VERSION] bump plus either
 * a rewrite of every already-authored JSON file under `assets/academy/` (there are only ever a
 * handful, all in this repo, none on an end user's device) or a small migrator step added the same way
 * [com.vellum.studio.model.ProjectSchemaMigrator] was -- whichever a real future change actually
 * calls for, not speculative machinery built for a change that doesn't exist yet.
 */
object AcademyContentLoader {
    /** Bump alongside a real, deliberate shape change to [CourseContentDto] -- see that class's doc. */
    const val CURRENT_SCHEMA_VERSION = 1

    // Strict on purpose -- the opposite tradeoff from ProjectRepository's Json (which must tolerate
    // old/corrupted on-disk USER project files without ever crashing a real user's app). This
    // content is authored and reviewed before it ships, never user-supplied, so an unknown field
    // (e.g. a typo'd key) or a missing required one should fail a build/test loudly instead of
    // silently decoding into something the author didn't intend.
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Loads and validates [assetPath] (relative to `assets/`, e.g. "academy/photo-reference-tools.json"). */
    fun loadFromAssets(assetPath: String): Course {
        val text = VellumApp.instance.assets.open(assetPath).bufferedReader().use { it.readText() }
        return parseAndValidate(text, sourceLabel = "assets/$assetPath")
    }

    /**
     * Parses, validates, and converts raw Academy course JSON -- the part of this object worth
     * unit-testing directly (see [AcademyContentLoaderTest]), independent of any Android asset I/O.
     * [sourceLabel] is purely for error messages (a file path, or a test's own description of what
     * it's feeding in) so a validation failure names where the bad content came from.
     */
    fun parseAndValidate(sourceJson: String, sourceLabel: String = "<inline>"): Course {
        val dto = try {
            json.decodeFromString(CourseContentDto.serializer(), sourceJson)
        } catch (e: SerializationException) {
            throw AcademyContentException("Malformed Academy course content in $sourceLabel: ${e.message}")
        }
        validate(dto, sourceLabel)
        return dto.toCourse()
    }

    private fun validate(dto: CourseContentDto, sourceLabel: String) {
        fun fail(reason: String): Nothing =
            throw AcademyContentException("Invalid Academy course content in $sourceLabel: $reason")

        if (dto.schemaVersion != CURRENT_SCHEMA_VERSION) {
            fail("schemaVersion ${dto.schemaVersion} is not supported (this build only understands $CURRENT_SCHEMA_VERSION)")
        }
        if (dto.id.isBlank()) fail("course id is blank")
        if (dto.title.isBlank()) fail("course '${dto.id}' has a blank title")
        if (dto.description.isBlank()) fail("course '${dto.id}' has a blank description")
        if (dto.instructorId.isBlank()) fail("course '${dto.id}' has a blank instructorId")
        if (Instructors.byId(dto.instructorId) == null) {
            val known = Instructors.all.joinToString(", ") { it.id }
            fail("course '${dto.id}' references unknown instructorId '${dto.instructorId}' (known instructors: $known)")
        }
        if (dto.lessons.isEmpty()) fail("course '${dto.id}' has no lessons")

        val seenLessonIds = HashSet<String>()
        dto.lessons.forEachIndexed { lessonIndex, lesson ->
            val lessonLabel = "course '${dto.id}' lesson #$lessonIndex"
            if (lesson.id.isBlank()) fail("$lessonLabel has a blank id")
            if (!seenLessonIds.add(lesson.id)) fail("course '${dto.id}' has a duplicate lesson id '${lesson.id}'")
            val namedLessonLabel = "$lessonLabel ('${lesson.id}')"
            if (lesson.title.isBlank()) fail("$namedLessonLabel has a blank title")
            if (lesson.summary.isBlank()) fail("$namedLessonLabel has a blank summary")
            if (lesson.blocks.isEmpty()) fail("$namedLessonLabel has no content blocks")

            lesson.blocks.forEachIndexed { blockIndex, block ->
                val blockLabel = "$namedLessonLabel block #$blockIndex"
                when (block) {
                    is LessonBlockDto.Heading ->
                        if (block.text.isBlank()) fail("$blockLabel (heading) has blank text")
                    is LessonBlockDto.Paragraph ->
                        if (block.text.isBlank()) fail("$blockLabel (paragraph) has blank text")
                    is LessonBlockDto.Tip ->
                        if (block.text.isBlank()) fail("$blockLabel (tip) has blank text")
                    is LessonBlockDto.BulletList -> {
                        if (block.items.isEmpty()) fail("$blockLabel (bulletList) has no items")
                        if (block.items.any { it.isBlank() }) fail("$blockLabel (bulletList) has a blank item")
                    }
                    is LessonBlockDto.MasterworkReference -> {
                        if (block.caption.isBlank()) fail("$blockLabel (masterworkReference) has a blank caption")
                        if (block.assetPath.isBlank()) fail("$blockLabel (masterworkReference) has a blank assetPath")
                        if (block.attribution.isBlank()) fail("$blockLabel (masterworkReference) has a blank attribution")
                    }
                }
            }
        }
    }

    private fun CourseContentDto.toCourse(): Course = Course(
        id = id,
        title = title,
        description = description,
        instructorId = instructorId,
        lessons = lessons.map { it.toLesson() },
    )

    private fun LessonContentDto.toLesson(): Lesson = Lesson(
        id = id,
        title = title,
        summary = summary,
        blocks = blocks.map { it.toLessonBlock() },
    )

    private fun LessonBlockDto.toLessonBlock(): LessonBlock = when (this) {
        is LessonBlockDto.Heading -> LessonBlock.Heading(text)
        is LessonBlockDto.Paragraph -> LessonBlock.Paragraph(text)
        is LessonBlockDto.Tip -> LessonBlock.Tip(text)
        is LessonBlockDto.BulletList -> LessonBlock.BulletList(items)
        is LessonBlockDto.MasterworkReference -> LessonBlock.MasterworkReference(caption, assetPath, attribution)
    }
}
