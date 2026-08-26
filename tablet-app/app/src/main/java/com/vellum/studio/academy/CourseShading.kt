package com.vellum.studio.academy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * "Shading & Light" - six lessons that take someone from "I can draw the outline of a sphere,
 * a cube, and a cylinder" (covered elsewhere, in Foundations) to actually understanding and
 * applying light and shadow so those shapes look solid. Assumes basic shape drawing is already
 * comfortable; this course is entirely about value and light logic on top of that.
 *
 * Migrated to the data-driven Academy content format (see AcademyContentDto / AcademyContentLoader)
 * as part of the "remaining migrations" pass -- but only PARTIALLY: four of this course's six
 * lessons ("picking-a-light-source", "shading-a-sphere-step-by-step", "blending-techniques-for-
 * this-app", "cross-hatching-and-mark-making") are plain text and migrated normally. The other two
 * ("five-values-of-light" and "shading-cubes-and-cylinders") each carry one [LessonBlock.Diagram]
 * whose hand-coded closure (below, unchanged from the original) genuinely needs drawing operations
 * outside even the extended [DiagramOpDto] vocabulary this migration pass added ([Oval]/[Arc] for
 * CourseFoundations/CourseColorTheory): [RadialGradient]/`LinearGradient` shaders for the sphere's
 * lit-surface gradient and the cast-shadow falloff, `canvas.clipPath` to confine the core-shadow/
 * reflected-light arcs to the sphere's own silhouette, and `canvas.save/translate/scale/restore`
 * to squash a circle into an elliptical ground shadow. That's a real paint/shader system plus clip
 * regions, not a small, generalizable addition the way [Oval]/[Arc] were -- forcing it into flat
 * JSON ops would mean either dropping the gradient/shadow entirely (a visibly worse, lossy
 * diagram) or inventing a much bigger shader/clip sub-format for exactly two diagrams in the whole
 * app. Per this task's own instructions, that's exactly the case for leaving it hand-authored
 * Kotlin rather than forcing a lossy approximation.
 *
 * So `shading.json` carries every lesson's prose faithfully, with the two diagram-bearing lessons'
 * `blocks` simply omitting the one block JSON can't express; [withHandAuthoredDiagram] below
 * reinserts the real [LessonBlock.Diagram] (still built from the original, unchanged
 * `drawFiveValueSphere`/`drawCubeAndCylinder` closures) at the same position -- immediately before
 * that lesson's first [LessonBlock.Tip] -- the diagram sat at in the original hand-authored course,
 * so nothing about the lesson's reading order changes.
 */
object CourseShading {

    private val rawCourse: Course = AcademyContentLoader.loadFromAssets("academy/shading.json")

    val course: Course = rawCourse.copy(
        lessons = rawCourse.lessons.map { lesson ->
            when (lesson.id) {
                "five-values-of-light" -> withHandAuthoredDiagram(
                    lesson,
                    LessonBlock.Diagram(
                        caption = "The five value zones on a lit sphere",
                        draw = { canvas, size -> drawFiveValueSphere(canvas, size) },
                    )
                )
                "shading-cubes-and-cylinders" -> withHandAuthoredDiagram(
                    lesson,
                    LessonBlock.Diagram(
                        caption = "The same light logic on a cube and a cylinder",
                        draw = { canvas, size -> drawCubeAndCylinder(canvas, size) },
                    )
                )
                else -> lesson
            }
        }
    )

    // ---- Diagram drawing (hand-authored -- see this object's own doc for why) ----

    private fun drawFiveValueSphere(canvas: Canvas, size: Int) {
        val s = size.toFloat()
        val cx = s * 0.5f
        val radius = s * 0.22f
        val groundY = s * 0.78f
        val cy = groundY - radius

        val leftColX = s * 0.03f
        val rightColX = s * 0.97f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 46, 42)
            textSize = s * 0.028f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(95, 88, 80)
            strokeWidth = s * 0.0025f
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 46, 42)
            style = Paint.Style.FILL
        }
        val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 210, 202)
            strokeWidth = s * 0.004f
        }
        canvas.drawLine(s * 0.06f, groundY, s * 0.94f, groundY, groundPaint)

        // Cast shadow: soft, blurred-looking radial gradient, offset away from the light.
        val shadowCx = cx + radius * 0.75f
        val shadowRx = radius * 1.25f
        val shadowRy = radius * 0.34f
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                shadowCx, groundY, shadowRx,
                intArrayOf(Color.argb(140, 30, 26, 22), Color.argb(0, 30, 26, 22)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.save()
        canvas.translate(shadowCx, groundY)
        canvas.scale(1f, shadowRy / shadowRx)
        canvas.translate(-shadowCx, -groundY)
        canvas.drawCircle(shadowCx, groundY, shadowRx, shadowPaint)
        canvas.restore()

        // Sphere base: radial gradient centered near the highlight point.
        val lightCx = cx - radius * 0.42f
        val lightCy = cy - radius * 0.48f
        val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                lightCx, lightCy, radius * 1.85f,
                intArrayOf(
                    Color.rgb(255, 250, 240),
                    Color.rgb(230, 200, 150),
                    Color.rgb(165, 120, 75),
                    Color.rgb(70, 50, 38)
                ),
                floatArrayOf(0f, 0.36f, 0.66f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(cx, cy, radius, spherePaint)

        // Core shadow band and reflected light sliver, clipped to the sphere itself.
        canvas.save()
        val clipPath = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        canvas.clipPath(clipPath)

        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.4f
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(120, 35, 24, 16)
        }
        val coreRect = RectF(cx - radius * 0.78f, cy - radius * 0.78f, cx + radius * 0.78f, cy + radius * 0.78f)
        canvas.drawArc(coreRect, 15f, 130f, false, corePaint)

        val reflectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = radius * 0.14f
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(90, 195, 165, 130)
        }
        val reflectedRect = RectF(cx - radius * 0.95f, cy - radius * 0.95f, cx + radius * 0.95f, cy + radius * 0.95f)
        canvas.drawArc(reflectedRect, 20f, 110f, false, reflectedPaint)
        canvas.restore()

        // Highlight pop.
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(170, 255, 255, 250)
        }
        canvas.drawCircle(lightCx, lightCy, radius * 0.16f, highlightPaint)

        // Sphere outline.
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.0025f
            color = Color.argb(120, 70, 60, 50)
        }
        canvas.drawCircle(cx, cy, radius, outlinePaint)

        fun label(text: String, tx: Float, ty: Float, px: Float, py: Float, align: Paint.Align) {
            canvas.drawLine(px, py, tx, ty, linePaint)
            canvas.drawCircle(px, py, s * 0.006f, dotPaint)
            textPaint.textAlign = align
            canvas.drawText(text, tx, ty, textPaint)
        }

        label("highlight", leftColX, s * 0.16f, lightCx, lightCy, Paint.Align.LEFT)
        label("light side", leftColX, s * 0.34f, cx - radius * 0.5f, cy - radius * 0.09f, Paint.Align.LEFT)
        label("core shadow", rightColX, s * 0.56f, cx + radius * 0.12f, cy + radius * 0.69f, Paint.Align.RIGHT)
        label("reflected light", rightColX, s * 0.70f, cx + radius * 0.25f, cy + radius * 0.92f, Paint.Align.RIGHT)
        label("cast shadow", cx, s * 0.94f, shadowCx, groundY, Paint.Align.CENTER)
    }

    private fun drawCubeAndCylinder(canvas: Canvas, size: Int) {
        val s = size.toFloat()
        val groundY = s * 0.80f

        val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(215, 210, 202)
            strokeWidth = s * 0.004f
        }
        canvas.drawLine(s * 0.04f, groundY, s * 0.96f, groundY, groundPaint)

        // ---- Cube ----
        val edge = s * 0.15f
        val dx = edge * 0.87f
        val dy = edge * 0.5f
        val cxCube = s * 0.26f

        val front = Pair(cxCube, groundY - edge)
        val top = Pair(cxCube, groundY - 2f * edge)
        val topRight = Pair(cxCube + dx, groundY - 2f * edge + dy)
        val topLeft = Pair(cxCube - dx, groundY - 2f * edge + dy)
        val bottomRight = Pair(cxCube + dx, groundY - edge + dy)
        val bottomLeft = Pair(cxCube - dx, groundY - edge + dy)
        val bottom = Pair(cxCube, groundY)

        // Soft cube shadow.
        val shadowCubeCx = cxCube + edge * 0.5f
        val shadowCubeRx = edge * 1.1f
        val shadowCubeRy = edge * 0.32f
        val cubeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                shadowCubeCx, groundY, shadowCubeRx,
                intArrayOf(Color.argb(120, 30, 26, 22), Color.argb(0, 30, 26, 22)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.save()
        canvas.translate(shadowCubeCx, groundY)
        canvas.scale(1f, shadowCubeRy / shadowCubeRx)
        canvas.translate(-shadowCubeCx, -groundY)
        canvas.drawCircle(shadowCubeCx, groundY, shadowCubeRx, cubeShadowPaint)
        canvas.restore()

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.004f
            color = Color.rgb(40, 32, 26)
        }

        fun facePath(a: Pair<Float, Float>, b: Pair<Float, Float>, c: Pair<Float, Float>, d: Pair<Float, Float>): Path {
            return Path().apply {
                moveTo(a.first, a.second)
                lineTo(b.first, b.second)
                lineTo(c.first, c.second)
                lineTo(d.first, d.second)
                close()
            }
        }

        val topFace = facePath(top, topRight, front, topLeft)
        val leftFace = facePath(topLeft, front, bottom, bottomLeft)
        val rightFace = facePath(topRight, front, bottom, bottomRight)

        val topFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 214, 168) }
        val leftFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(178, 150, 105) }
        val rightFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 80, 60) }

        canvas.drawPath(topFace, topFacePaint)
        canvas.drawPath(leftFace, leftFacePaint)
        canvas.drawPath(rightFace, rightFacePaint)
        canvas.drawPath(topFace, outlinePaint)
        canvas.drawPath(leftFace, outlinePaint)
        canvas.drawPath(rightFace, outlinePaint)

        // ---- Cylinder ----
        val rx = s * 0.095f
        val ry = s * 0.028f
        val cxCyl = s * 0.74f
        val bottomCenterY = groundY - ry
        val topCenterY = bottomCenterY - s * 0.30f

        val shadowCylCx = cxCyl + rx * 0.9f
        val shadowCylRx = rx * 1.4f
        val shadowCylRy = ry * 1.3f
        val cylShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 35, 30, 25)
        }
        canvas.save()
        canvas.translate(shadowCylCx, groundY)
        canvas.scale(1f, shadowCylRy / shadowCylRx)
        canvas.translate(-shadowCylCx, -groundY)
        canvas.drawCircle(shadowCylCx, groundY, shadowCylRx, cylShadowPaint)
        canvas.restore()

        val bottomOvalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(110, 88, 66) }
        canvas.drawOval(RectF(cxCyl - rx, bottomCenterY - ry, cxCyl + rx, bottomCenterY + ry), bottomOvalPaint)
        canvas.drawOval(RectF(cxCyl - rx, bottomCenterY - ry, cxCyl + rx, bottomCenterY + ry), outlinePaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                cxCyl - rx, topCenterY, cxCyl + rx, topCenterY,
                intArrayOf(
                    Color.rgb(240, 225, 195),
                    Color.rgb(170, 135, 95),
                    Color.rgb(90, 65, 48),
                    Color.rgb(120, 90, 68)
                ),
                floatArrayOf(0f, 0.4f, 0.8f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val bodyPath = Path().apply {
            moveTo(cxCyl - rx, topCenterY)
            lineTo(cxCyl - rx, bottomCenterY)
            lineTo(cxCyl + rx, bottomCenterY)
            lineTo(cxCyl + rx, topCenterY)
            close()
        }
        canvas.drawPath(bodyPath, bodyPaint)
        canvas.drawLine(cxCyl - rx, topCenterY, cxCyl - rx, bottomCenterY, outlinePaint)
        canvas.drawLine(cxCyl + rx, topCenterY, cxCyl + rx, bottomCenterY, outlinePaint)

        val topOvalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(214, 198, 168) }
        canvas.drawOval(RectF(cxCyl - rx, topCenterY - ry, cxCyl + rx, topCenterY + ry), topOvalPaint)
        canvas.drawOval(RectF(cxCyl - rx, topCenterY - ry, cxCyl + rx, topCenterY + ry), outlinePaint)

        // Labels.
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 46, 42)
            textSize = s * 0.026f
            textAlign = Paint.Align.CENTER
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(95, 88, 80)
            strokeWidth = s * 0.0025f
        }

        canvas.drawLine(cxCube, front.second, cxCube, s * 0.87f, linePaint)
        canvas.drawText("flat, hard-edged planes", cxCube, s * 0.90f, textPaint)

        val cylMidY = (topCenterY + bottomCenterY) / 2f
        canvas.drawLine(cxCyl, cylMidY, cxCyl, s * 0.87f, linePaint)
        canvas.drawText("smooth curve, sharp shadow", cxCyl, s * 0.90f, textPaint)
    }
}

