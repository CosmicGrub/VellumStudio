package com.vellum.studio.academy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Interprets a parsed [DiagramOpDto] list (a "DiagramSpec") into the exact `(Canvas, Int) -> Unit`
 * closure [LessonBlock.Diagram.draw] needs -- a pure function with no [android.content.Context] or
 * I/O of its own, so it's directly unit-testable by rendering a spec onto a real Bitmap-backed
 * Canvas (via Robolectric) and reading back specific pixels, same technique as
 * `RegionAnalyzerTest`/`ShapeAssistTest`.
 *
 * Every op is drawn independently in list order (later ops paint over earlier ones, same as
 * sequential `canvas.drawXxx` calls in a hand-coded Diagram closure) -- there is deliberately no
 * shared mutable `Paint` state carried between ops, so one op's style can never accidentally leak
 * into the next.
 */
object DiagramRenderer {

    /** Builds the drawing closure [LessonBlock.Diagram] needs from a parsed op list. */
    fun render(ops: List<DiagramOpDto>): (canvas: Canvas, size: Int) -> Unit = { canvas, size ->
        val s = size.toFloat()
        for (op in ops) {
            drawOp(canvas, s, op)
        }
    }

    private fun drawOp(canvas: Canvas, s: Float, op: DiagramOpDto) {
        when (op) {
            is DiagramOpDto.Line -> {
                val paint = strokePaint(s, op.color, op.strokeWidth, op.alpha, op.dash)
                canvas.drawLine(op.x1 * s, op.y1 * s, op.x2 * s, op.y2 * s, paint)
            }
            is DiagramOpDto.Circle -> {
                op.fillColor?.let { canvas.drawCircle(op.cx * s, op.cy * s, op.r * s, fillPaint(it, op.alpha)) }
                op.strokeColor?.let {
                    canvas.drawCircle(op.cx * s, op.cy * s, op.r * s, strokePaint(s, it, op.strokeWidth, op.alpha, op.dash))
                }
            }
            is DiagramOpDto.Rect -> {
                val rect = RectF(op.left * s, op.top * s, op.right * s, op.bottom * s)
                op.fillColor?.let { canvas.drawRect(rect, fillPaint(it, op.alpha)) }
                op.strokeColor?.let { canvas.drawRect(rect, strokePaint(s, it, op.strokeWidth, op.alpha, op.dash)) }
            }
            is DiagramOpDto.Text -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colorWithAlpha(op.color, op.alpha)
                    textSize = op.textSize * s
                    textAlign = when (op.align) {
                        TextAlignDto.LEFT -> Paint.Align.LEFT
                        TextAlignDto.CENTER -> Paint.Align.CENTER
                        TextAlignDto.RIGHT -> Paint.Align.RIGHT
                    }
                }
                canvas.drawText(op.text, op.x * s, op.y * s, paint)
            }
            is DiagramOpDto.Path -> {
                val path = buildPath(op.commands, op.closed, s)
                op.fillColor?.let { canvas.drawPath(path, fillPaint(it, op.alpha)) }
                op.strokeColor?.let { canvas.drawPath(path, strokePaint(s, it, op.strokeWidth, op.alpha, op.dash)) }
            }
        }
    }

    private fun buildPath(commands: List<PathCommandDto>, closed: Boolean, s: Float): Path = Path().apply {
        for (command in commands) {
            when (command) {
                is PathCommandDto.MoveTo -> moveTo(command.x * s, command.y * s)
                is PathCommandDto.LineTo -> lineTo(command.x * s, command.y * s)
                is PathCommandDto.QuadTo -> quadTo(command.controlX * s, command.controlY * s, command.x * s, command.y * s)
                is PathCommandDto.CubicTo -> cubicTo(
                    command.control1X * s, command.control1Y * s,
                    command.control2X * s, command.control2Y * s,
                    command.x * s, command.y * s,
                )
            }
        }
        if (closed) close()
    }

    private fun fillPaint(hexColor: String, alpha: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorWithAlpha(hexColor, alpha)
    }

    private fun strokePaint(s: Float, hexColor: String, strokeWidth: Float, alpha: Float, dash: List<Float>?): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth * s
            color = colorWithAlpha(hexColor, alpha)
            if (dash != null) {
                pathEffect = DashPathEffect(floatArrayOf(dash[0] * s, dash[1] * s), 0f)
            }
        }

    /** Parses an opaque "#RRGGBB" hex string, then applies [alpha] (0..1) as the real alpha channel. */
    private fun colorWithAlpha(hexColor: String, alpha: Float): Int {
        val opaque = Color.parseColor(hexColor)
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return Color.argb(a, Color.red(opaque), Color.green(opaque), Color.blue(opaque))
    }
}
