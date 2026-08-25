package com.vellum.studio.academy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * On-disk (JSON) shape for the drawing primitives inside a [LessonBlockDto.Diagram] block --
 * a small, declarative "DiagramSpec" that [DiagramRenderer] interprets into the real
 * `(Canvas, Int) -> Unit` closure [LessonBlock.Diagram] needs.
 *
 * Deliberately scoped to exactly the vocabulary every hand-coded `CourseXxx.kt` Diagram closure
 * actually uses (confirmed by reading every one of them, not guessed): [Line], [Circle], [Rect]
 * (fill and/or stroke), [Text], and [Path] (fill and/or stroke, built from moveTo/lineTo/quadTo/
 * cubicTo). All geometry is normalized 0..1, matching the "s = size" convention every hand-coded
 * Diagram already uses -- [DiagramRenderer] multiplies every coordinate by the real pixel size at
 * render time. Two things some hand-coded diagrams elsewhere in Academy DO use that this format
 * deliberately does NOT cover: shader gradients (`RadialGradient`/`LinearGradient`, used only by
 * CourseShading's sphere/cylinder lighting) and `drawOval`/`drawArc` (used by CourseFoundations/
 * CourseAnatomy/CourseColorTheory/CourseShading, none of which this pass migrates) -- courses that
 * need those stay hand-authored Kotlin rather than force a lossy approximation here.
 *
 * Every op carries its own style rather than sharing one Paint, mirroring how each hand-coded
 * Diagram builds a handful of small local `Paint` values (`pLinePaint`/`pFillPaint`/`pLabelPaint`)
 * and reuses them across a handful of drawing calls -- a JSON op is one drawing call, so the style
 * lives right on it. [strokeWidth]/[textSize] are fractions of the diagram's size (same "s *
 * 0.006f" style constants every hand-coded closure uses), [alpha] is a 0..1 multiplier applied on
 * top of [color]'s own (always fully opaque, "#RRGGBB") color -- confirmed real usage never needs
 * per-op alpha today (every hand-coded CoursePerspective color is opaque), but the general
 * "how much this looks drawn" knob is one every other Paint-based drawing surface in this app
 * (see `DemoPlayer.flattenPaint.alpha`) already needs, so it's included as a real (defaulted, not
 * mandatory) field rather than left out and bolted on later. [dash], when present, is a real,
 * confirmed need: CoursePerspective's own construction-guideline paint uses
 * `DashPathEffect(floatArrayOf(s * 0.018f, s * 0.014f), 0f)` for exactly this look.
 */
@Serializable
sealed class DiagramOpDto {
    @Serializable
    @SerialName("line")
    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val color: String,
        val strokeWidth: Float,
        val alpha: Float = 1f,
        /** [dashLength, gapLength], both fractions of size -- null (the default) draws a solid line. */
        val dash: List<Float>? = null,
    ) : DiagramOpDto()

    @Serializable
    @SerialName("circle")
    data class Circle(
        val cx: Float,
        val cy: Float,
        val r: Float,
        val fillColor: String? = null,
        val strokeColor: String? = null,
        val strokeWidth: Float = 0f,
        val alpha: Float = 1f,
        val dash: List<Float>? = null,
    ) : DiagramOpDto()

    @Serializable
    @SerialName("rect")
    data class Rect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val fillColor: String? = null,
        val strokeColor: String? = null,
        val strokeWidth: Float = 0f,
        val alpha: Float = 1f,
        val dash: List<Float>? = null,
    ) : DiagramOpDto()

    @Serializable
    @SerialName("text")
    data class Text(
        val x: Float,
        val y: Float,
        val text: String,
        val color: String,
        val textSize: Float,
        val align: TextAlignDto = TextAlignDto.CENTER,
        val alpha: Float = 1f,
    ) : DiagramOpDto()

    @Serializable
    @SerialName("path")
    data class Path(
        val commands: List<PathCommandDto> = emptyList(),
        /** Mirrors `android.graphics.Path.close()` -- both real Diagram paths that use it (the two
         * perspective box faces in CoursePerspective) close back to their starting point to form a
         * clean fillable/strokeable outline instead of leaving the last segment open. */
        val closed: Boolean = false,
        val fillColor: String? = null,
        val strokeColor: String? = null,
        val strokeWidth: Float = 0f,
        val alpha: Float = 1f,
        val dash: List<Float>? = null,
    ) : DiagramOpDto()
}

/** JSON-authorable mirror of `Paint.Align` -- confirmed the only three values any real Diagram uses. */
@Serializable
enum class TextAlignDto {
    @SerialName("left") LEFT,
    @SerialName("center") CENTER,
    @SerialName("right") RIGHT,
}

/**
 * One step of an `android.graphics.Path` construction, in normalized 0..1 coordinates. Every real
 * hand-coded Diagram path is built from exactly these four calls (confirmed by grep across every
 * `CourseXxx.kt` Diagram closure) -- moveTo to start, then some mix of straight/quadratic/cubic
 * segments.
 */
@Serializable
sealed class PathCommandDto {
    @Serializable
    @SerialName("moveTo")
    data class MoveTo(val x: Float, val y: Float) : PathCommandDto()

    @Serializable
    @SerialName("lineTo")
    data class LineTo(val x: Float, val y: Float) : PathCommandDto()

    @Serializable
    @SerialName("quadTo")
    data class QuadTo(val controlX: Float, val controlY: Float, val x: Float, val y: Float) : PathCommandDto()

    @Serializable
    @SerialName("cubicTo")
    data class CubicTo(
        val control1X: Float,
        val control1Y: Float,
        val control2X: Float,
        val control2Y: Float,
        val x: Float,
        val y: Float,
    ) : PathCommandDto()
}
