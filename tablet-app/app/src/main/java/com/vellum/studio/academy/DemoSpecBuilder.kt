package com.vellum.studio.academy

import android.graphics.Color
import android.graphics.Path

/**
 * Converts a validated [DemoSpecDto] into a real [LessonDemo] -- the one place JSON-authored demo
 * content becomes genuine [android.graphics.Path]/[android.graphics.Color] data.
 *
 * Deliberately kept out of [AcademyContentLoader.parseAndValidate] itself, and NOT lazy the way
 * [DiagramRenderer.render] is: a [LessonBlock.Diagram] wraps an unevaluated `(Canvas, Int) -> Unit`
 * closure, so building one during [AcademyContentLoader.parseAndValidate] never actually touches
 * `Canvas`/`Paint` until something later calls it. [LessonDemo]/[DemoStage]/[DemoStroke], in
 * contrast, are the same plain, eager data classes every hand-authored `CourseXxx.kt` already
 * builds directly -- [DemoStroke.path] is a real, already-constructed [android.graphics.Path], not
 * a closure -- and that shape is a hardened, unchanged part of this app's domain model (see
 * AcademyModels.kt), not something this migration gets to redesign. So [build] genuinely calls real
 * [android.graphics.Path]/[android.graphics.Color] methods the moment it runs, exactly like every
 * hand-coded `CourseXxx.kt` demo already does at object-init time -- which means, same as
 * [DiagramRenderer] needing Robolectric's NATIVE graphics mode the moment its closure is actually
 * invoked, any test that actually calls [build] (or [buildPath]) needs Robolectric too. Every OTHER
 * course -- i.e. any course whose `LessonContentDto.demo` is null -- never calls this object at all,
 * so [AcademyContentLoaderTest] stays a plain JVM test for all of those, same as before this file
 * existed; only a demo-bearing course's happy-path/end-to-end parsing needs the Robolectric-based
 * test this file's own test class lives in.
 */
object DemoSpecBuilder {

    fun build(dto: DemoSpecDto): LessonDemo = LessonDemo(stages = dto.stages.map { it.toDemoStage() })

    private fun DemoStageDto.toDemoStage(): DemoStage =
        DemoStage(caption = caption, strokes = strokes.map { it.toDemoStroke() })

    private fun DemoStrokeDto.toDemoStroke(): DemoStroke = DemoStroke(
        path = buildPath(path),
        brushId = brushId,
        colorArgb = Color.parseColor(color),
        sizeMultiplier = sizeMultiplier,
    )

    /**
     * Builds a real [android.graphics.Path] from a normalized-0..1 [PathCommandDto] list. Pure
     * geometry construction -- no size scaling (contrast [DiagramRenderer]'s own private
     * `buildPath`, which multiplies every coordinate by the diagram's pixel size): a demo's path is
     * stored in normalized 0..1 space and scaled later, at playback time, by [DemoPlayer] itself via
     * a real [android.graphics.Matrix]. Kept as its own top-level function (not folded into [build])
     * specifically so it's directly unit-testable on its own -- e.g. building a simple triangle and
     * asserting `Path.computeBounds()`, or walking it with a [android.graphics.PathMeasure] and
     * comparing sampled points against an equivalent hand-built `Path`.
     */
    fun buildPath(commands: List<PathCommandDto>): Path = Path().apply {
        for (command in commands) {
            when (command) {
                is PathCommandDto.MoveTo -> moveTo(command.x, command.y)
                is PathCommandDto.LineTo -> lineTo(command.x, command.y)
                is PathCommandDto.QuadTo -> quadTo(command.controlX, command.controlY, command.x, command.y)
                is PathCommandDto.CubicTo -> cubicTo(
                    command.control1X, command.control1Y,
                    command.control2X, command.control2Y,
                    command.x, command.y,
                )
            }
        }
    }
}