/**
 * Reinserts a hand-authored [LessonBlock.Diagram] into [lesson] at the position the original
 * hand-authored [CourseShading] always placed it: immediately before the lesson's first
 * [LessonBlock.Tip] (both "five-values-of-light" and "shading-cubes-and-cylinders" put their
 * diagram right after the last explanatory paragraph and right before their two closing tips).
 *
 * Deliberately a top-level `internal` function -- not a member of [CourseShading] -- specifically
 * so [CourseShadingDiagramInsertionTest] can call it directly from plain JVM with a synthetic
 * [Lesson]/[LessonBlock.Diagram], without ever touching the [CourseShading] object itself (which
 * would eagerly run [CourseShading.course]'s own `AcademyContentLoader.loadFromAssets` and require
 * a real Android asset manager). This function never invokes a [LessonBlock.Diagram.draw] closure,
 * only stores it, so it needs nothing Android-specific either way.
 */
internal fun withHandAuthoredDiagram(lesson: Lesson, diagram: LessonBlock.Diagram): Lesson {
    val tipIndex = lesson.blocks.indexOfFirst { it is LessonBlock.Tip }
    val insertAt = if (tipIndex >= 0) tipIndex else lesson.blocks.size
    return lesson.copy(blocks = lesson.blocks.toMutableList().apply { add(insertAt, diagram) })
}
