package com.vellum.studio.academy

import kotlinx.serialization.Serializable

/**
 * On-disk (JSON) shape for an optional [LessonContentDto.demo] field -- the flagship recorded,
 * replayable drawing demo a lesson can carry (see [LessonDemo]/[DemoStage]/[DemoStroke], and
 * [DemoPlayer], which actually plays one back through the app's real stroke-rendering pipeline).
 * This is the one new capability this pass adds to [CourseContentDto]'s existing
 * Heading/Paragraph/BulletList/Tip/MasterworkReference/Diagram vocabulary.
 *
 * A demo stroke's path is expressed with exactly the same normalized-0..1 [PathCommandDto]
 * vocabulary [DiagramOpDto.Path] already uses (moveTo/lineTo/quadTo/cubicTo) -- reused rather than
 * duplicated, since a demo stroke's path is built from precisely the same primitives a diagram
 * path is (confirmed by grep across every `CourseXxx.kt` [LessonDemo]: most use only moveTo/lineTo,
 * a few also use quadTo/cubicTo -- see CourseGraffiti.kt/CourseAnatomy.kt/CourseShading.kt/
 * CourseFoundations.kt). Unlike a [DiagramOpDto], a demo stroke's coordinates are NOT later
 * multiplied by any canvas size: [DemoStroke.path] itself lives in normalized 0..1 space (see that
 * class's own doc), and [DemoPlayer] is the one place that scales it, via a real
 * [android.graphics.Matrix], to whatever pixel size actually plays it back -- so [DemoSpecBuilder]
 * builds each path directly from the raw 0..1 command coordinates, with no size factor involved.
 *
 * [DemoSpecBuilder] is the small, genuinely Android-dependent (real [android.graphics.Path] /
 * [android.graphics.Color]) conversion layer this DTO feeds, kept out of
 * [AcademyContentLoader.parseAndValidate] itself -- see [DemoSpecBuilder]'s own doc for why that
 * split matters here specifically, more than it did for [DiagramOpDto].
 */
@Serializable
data class DemoSpecDto(val stages: List<DemoStageDto> = emptyList())

@Serializable
data class DemoStageDto(val caption: String, val strokes: List<DemoStrokeDto> = emptyList())

@Serializable
data class DemoStrokeDto(
    val path: List<PathCommandDto> = emptyList(),
    /**
     * Must match a real [com.vellum.studio.canvas.BrushPresets] id -- checked explicitly by
     * [AcademyContentLoader], not just assumed: [com.vellum.studio.canvas.BrushPresets.byId] itself
     * silently falls back to Pencil for an unrecognized id, which would otherwise let a typo'd
     * brushId ship silently instead of failing loudly at build/test time.
     */
    val brushId: String,
    /** Opaque "#RRGGBB" hex color -- same convention as [DiagramOpDto]'s color fields, parsed with an always-FF alpha channel by [DemoSpecBuilder]. */
    val color: String,
    val sizeMultiplier: Float = 1f,
)
