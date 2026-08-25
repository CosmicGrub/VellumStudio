package com.vellum.studio.academy

import com.vellum.studio.VellumApp
import com.vellum.studio.canvas.BrushPresets
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

    /** Matches an opaque "#RRGGBB" hex color -- the shape every [DiagramOpDto] color field expects. */
    private val HEX_COLOR_REGEX = Regex("^#[0-9A-Fa-f]{6}$")

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
            lesson.demo?.let { validateDemo(it, namedLessonLabel, ::fail) }

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
                    is LessonBlockDto.Diagram -> {
                        if (block.caption.isBlank()) fail("$blockLabel (diagram) has a blank caption")
                        if (block.ops.isEmpty()) fail("$blockLabel (diagram) has no ops")
                        block.ops.forEachIndexed { opIndex, op ->
                            validateDiagramOp(op, "$blockLabel (diagram) op #$opIndex", ::fail)
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies the same "fail loudly, at build/test time" discipline to every [DiagramOpDto]: valid
     * hex colors, at least one of fill/stroke actually present on a shape op, a positive stroke
     * width whenever a stroke color is given, coordinates within the normalized 0..1 canvas every
     * hand-coded Diagram closure already assumes, and a non-empty [DiagramOpDto.Path.commands]
     * list starting with a `moveTo` (mirroring what every real hand-coded Diagram path does, and
     * what `android.graphics.Path` itself needs for a sensible outline).
     */
    private fun validateDiagramOp(op: DiagramOpDto, label: String, fail: (String) -> Nothing) {
        fun requireUnitRange(value: Float, fieldName: String) {
            if (value < 0f || value > 1f) fail("$label has $fieldName $value outside the normalized 0..1 range")
        }

        // A pure-Kotlin format check (never android.graphics.Color.parseColor) so this validation --
        // like the rest of AcademyContentLoader's pure logic -- stays a plain JVM unit test with no
        // Robolectric/Android scaffolding; see this class's own doc for why that separation matters.
        fun requireValidHexColor(hexColor: String, fieldName: String) {
            if (!HEX_COLOR_REGEX.matches(hexColor)) {
                fail("$label has an invalid $fieldName '$hexColor' (expected a '#RRGGBB' hex color)")
            }
        }

        fun requireValidAlpha(alpha: Float) {
            if (alpha < 0f || alpha > 1f) fail("$label has alpha $alpha outside the 0..1 range")
        }

        fun requireValidDash(dash: List<Float>?) {
            if (dash != null && (dash.size != 2 || dash.any { it <= 0f })) {
                fail("$label has an invalid dash pattern $dash (expected exactly 2 positive fractions: [dashLength, gapLength])")
            }
        }

        fun requireFillOrStroke(fillColor: String?, strokeColor: String?, strokeWidth: Float) {
            if (fillColor == null && strokeColor == null) fail("$label has neither a fillColor nor a strokeColor")
            fillColor?.let { requireValidHexColor(it, "fillColor") }
            strokeColor?.let {
                requireValidHexColor(it, "strokeColor")
                if (strokeWidth <= 0f) fail("$label has a strokeColor but a non-positive strokeWidth ($strokeWidth)")
            }
        }

        when (op) {
            is DiagramOpDto.Line -> {
                requireUnitRange(op.x1, "x1"); requireUnitRange(op.y1, "y1")
                requireUnitRange(op.x2, "x2"); requireUnitRange(op.y2, "y2")
                requireValidHexColor(op.color, "color")
                if (op.strokeWidth <= 0f) fail("$label has a non-positive strokeWidth (${op.strokeWidth})")
                requireValidAlpha(op.alpha)
                requireValidDash(op.dash)
            }
            is DiagramOpDto.Circle -> {
                requireUnitRange(op.cx, "cx"); requireUnitRange(op.cy, "cy")
                if (op.r <= 0f) fail("$label has a non-positive radius (${op.r})")
                requireFillOrStroke(op.fillColor, op.strokeColor, op.strokeWidth)
                requireValidAlpha(op.alpha)
                requireValidDash(op.dash)
            }
            is DiagramOpDto.Rect -> {
                requireUnitRange(op.left, "left"); requireUnitRange(op.top, "top")
                requireUnitRange(op.right, "right"); requireUnitRange(op.bottom, "bottom")
                if (op.left >= op.right) fail("$label has left (${op.left}) >= right (${op.right})")
                if (op.top >= op.bottom) fail("$label has top (${op.top}) >= bottom (${op.bottom})")
                requireFillOrStroke(op.fillColor, op.strokeColor, op.strokeWidth)
                requireValidAlpha(op.alpha)
                requireValidDash(op.dash)
            }
            is DiagramOpDto.Text -> {
                requireUnitRange(op.x, "x"); requireUnitRange(op.y, "y")
                if (op.text.isBlank()) fail("$label (text) has blank text")
                requireValidHexColor(op.color, "color")
                if (op.textSize <= 0f) fail("$label has a non-positive textSize (${op.textSize})")
                requireValidAlpha(op.alpha)
            }
            is DiagramOpDto.Path -> {
                if (op.commands.isEmpty()) fail("$label (path) has no commands")
                if (op.commands.first() !is PathCommandDto.MoveTo) fail("$label (path) must start with a moveTo")
                requirePathCommandsInUnitRange(op.commands, label, fail)
                requireFillOrStroke(op.fillColor, op.strokeColor, op.strokeWidth)
                requireValidAlpha(op.alpha)
                requireValidDash(op.dash)
            }
        }
    }

    /**
     * Shared 0..1 coordinate-range check for every value in a moveTo/lineTo/quadTo/cubicTo command
     * list -- used by both [DiagramOpDto.Path] (above) and [DemoStrokeDto] (below), which express
     * path geometry with the exact same [PathCommandDto] vocabulary. Message shape mirrors
     * [validateDiagramOp]'s own `requireUnitRange` exactly ("$label has command #$i $fieldName
     * $value outside the normalized 0..1 range").
     */
    private fun requirePathCommandsInUnitRange(commands: List<PathCommandDto>, label: String, fail: (String) -> Nothing) {
        commands.forEachIndexed { i, command ->
            val coords = when (command) {
                is PathCommandDto.MoveTo -> listOf("x" to command.x, "y" to command.y)
                is PathCommandDto.LineTo -> listOf("x" to command.x, "y" to command.y)
                is PathCommandDto.QuadTo -> listOf(
                    "controlX" to command.controlX, "controlY" to command.controlY,
                    "x" to command.x, "y" to command.y,
                )
                is PathCommandDto.CubicTo -> listOf(
                    "control1X" to command.control1X, "control1Y" to command.control1Y,
                    "control2X" to command.control2X, "control2Y" to command.control2Y,
                    "x" to command.x, "y" to command.y,
                )
            }
            coords.forEach { (fieldName, value) ->
                if (value < 0f || value > 1f) fail("$label has command #$i $fieldName $value outside the normalized 0..1 range")
            }
        }
    }

    /**
     * Applies the same "fail loudly" discipline to an optional [Lesson.demo]: non-empty stages,
     * each with a non-blank caption and at least one stroke, and every stroke referencing a real
     * [com.vellum.studio.canvas.BrushPresets] id (rather than silently falling back to Pencil the
     * way [com.vellum.studio.canvas.BrushPresets.byId] itself does), a valid hex color, a positive
     * sizeMultiplier, and a well-formed path starting with a moveTo -- all pure-Kotlin checks, same
     * as everything else in this function, so a course without a demo never needs Robolectric to
     * validate (see [DemoSpecBuilder]'s own doc for why the *conversion* step, unlike this
     * validation step, does).
     */
    private fun validateDemo(demo: DemoSpecDto, lessonLabel: String, fail: (String) -> Nothing) {
        if (demo.stages.isEmpty()) fail("$lessonLabel demo has no stages")
        demo.stages.forEachIndexed { stageIndex, stage ->
            val stageLabel = "$lessonLabel demo stage #$stageIndex"
            if (stage.caption.isBlank()) fail("$stageLabel has a blank caption")
            if (stage.strokes.isEmpty()) fail("$stageLabel has no strokes")
            stage.strokes.forEachIndexed { strokeIndex, stroke ->
                val strokeLabel = "$stageLabel stroke #$strokeIndex"
                if (BrushPresets.all.none { it.id == stroke.brushId }) {
                    val known = BrushPresets.all.joinToString(", ") { it.id }
                    fail("$strokeLabel references unknown brushId '${stroke.brushId}' (known brushes: $known)")
                }
                if (!HEX_COLOR_REGEX.matches(stroke.color)) {
                    fail("$strokeLabel has an invalid color '${stroke.color}' (expected a '#RRGGBB' hex color)")
                }
                if (stroke.sizeMultiplier <= 0f) {
                    fail("$strokeLabel has a non-positive sizeMultiplier (${stroke.sizeMultiplier})")
                }
                if (stroke.path.isEmpty()) fail("$strokeLabel has no path commands")
                if (stroke.path.first() !is PathCommandDto.MoveTo) fail("$strokeLabel path must start with a moveTo")
                requirePathCommandsInUnitRange(stroke.path, strokeLabel, fail)
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
        demo = demo?.let { DemoSpecBuilder.build(it) },
    )

    private fun LessonBlockDto.toLessonBlock(): LessonBlock = when (this) {
        is LessonBlockDto.Heading -> LessonBlock.Heading(text)
        is LessonBlockDto.Paragraph -> LessonBlock.Paragraph(text)
        is LessonBlockDto.Tip -> LessonBlock.Tip(text)
        is LessonBlockDto.BulletList -> LessonBlock.BulletList(items)
        is LessonBlockDto.MasterworkReference -> LessonBlock.MasterworkReference(caption, assetPath, attribution)
        is LessonBlockDto.Diagram -> LessonBlock.Diagram(caption, DiagramRenderer.render(ops))
    }
}
