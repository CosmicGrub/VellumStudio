package com.vellum.studio.art

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Original hand-authored line-art coloring pages: nature botanicals, stylized animals, and
 * decorative abstract motifs. Every shape is stroked only (never filled) so every enclosed
 * region stays open for the app's fill/brush tools, and every illustration is subdivided with
 * internal linework into several distinct colorable zones.
 */
object ColoringTemplatesIllustrated {
    val templates: List<ColoringTemplate> = listOf(
        ColoringTemplate("nature-floral-wreath", "Floral Wreath", "Nature", ::drawFloralWreath),
        ColoringTemplate("nature-peony-bloom", "Peony Bloom", "Nature", ::drawPeonyBloom),
        ColoringTemplate("nature-botanical-leaves", "Botanical Leaf Spray", "Nature", ::drawBotanicalLeaves),
        ColoringTemplate("nature-blossom-branch", "Blossom Branch", "Nature", ::drawBlossomBranch),
        ColoringTemplate("animal-owl", "Patterned Owl", "Animals", ::drawOwl),
        ColoringTemplate("animal-cat-sitting", "Sitting Cat", "Animals", ::drawCatSitting),
        ColoringTemplate("animal-butterfly", "Garden Butterfly", "Animals", ::drawButterfly),
        ColoringTemplate("animal-fish", "Scaled Fish", "Animals", ::drawFish),
        ColoringTemplate("abstract-paisley", "Paisley Teardrop", "Abstract", ::drawPaisley),
        ColoringTemplate("abstract-ocean-waves", "Ocean Waves", "Abstract", ::drawOceanWaves),
        ColoringTemplate("abstract-sunburst", "Sunburst", "Abstract", ::drawSunburst),
        ColoringTemplate("abstract-feather", "Detailed Feather", "Abstract", ::drawFeather),
    )
}

// ---------------------------------------------------------------------------------------------
// Shared paint / geometry helpers
// ---------------------------------------------------------------------------------------------

/** Primary line weight - used for main silhouettes and outlines. */
private fun linePaint(size: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = 0xFF1A1A1A.toInt()
    strokeWidth = (size * 0.0032f).coerceAtLeast(2f)
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** Lighter line weight - used for internal detail lines that subdivide a shape for coloring. */
private fun thinPaint(size: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = 0xFF1A1A1A.toInt()
    strokeWidth = (size * 0.0025f).coerceAtLeast(2f)
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** A simple curved petal, base at the origin, tip pointing straight up (toward -y). */
private fun petalPath(len: Float, width: Float): Path = Path().apply {
    moveTo(0f, 0f)
    cubicTo(-width, -len * 0.32f, -width * 0.58f, -len * 0.82f, 0f, -len)
    cubicTo(width * 0.58f, -len * 0.82f, width, -len * 0.32f, 0f, 0f)
    close()
}

/** A simple curved botanical leaf, base at the origin, tip pointing straight up. */
private fun leafPath(len: Float, width: Float): Path = Path().apply {
    moveTo(0f, 0f)
    cubicTo(-width, -len * 0.28f, -width * 0.85f, -len * 0.74f, 0f, -len)
    cubicTo(width * 0.85f, -len * 0.74f, width, -len * 0.28f, 0f, 0f)
    close()
}

/** Center vein line for [leafPath], splitting the leaf into two colorable halves. */
private fun leafVein(len: Float): Path = Path().apply {
    moveTo(0f, -len * 0.06f)
    quadTo(0f, -len * 0.5f, 0f, -len * 0.92f)
}

/** Draws [path] translated to ([cx],[cy]), optionally rotated / scaled about that point. */
private fun drawAt(
    canvas: Canvas,
    path: Path,
    paint: Paint,
    cx: Float,
    cy: Float,
    rotationDeg: Float = 0f,
    scale: Float = 1f,
) {
    canvas.save()
    canvas.translate(cx, cy)
    if (rotationDeg != 0f) canvas.rotate(rotationDeg)
    if (scale != 1f) canvas.scale(scale, scale)
    canvas.drawPath(path, paint)
    canvas.restore()
}

/** A small five-petal blossom with a round center, used across several nature templates. */
private fun drawBlossom(
    canvas: Canvas,
    petalPaint: Paint,
    centerPaint: Paint,
    cx: Float,
    cy: Float,
    petalLen: Float,
    rotationOffset: Float,
    petalWidthRatio: Float = 0.55f,
) {
    val petal = petalPath(petalLen, petalLen * petalWidthRatio)
    for (i in 0 until 5) {
        val angle = rotationOffset + i * 72f
        drawAt(canvas, petal, petalPaint, cx, cy, angle)
    }
    canvas.drawCircle(cx, cy, petalLen * 0.22f, centerPaint)
}

/** Point at parameter [t] along a cubic Bezier defined by its four control points. */
private fun cubicPoint(
    x0: Float, y0: Float,
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    x3: Float, y3: Float,
    t: Float,
): PointF {
    val u = 1f - t
    val x = u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
    val y = u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
    return PointF(x, y)
}

/** A small outward spiral, used as a decorative accent (paisley curl, wave foam, etc). */
private fun drawSpiral(canvas: Canvas, paint: Paint, cx: Float, cy: Float, maxR: Float, turns: Float = 2.2f) {
    val path = Path()
    val steps = 48
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val angle = t * turns * 2f * PI.toFloat()
        val r = maxR * t
        val x = cx + r * cos(angle)
        val y = cy + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    canvas.drawPath(path, paint)
}

private fun trianglePath(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): Path =
    Path().apply {
        moveTo(x1, y1)
        lineTo(x2, y2)
        lineTo(x3, y3)
        close()
    }

// ---------------------------------------------------------------------------------------------
// Nature
// ---------------------------------------------------------------------------------------------

/** A ring of alternating small blossoms and leaf pairs, framing an empty center. */
private fun drawFloralWreath(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val cx = s * 0.5f
    val cy = s * 0.5f
    val ringRadius = s * 0.32f
    val itemCount = 10

    for (i in 0 until itemCount) {
        val angle = i * (360f / itemCount)
        val rad = Math.toRadians(angle.toDouble())
        val px = cx + ringRadius * sin(rad).toFloat()
        val py = cy - ringRadius * cos(rad).toFloat()

        if (i % 2 == 0) {
            drawBlossom(canvas, paint, thin, px, py, s * 0.075f, angle, 0.44f)
        } else {
            val leafLen = s * 0.095f
            val leafWidth = s * 0.038f
            val lp = leafPath(leafLen, leafWidth)
            val vp = leafVein(leafLen)
            drawAt(canvas, lp, paint, px, py, angle - 22f)
            drawAt(canvas, vp, thin, px, py, angle - 22f)
            drawAt(canvas, lp, paint, px, py, angle + 22f)
            drawAt(canvas, vp, thin, px, py, angle + 22f)
        }
    }

    // Scalloped vine connecting every element into a continuous wreath.
    val vine = Path()
    val vineRadius = ringRadius * 0.72f
    for (i in 0..itemCount) {
        val angle = i * (360f / itemCount)
        val rad = Math.toRadians(angle.toDouble())
        val x = cx + vineRadius * sin(rad).toFloat()
        val y = cy - vineRadius * cos(rad).toFloat()
        if (i == 0) {
            vine.moveTo(x, y)
        } else {
            val midAngle = angle - (360f / itemCount) / 2f
            val midRad = Math.toRadians(midAngle.toDouble())
            val midR = vineRadius * 0.90f
            val mx = cx + midR * sin(midRad).toFloat()
            val my = cy - midR * cos(midRad).toFloat()
            vine.quadTo(mx, my, x, y)
        }
    }
    canvas.drawPath(vine, thin)
}

/** A single large peony-style bloom built from three layered rings of curved petals. */
private fun drawPeonyBloom(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val cx = s * 0.5f
    val cy = s * 0.5f
    val maxR = s * 0.42f

    val outerPetal = petalPath(maxR * 0.95f, maxR * 0.50f)
    val outerCount = 8
    for (i in 0 until outerCount) {
        val angle = i * (360f / outerCount) + 8f
        drawAt(canvas, outerPetal, paint, cx, cy, angle)
    }

    val midPetal = petalPath(maxR * 0.62f, maxR * 0.36f)
    val midCount = 7
    for (i in 0 until midCount) {
        val angle = i * (360f / midCount) + 34f
        drawAt(canvas, midPetal, paint, cx, cy, angle)
    }

    val innerPetal = petalPath(maxR * 0.34f, maxR * 0.24f)
    val innerCount = 6
    for (i in 0 until innerCount) {
        val angle = i * (360f / innerCount)
        drawAt(canvas, innerPetal, paint, cx, cy, angle)
    }

    val centerPetal = petalPath(maxR * 0.16f, maxR * 0.14f)
    val centerCount = 5
    for (i in 0 until centerCount) {
        val angle = i * (360f / centerCount) + 18f
        drawAt(canvas, centerPetal, thin, cx, cy, angle)
    }
    canvas.drawCircle(cx, cy, maxR * 0.05f, thin)
}

/** A curving stem with alternating leaves and a closed bud, like a botanical study sketch. */
private fun drawBotanicalLeaves(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val margin = s * 0.08f

    val x0 = s * 0.5f; val y0 = s - margin
    val cx1 = s * 0.38f; val cy1 = s * 0.68f
    val cx2 = s * 0.62f; val cy2 = s * 0.38f
    val x3 = s * 0.5f; val y3 = margin

    val stem = Path()
    stem.moveTo(x0, y0)
    stem.cubicTo(cx1, cy1, cx2, cy2, x3, y3)
    canvas.drawPath(stem, paint)

    val leafPositions = listOf(0.16f, 0.34f, 0.52f, 0.68f, 0.84f)
    var side = 1f
    for (t in leafPositions) {
        val p = cubicPoint(x0, y0, cx1, cy1, cx2, cy2, x3, y3, t)
        val leafLen = s * 0.15f
        val leafWidth = s * 0.065f
        val angle = -68f * side
        val lp = leafPath(leafLen, leafWidth)
        val vp = leafVein(leafLen)
        drawAt(canvas, lp, paint, p.x, p.y, angle)
        drawAt(canvas, vp, thin, p.x, p.y, angle)
        side *= -1f
    }

    // Closed bud near the top of the stem, formed from three overlapping petal-like sepals.
    val budCenter = cubicPoint(x0, y0, cx1, cy1, cx2, cy2, x3, y3, 0.95f)
    val bud = petalPath(s * 0.07f, s * 0.038f)
    drawAt(canvas, bud, thin, budCenter.x, budCenter.y, 0f)
    drawAt(canvas, bud, thin, budCenter.x, budCenter.y, 28f)
    drawAt(canvas, bud, thin, budCenter.x, budCenter.y, -28f)
}

/** A diagonal branch carrying several five-petal blossoms, leaves, and one side twig. */
private fun drawBlossomBranch(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val margin = s * 0.08f

    val x0 = margin; val y0 = s - margin * 1.2f
    val cx1 = s * 0.30f; val cy1 = s * 0.85f
    val cx2 = s * 0.55f; val cy2 = s * 0.25f
    val x3 = s - margin; val y3 = margin * 1.1f

    val branch = Path()
    branch.moveTo(x0, y0)
    branch.cubicTo(cx1, cy1, cx2, cy2, x3, y3)
    canvas.drawPath(branch, paint)

    val twigBase = cubicPoint(x0, y0, cx1, cy1, cx2, cy2, x3, y3, 0.40f)
    val twigTip = PointF(twigBase.x + s * 0.10f, twigBase.y - s * 0.16f)
    val twig = Path()
    twig.moveTo(twigBase.x, twigBase.y)
    twig.quadTo(twigBase.x + s * 0.05f, twigBase.y - s * 0.10f, twigTip.x, twigTip.y)
    canvas.drawPath(twig, paint)

    val blossomTs = listOf(0.10f, 0.30f, 0.58f, 0.78f, 0.95f)
    for ((idx, t) in blossomTs.withIndex()) {
        val p = cubicPoint(x0, y0, cx1, cy1, cx2, cy2, x3, y3, t)
        drawBlossom(canvas, paint, thin, p.x, p.y, s * 0.062f, idx * 37f)
    }
    drawBlossom(canvas, paint, thin, twigTip.x, twigTip.y, s * 0.05f, 15f)

    val leafTs = listOf(0.20f, 0.45f, 0.68f, 0.88f)
    var side = 1f
    for (t in leafTs) {
        val p = cubicPoint(x0, y0, cx1, cy1, cx2, cy2, x3, y3, t)
        val leafLen = s * 0.085f
        val lp = leafPath(leafLen, leafLen * 0.42f)
        val vp = leafVein(leafLen)
        val angle = -60f * side
        drawAt(canvas, lp, thin, p.x, p.y, angle)
        drawAt(canvas, vp, thin, p.x, p.y, angle)
        side *= -1f
    }
}

// ---------------------------------------------------------------------------------------------
// Animals
// ---------------------------------------------------------------------------------------------

/** A stylized owl: rounded body, patterned face disks, banded chest, and feather-row wings. */
private fun drawOwl(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val cx = s * 0.5f
    val margin = s * 0.08f

    val bodyTop = s * 0.20f
    val bodyBottom = s - margin - s * 0.06f
    val bodyHalfW = s * 0.30f

    val body = Path()
    body.moveTo(cx, bodyTop)
    body.cubicTo(cx - bodyHalfW * 0.95f, bodyTop + s * 0.02f, cx - bodyHalfW, bodyTop + s * 0.30f, cx - bodyHalfW * 0.92f, s * 0.55f)
    body.cubicTo(cx - bodyHalfW * 0.85f, s * 0.68f, cx - bodyHalfW * 0.55f, bodyBottom, cx, bodyBottom)
    body.cubicTo(cx + bodyHalfW * 0.55f, bodyBottom, cx + bodyHalfW * 0.85f, s * 0.68f, cx + bodyHalfW * 0.92f, s * 0.55f)
    body.cubicTo(cx + bodyHalfW, bodyTop + s * 0.30f, cx + bodyHalfW * 0.95f, bodyTop + s * 0.02f, cx, bodyTop)
    body.close()
    canvas.drawPath(body, paint)

    val earL = Path()
    earL.moveTo(cx - bodyHalfW * 0.55f, bodyTop + s * 0.03f)
    earL.cubicTo(cx - bodyHalfW * 0.62f, bodyTop - s * 0.07f, cx - bodyHalfW * 0.40f, bodyTop - s * 0.09f, cx - bodyHalfW * 0.30f, bodyTop + s * 0.01f)
    canvas.drawPath(earL, paint)
    val earR = Path()
    earR.moveTo(cx + bodyHalfW * 0.55f, bodyTop + s * 0.03f)
    earR.cubicTo(cx + bodyHalfW * 0.62f, bodyTop - s * 0.07f, cx + bodyHalfW * 0.40f, bodyTop - s * 0.09f, cx + bodyHalfW * 0.30f, bodyTop + s * 0.01f)
    canvas.drawPath(earR, paint)

    val eyeY = bodyTop + s * 0.18f
    val eyeOffsetX = s * 0.11f
    val faceR = s * 0.115f
    canvas.drawCircle(cx - eyeOffsetX, eyeY, faceR, thin)
    canvas.drawCircle(cx + eyeOffsetX, eyeY, faceR, thin)

    canvas.drawCircle(cx - eyeOffsetX, eyeY, faceR * 0.55f, paint)
    canvas.drawCircle(cx - eyeOffsetX, eyeY, faceR * 0.22f, paint)
    canvas.drawCircle(cx + eyeOffsetX, eyeY, faceR * 0.55f, paint)
    canvas.drawCircle(cx + eyeOffsetX, eyeY, faceR * 0.22f, paint)

    val beak = Path()
    beak.moveTo(cx - s * 0.035f, eyeY + faceR * 0.55f)
    beak.quadTo(cx, eyeY + faceR * 0.95f, cx + s * 0.035f, eyeY + faceR * 0.55f)
    beak.quadTo(cx, eyeY + faceR * 0.75f, cx - s * 0.035f, eyeY + faceR * 0.55f)
    canvas.drawPath(beak, paint)

    val browL = Path()
    browL.moveTo(cx - eyeOffsetX - faceR * 0.9f, eyeY - faceR * 0.95f)
    browL.quadTo(cx - eyeOffsetX, eyeY - faceR * 1.35f, cx - eyeOffsetX + faceR * 0.9f, eyeY - faceR * 0.95f)
    canvas.drawPath(browL, thin)
    val browR = Path()
    browR.moveTo(cx + eyeOffsetX - faceR * 0.9f, eyeY - faceR * 0.95f)
    browR.quadTo(cx + eyeOffsetX, eyeY - faceR * 1.35f, cx + eyeOffsetX + faceR * 0.9f, eyeY - faceR * 0.95f)
    canvas.drawPath(browR, thin)

    // Chest: a few scalloped bands dividing the lower body into colorable zones.
    val chestTop = eyeY + faceR * 1.3f
    val chestBottom = bodyBottom - s * 0.05f
    val bandCount = 4
    for (i in 1 until bandCount) {
        val t = i / bandCount.toFloat()
        val y = chestTop + (chestBottom - chestTop) * t
        val bandWidth = bodyHalfW * (0.55f - 0.10f * t)
        val startX = cx - bandWidth
        val endX = cx + bandWidth
        val scallops = 5
        val step = (endX - startX) / scallops
        val band = Path()
        band.moveTo(startX, y)
        for (j in 0 until scallops) {
            val segStart = startX + step * j
            val segEnd = segStart + step
            val midX = (segStart + segEnd) / 2f
            band.quadTo(midX, y + s * 0.015f, segEnd, y)
        }
        canvas.drawPath(band, thin)
    }

    // Wings, each with three internal feather-row arcs.
    val wTopY = s * 0.32f
    val wingBottomY = bodyBottom - s * 0.03f
    val wingL = Path()
    wingL.moveTo(cx - bodyHalfW * 0.85f, wTopY)
    wingL.cubicTo(cx - bodyHalfW * 1.02f, s * 0.45f, cx - bodyHalfW * 0.98f, s * 0.62f, cx - bodyHalfW * 0.60f, wingBottomY)
    wingL.cubicTo(cx - bodyHalfW * 0.75f, s * 0.58f, cx - bodyHalfW * 0.70f, s * 0.42f, cx - bodyHalfW * 0.85f, wTopY)
    canvas.drawPath(wingL, paint)
    val wingR = Path()
    wingR.moveTo(cx + bodyHalfW * 0.85f, wTopY)
    wingR.cubicTo(cx + bodyHalfW * 1.02f, s * 0.45f, cx + bodyHalfW * 0.98f, s * 0.62f, cx + bodyHalfW * 0.60f, wingBottomY)
    wingR.cubicTo(cx + bodyHalfW * 0.75f, s * 0.58f, cx + bodyHalfW * 0.70f, s * 0.42f, cx + bodyHalfW * 0.85f, wTopY)
    canvas.drawPath(wingR, paint)

    for (i in 1..3) {
        val t = i / 4f
        val y = wTopY + (wingBottomY - wTopY) * t
        val archL = Path()
        archL.moveTo(cx - bodyHalfW * 0.95f, y - s * 0.02f)
        archL.quadTo(cx - bodyHalfW * 0.72f, y + s * 0.03f, cx - bodyHalfW * 0.55f, y - s * 0.01f)
        canvas.drawPath(archL, thin)
        val archR = Path()
        archR.moveTo(cx + bodyHalfW * 0.95f, y - s * 0.02f)
        archR.quadTo(cx + bodyHalfW * 0.72f, y + s * 0.03f, cx + bodyHalfW * 0.55f, y - s * 0.01f)
        canvas.drawPath(archR, thin)
    }

    // Feet perched on a simple branch line.
    canvas.drawLine(cx - s * 0.12f, s - margin, cx + s * 0.12f, s - margin, thin)
    for (dx in listOf(-s * 0.06f, s * 0.06f)) {
        val leg = Path()
        leg.moveTo(cx + dx, bodyBottom - s * 0.02f)
        leg.lineTo(cx + dx, s - margin)
        canvas.drawPath(leg, thin)
        for (t in listOf(-1f, 0f, 1f)) {
            val talon = Path()
            talon.moveTo(cx + dx, s - margin)
            talon.lineTo(cx + dx + t * s * 0.025f, s - margin + s * 0.03f)
            canvas.drawPath(talon, thin)
        }
    }
}

/** A stylized sitting cat in profile: circle head, teardrop body, curled tail, and fur patches. */
private fun drawCatSitting(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val margin = s * 0.08f
    val groundY = s - margin

    val headCx = s * 0.58f
    val headCy = s * 0.30f
    val headR = s * 0.125f

    // Body: rounded silhouette from the ground up to the neck, tucked under the head.
    val body = Path()
    body.moveTo(s * 0.30f, groundY)
    body.cubicTo(s * 0.16f, groundY - s * 0.05f, s * 0.12f, s * 0.62f, s * 0.16f, s * 0.55f)
    body.cubicTo(s * 0.20f, s * 0.46f, s * 0.22f, s * 0.42f, s * 0.28f, s * 0.38f)
    body.cubicTo(s * 0.33f, s * 0.35f, s * 0.37f, s * 0.35f, s * 0.42f, s * 0.34f)
    body.cubicTo(s * 0.48f, s * 0.33f, s * 0.52f, s * 0.35f, s * 0.56f, s * 0.40f)
    body.cubicTo(s * 0.60f, s * 0.46f, s * 0.61f, s * 0.55f, s * 0.60f, s * 0.65f)
    body.cubicTo(s * 0.59f, s * 0.78f, s * 0.58f, groundY - s * 0.04f, s * 0.56f, groundY)
    body.close()
    canvas.drawPath(body, paint)

    canvas.drawCircle(headCx, headCy, headR, paint)

    val earL = Path()
    earL.moveTo(headCx - headR * 0.65f, headCy - headR * 0.75f)
    earL.lineTo(headCx - headR * 0.95f, headCy - headR * 1.55f)
    earL.lineTo(headCx - headR * 0.15f, headCy - headR * 0.95f)
    earL.close()
    canvas.drawPath(earL, paint)
    canvas.drawPath(
        trianglePath(
            headCx - headR * 0.60f, headCy - headR * 0.90f,
            headCx - headR * 0.80f, headCy - headR * 1.35f,
            headCx - headR * 0.28f, headCy - headR * 0.98f,
        ),
        thin,
    )

    val earR = Path()
    earR.moveTo(headCx + headR * 0.45f, headCy - headR * 0.85f)
    earR.lineTo(headCx + headR * 0.70f, headCy - headR * 1.60f)
    earR.lineTo(headCx + headR * 0.95f, headCy - headR * 0.70f)
    earR.close()
    canvas.drawPath(earR, paint)
    canvas.drawPath(
        trianglePath(
            headCx + headR * 0.52f, headCy - headR * 1.00f,
            headCx + headR * 0.72f, headCy - headR * 1.38f,
            headCx + headR * 0.85f, headCy - headR * 0.90f,
        ),
        thin,
    )

    // Face.
    canvas.drawCircle(headCx + headR * 0.35f, headCy - headR * 0.05f, headR * 0.07f, thin)
    val nose = trianglePath(
        headCx + headR * 0.55f, headCy + headR * 0.12f,
        headCx + headR * 0.66f, headCy + headR * 0.12f,
        headCx + headR * 0.605f, headCy + headR * 0.22f,
    )
    canvas.drawPath(nose, thin)
    val mouth = Path()
    mouth.moveTo(headCx + headR * 0.605f, headCy + headR * 0.22f)
    mouth.quadTo(headCx + headR * 0.55f, headCy + headR * 0.34f, headCx + headR * 0.42f, headCy + headR * 0.28f)
    canvas.drawPath(mouth, thin)
    for (wy in listOf(-0.03f, 0.05f, 0.13f)) {
        val whisker = Path()
        whisker.moveTo(headCx + headR * 0.62f, headCy + headR * wy)
        whisker.lineTo(headCx + headR * 1.30f, headCy + headR * (wy - 0.05f))
        canvas.drawPath(whisker, thin)
    }

    // Tail curling from the haunch around toward the front paws.
    val tail = Path()
    tail.moveTo(s * 0.18f, s * 0.52f)
    tail.cubicTo(s * 0.10f, s * 0.52f, s * 0.09f, s * 0.70f, s * 0.13f, s * 0.82f)
    tail.cubicTo(s * 0.18f, s * 0.92f, s * 0.30f, s * 0.90f, s * 0.34f, s * 0.80f)
    canvas.drawPath(tail, paint)

    // Decorative fur patches subdividing the body into colorable zones.
    val patch1 = Path()
    patch1.moveTo(s * 0.22f, s * 0.46f)
    patch1.quadTo(s * 0.28f, s * 0.55f, s * 0.24f, s * 0.70f)
    canvas.drawPath(patch1, thin)

    val patch2 = Path()
    patch2.moveTo(s * 0.36f, s * 0.38f)
    patch2.quadTo(s * 0.44f, s * 0.50f, s * 0.42f, s * 0.66f)
    canvas.drawPath(patch2, thin)

    canvas.drawOval(RectF(s * 0.40f, s * 0.46f, s * 0.54f, s * 0.66f), thin)

    val legLine = Path()
    legLine.moveTo(s * 0.50f, groundY)
    legLine.lineTo(s * 0.505f, s * 0.70f)
    canvas.drawPath(legLine, thin)
}

/** A symmetric garden butterfly with vein-divided wings and a segmented body. */
private fun drawButterfly(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val cx = s * 0.5f
    val bodyTop = s * 0.16f

    val headCy = bodyTop + s * 0.03f
    val thoraxCy = s * 0.40f
    val abdomenCy = s * 0.66f

    canvas.drawOval(RectF(cx - s * 0.025f, headCy - s * 0.025f, cx + s * 0.025f, headCy + s * 0.025f), paint)
    canvas.drawOval(RectF(cx - s * 0.035f, thoraxCy - s * 0.10f, cx + s * 0.035f, thoraxCy + s * 0.10f), paint)
    canvas.drawOval(RectF(cx - s * 0.028f, abdomenCy - s * 0.13f, cx + s * 0.028f, abdomenCy + s * 0.13f), paint)

    val antL = Path()
    antL.moveTo(cx - s * 0.01f, headCy - s * 0.02f)
    antL.quadTo(cx - s * 0.06f, bodyTop - s * 0.05f, cx - s * 0.08f, bodyTop - s * 0.09f)
    canvas.drawPath(antL, thin)
    val antR = Path()
    antR.moveTo(cx + s * 0.01f, headCy - s * 0.02f)
    antR.quadTo(cx + s * 0.06f, bodyTop - s * 0.05f, cx + s * 0.08f, bodyTop - s * 0.09f)
    canvas.drawPath(antR, thin)

    val attachUpperY = thoraxCy - s * 0.06f
    drawButterflyWing(canvas, paint, thin, cx, attachUpperY, s, mirror = false, isUpper = true)
    drawButterflyWing(canvas, paint, thin, cx, attachUpperY, s, mirror = true, isUpper = true)

    val attachLowerY = thoraxCy + s * 0.08f
    drawButterflyWing(canvas, paint, thin, cx, attachLowerY, s, mirror = false, isUpper = false)
    drawButterflyWing(canvas, paint, thin, cx, attachLowerY, s, mirror = true, isUpper = false)
}

private fun mirrorX(cx: Float, dx: Float, mirror: Boolean): Float = if (mirror) cx - dx else cx + dx

private fun drawButterflyWing(
    canvas: Canvas,
    paint: Paint,
    thin: Paint,
    cx: Float,
    attachY: Float,
    s: Float,
    mirror: Boolean,
    isUpper: Boolean,
) {
    val outline = Path()
    if (isUpper) {
        outline.moveTo(mirrorX(cx, s * 0.01f, mirror), attachY)
        outline.cubicTo(
            mirrorX(cx, s * 0.10f, mirror), attachY - s * 0.14f,
            mirrorX(cx, s * 0.26f, mirror), attachY - s * 0.20f,
            mirrorX(cx, s * 0.34f, mirror), attachY - s * 0.08f,
        )
        outline.cubicTo(
            mirrorX(cx, s * 0.40f, mirror), attachY + s * 0.02f,
            mirrorX(cx, s * 0.38f, mirror), attachY + s * 0.16f,
            mirrorX(cx, s * 0.28f, mirror), attachY + s * 0.22f,
        )
        outline.cubicTo(
            mirrorX(cx, s * 0.18f, mirror), attachY + s * 0.27f,
            mirrorX(cx, s * 0.06f, mirror), attachY + s * 0.22f,
            mirrorX(cx, s * 0.01f, mirror), attachY + s * 0.14f,
        )
        outline.close()
    } else {
        outline.moveTo(mirrorX(cx, s * 0.01f, mirror), attachY)
        outline.cubicTo(
            mirrorX(cx, s * 0.08f, mirror), attachY + s * 0.02f,
            mirrorX(cx, s * 0.22f, mirror), attachY + s * 0.04f,
            mirrorX(cx, s * 0.26f, mirror), attachY + s * 0.14f,
        )
        outline.cubicTo(
            mirrorX(cx, s * 0.29f, mirror), attachY + s * 0.22f,
            mirrorX(cx, s * 0.22f, mirror), attachY + s * 0.30f,
            mirrorX(cx, s * 0.13f, mirror), attachY + s * 0.30f,
        )
        outline.cubicTo(
            mirrorX(cx, s * 0.05f, mirror), attachY + s * 0.30f,
            mirrorX(cx, s * 0.01f, mirror), attachY + s * 0.22f,
            mirrorX(cx, s * 0.01f, mirror), attachY + s * 0.10f,
        )
        outline.close()
    }
    canvas.drawPath(outline, paint)

    val veinCount = if (isUpper) 4 else 3
    for (i in 0 until veinCount) {
        val t = (i + 1) / (veinCount + 1).toFloat()
        val vein = Path()
        vein.moveTo(mirrorX(cx, s * 0.01f, mirror), attachY)
        if (isUpper) {
            val endX = mirrorX(cx, s * 0.10f + t * s * 0.24f, mirror)
            val endY = attachY - s * 0.16f + t * s * 0.34f
            vein.quadTo(mirrorX(cx, s * 0.16f + t * s * 0.12f, mirror), attachY - s * 0.04f + t * s * 0.10f, endX, endY)
        } else {
            val endX = mirrorX(cx, s * 0.06f + t * s * 0.20f, mirror)
            val endY = attachY + t * s * 0.28f
            vein.quadTo(mirrorX(cx, s * 0.10f + t * s * 0.12f, mirror), attachY + s * 0.10f + t * s * 0.10f, endX, endY)
        }
        canvas.drawPath(vein, thin)
    }

    if (isUpper) {
        canvas.drawCircle(mirrorX(cx, s * 0.20f, mirror), attachY + s * 0.02f, s * 0.025f, thin)
    }
}

/** A fish with fins, an eye, a gill line, and a grid of scale-pattern arcs across the body. */
private fun drawFish(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val cx = s * 0.42f
    val cy = s * 0.5f
    val bodyLen = s * 0.55f
    val bodyHeight = s * 0.30f

    val body = Path()
    body.moveTo(cx - bodyLen * 0.5f, cy)
    body.cubicTo(cx - bodyLen * 0.45f, cy - bodyHeight * 0.55f, cx - bodyLen * 0.10f, cy - bodyHeight * 0.55f, cx + bodyLen * 0.20f, cy - bodyHeight * 0.35f)
    body.cubicTo(cx + bodyLen * 0.32f, cy - bodyHeight * 0.20f, cx + bodyLen * 0.32f, cy + bodyHeight * 0.20f, cx + bodyLen * 0.20f, cy + bodyHeight * 0.35f)
    body.cubicTo(cx - bodyLen * 0.10f, cy + bodyHeight * 0.55f, cx - bodyLen * 0.45f, cy + bodyHeight * 0.55f, cx - bodyLen * 0.5f, cy)
    body.close()
    canvas.drawPath(body, paint)

    val tailBaseX = cx + bodyLen * 0.20f
    val tail = Path()
    tail.moveTo(tailBaseX, cy - bodyHeight * 0.30f)
    tail.cubicTo(tailBaseX + bodyLen * 0.20f, cy - bodyHeight * 0.55f, tailBaseX + bodyLen * 0.32f, cy - bodyHeight * 0.35f, tailBaseX + bodyLen * 0.30f, cy)
    tail.cubicTo(tailBaseX + bodyLen * 0.32f, cy + bodyHeight * 0.35f, tailBaseX + bodyLen * 0.20f, cy + bodyHeight * 0.55f, tailBaseX, cy + bodyHeight * 0.30f)
    canvas.drawPath(tail, paint)
    for (t in listOf(0.3f, 0.55f, 0.8f)) {
        val ray = Path()
        ray.moveTo(tailBaseX + bodyLen * 0.02f, cy - bodyHeight * 0.28f * (1f - t))
        ray.lineTo(tailBaseX + bodyLen * (0.20f + 0.10f * t), cy)
        canvas.drawPath(ray, thin)
    }

    val dorsal = Path()
    dorsal.moveTo(cx - bodyLen * 0.02f, cy - bodyHeight * 0.52f)
    dorsal.quadTo(cx + bodyLen * 0.02f, cy - bodyHeight * 0.85f, cx + bodyLen * 0.14f, cy - bodyHeight * 0.50f)
    canvas.drawPath(dorsal, paint)

    val ventral = Path()
    ventral.moveTo(cx - bodyLen * 0.05f, cy + bodyHeight * 0.50f)
    ventral.quadTo(cx + bodyLen * 0.02f, cy + bodyHeight * 0.78f, cx + bodyLen * 0.12f, cy + bodyHeight * 0.48f)
    canvas.drawPath(ventral, paint)

    val sideFin = Path()
    sideFin.moveTo(cx - bodyLen * 0.15f, cy + bodyHeight * 0.20f)
    sideFin.quadTo(cx - bodyLen * 0.22f, cy + bodyHeight * 0.42f, cx - bodyLen * 0.05f, cy + bodyHeight * 0.40f)
    canvas.drawPath(sideFin, thin)

    canvas.drawCircle(cx - bodyLen * 0.34f, cy - bodyHeight * 0.08f, bodyHeight * 0.10f, paint)
    canvas.drawCircle(cx - bodyLen * 0.34f, cy - bodyHeight * 0.08f, bodyHeight * 0.04f, thin)

    val gill = Path()
    gill.moveTo(cx - bodyLen * 0.24f, cy - bodyHeight * 0.38f)
    gill.quadTo(cx - bodyLen * 0.30f, cy, cx - bodyLen * 0.24f, cy + bodyHeight * 0.38f)
    canvas.drawPath(gill, thin)

    val rows = 4
    val cols = 6
    for (r in 0 until rows) {
        val rowY = cy - bodyHeight * 0.40f + (bodyHeight * 0.80f) * (r / (rows - 1).toFloat())
        for (c in 0 until cols) {
            val colX = cx - bodyLen * 0.12f + (bodyLen * 0.55f) * (c / (cols - 1).toFloat())
            val nx = (colX - cx) / (bodyLen * 0.5f)
            val ny = (rowY - cy) / (bodyHeight * 0.55f)
            if (nx * nx + ny * ny < 0.85f) {
                val scale = Path()
                scale.moveTo(colX - bodyLen * 0.045f, rowY)
                scale.quadTo(colX, rowY + bodyHeight * 0.09f, colX + bodyLen * 0.045f, rowY)
                canvas.drawPath(scale, thin)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Abstract
// ---------------------------------------------------------------------------------------------

/** A classic paisley teardrop with a curled tail and nested inner contour lines. */
private fun drawPaisley(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val cx = s * 0.5f
    val cy = s * 0.5f

    val outline = Path()
    outline.moveTo(cx, cy - s * 0.36f)
    outline.cubicTo(cx + s * 0.26f, cy - s * 0.30f, cx + s * 0.30f, cy - s * 0.05f, cx + s * 0.20f, cy + s * 0.14f)
    outline.cubicTo(cx + s * 0.12f, cy + s * 0.28f, cx - s * 0.06f, cy + s * 0.34f, cx - s * 0.20f, cy + s * 0.26f)
    outline.cubicTo(cx - s * 0.32f, cy + s * 0.19f, cx - s * 0.33f, cy + s * 0.02f, cx - s * 0.22f, cy - s * 0.06f)
    outline.cubicTo(cx - s * 0.14f, cy - s * 0.12f, cx - s * 0.02f, cy - s * 0.10f, cx - s * 0.02f, cy - s * 0.02f)
    outline.cubicTo(cx - s * 0.02f, cy + s * 0.06f, cx - s * 0.10f, cy + s * 0.08f, cx - s * 0.14f, cy + s * 0.02f)
    outline.cubicTo(cx - s * 0.16f, cy - s * 0.04f, cx - s * 0.10f, cy - s * 0.08f, cx - s * 0.02f, cy - s * 0.20f)
    outline.cubicTo(cx, cy - s * 0.28f, cx, cy - s * 0.32f, cx, cy - s * 0.36f)
    canvas.drawPath(outline, paint)

    for (scale in listOf(0.72f, 0.46f)) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.translate(-cx, -cy)
        canvas.drawPath(outline, thin)
        canvas.restore()
    }

    drawSpiral(canvas, thin, cx - s * 0.09f, cy - s * 0.01f, s * 0.05f)

    val dotAngles = listOf(20f, 55f, 90f, 125f, 160f)
    for (a in dotAngles) {
        val rad = Math.toRadians(a.toDouble())
        val dx = cx + s * 0.30f * cos(rad).toFloat()
        val dy = cy - s * 0.10f + s * 0.20f * sin(rad).toFloat()
        canvas.drawCircle(dx, dy, s * 0.012f, thin)
    }
}

/** Stacked wave bands inside a rounded frame, with a couple of small foam-spiral accents. */
private fun drawOceanWaves(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val margin = s * 0.08f

    val frame = RectF(margin, margin, s - margin, s - margin)
    canvas.drawRoundRect(frame, s * 0.05f, s * 0.05f, paint)

    val bandCount = 6
    val innerTop = margin + s * 0.04f
    val innerBottom = s - margin - s * 0.04f
    val innerLeft = margin + s * 0.03f
    val innerRight = s - margin - s * 0.03f
    val bandSpacing = (innerBottom - innerTop) / (bandCount - 1)

    for (i in 0 until bandCount) {
        val baseY = innerTop + bandSpacing * i
        val waveLength = (innerRight - innerLeft) / 3f
        val amplitude = s * 0.028f
        val wave = Path()
        wave.moveTo(innerLeft, baseY)
        var x = innerLeft
        var up = true
        while (x < innerRight - 1f) {
            val nextX = min(x + waveLength, innerRight)
            val ctrlY = if (up) baseY - amplitude else baseY + amplitude
            wave.quadTo((x + nextX) / 2f, ctrlY, nextX, baseY)
            x = nextX
            up = !up
        }
        canvas.drawPath(wave, if (i % 2 == 0) paint else thin)
    }

    drawSpiral(canvas, thin, innerLeft + (innerRight - innerLeft) * 0.22f, innerTop + bandSpacing * 1.5f, s * 0.035f)
    drawSpiral(canvas, thin, innerLeft + (innerRight - innerLeft) * 0.75f, innerTop + bandSpacing * 3.5f, s * 0.04f)
}

/** A sunburst of alternating long and short pointed rays around concentric core circles. */
private fun drawSunburst(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val cx = s * 0.5f
    val cy = s * 0.5f
    val coreR = s * 0.12f
    val maxRayR = s * 0.40f
    val rayCount = 16

    canvas.drawCircle(cx, cy, coreR, paint)
    canvas.drawCircle(cx, cy, coreR * 0.55f, thin)
    canvas.drawCircle(cx, cy, maxRayR * 0.62f, thin)

    for (i in 0 until rayCount) {
        val angle = i * (360f / rayCount)
        val rad = Math.toRadians(angle.toDouble())
        val isLong = i % 2 == 0
        val rayLen = if (isLong) maxRayR else maxRayR * 0.62f
        val rayWidth = if (isLong) s * 0.028f else s * 0.020f
        val halfWidthDeg = if (isLong) 4.5f else 3.2f

        val dirX = sin(rad).toFloat()
        val dirY = -cos(rad).toFloat()
        val perpX = cos(rad).toFloat()
        val perpY = sin(rad).toFloat()

        val tipX = cx + rayLen * dirX
        val tipY = cy + rayLen * dirY
        val midX = cx + rayLen * 0.5f * dirX
        val midY = cy + rayLen * 0.5f * dirY

        val baseRad1 = Math.toRadians((angle - halfWidthDeg).toDouble())
        val baseRad2 = Math.toRadians((angle + halfWidthDeg).toDouble())
        val base1X = cx + coreR * sin(baseRad1).toFloat()
        val base1Y = cy - coreR * cos(baseRad1).toFloat()
        val base2X = cx + coreR * sin(baseRad2).toFloat()
        val base2Y = cy - coreR * cos(baseRad2).toFloat()

        val ray = Path()
        ray.moveTo(base1X, base1Y)
        ray.quadTo(midX - rayWidth * perpX, midY - rayWidth * perpY, tipX, tipY)
        ray.quadTo(midX + rayWidth * perpX, midY + rayWidth * perpY, base2X, base2Y)
        ray.close()
        canvas.drawPath(ray, if (isLong) paint else thin)
    }
}

/** A single detailed feather: curved shaft, tapering outline, and rows of barb lines. */
private fun drawFeather(canvas: Canvas, size: Int) {
    val paint = linePaint(size)
    val thin = thinPaint(size)
    val s = size.toFloat()
    val margin = s * 0.08f

    val tipX = s * 0.5f
    val tipY = margin
    val baseX = s * 0.5f
    val baseY = s - margin
    val maxWidth = s * 0.24f
    val quillLen = s * 0.14f

    val shaft = Path()
    shaft.moveTo(baseX, baseY)
    shaft.quadTo(tipX + s * 0.03f, (baseY + tipY) / 2f, tipX, tipY)
    canvas.drawPath(shaft, paint)

    val leftOutline = Path()
    leftOutline.moveTo(tipX, tipY)
    leftOutline.cubicTo(
        tipX - maxWidth * 0.35f, tipY + (baseY - tipY) * 0.18f,
        tipX - maxWidth, tipY + (baseY - tipY) * 0.38f,
        tipX - maxWidth * 0.92f, tipY + (baseY - tipY) * 0.55f,
    )
    leftOutline.cubicTo(
        tipX - maxWidth * 0.75f, tipY + (baseY - tipY) * 0.70f,
        tipX - maxWidth * 0.30f, tipY + (baseY - tipY) * 0.82f,
        tipX - s * 0.02f, baseY - quillLen,
    )
    leftOutline.lineTo(baseX, baseY)
    canvas.drawPath(leftOutline, paint)

    val rightOutline = Path()
    rightOutline.moveTo(tipX, tipY)
    rightOutline.cubicTo(
        tipX + maxWidth * 0.35f, tipY + (baseY - tipY) * 0.18f,
        tipX + maxWidth, tipY + (baseY - tipY) * 0.38f,
        tipX + maxWidth * 0.92f, tipY + (baseY - tipY) * 0.55f,
    )
    rightOutline.cubicTo(
        tipX + maxWidth * 0.75f, tipY + (baseY - tipY) * 0.70f,
        tipX + maxWidth * 0.30f, tipY + (baseY - tipY) * 0.82f,
        tipX + s * 0.02f, baseY - quillLen,
    )
    rightOutline.lineTo(baseX, baseY)
    canvas.drawPath(rightOutline, paint)

    val barbCount = 16
    for (i in 1..barbCount) {
        val t = i / (barbCount + 1).toFloat()
        val shaftY = tipY + (baseY - tipY) * t
        val shaftX = tipX + s * 0.03f * (1f - abs(t - 0.5f) * 2f)
        val widthEnvelope = sin(t * PI.toFloat())
        val edgeDist = maxWidth * 0.90f * widthEnvelope + s * 0.01f

        val leftBarb = Path()
        leftBarb.moveTo(shaftX, shaftY)
        leftBarb.quadTo(shaftX - edgeDist * 0.5f, shaftY - s * 0.01f, shaftX - edgeDist, shaftY + s * 0.012f)
        canvas.drawPath(leftBarb, thin)

        val rightBarb = Path()
        rightBarb.moveTo(shaftX, shaftY)
        rightBarb.quadTo(shaftX + edgeDist * 0.5f, shaftY - s * 0.01f, shaftX + edgeDist, shaftY + s * 0.012f)
        canvas.drawPath(rightBarb, thin)
    }

    val bandTs = listOf(0.30f, 0.55f, 0.78f)
    for (t in bandTs) {
        val shaftY = tipY + (baseY - tipY) * t
        val widthEnvelope = sin(t * PI.toFloat())
        val edgeDist = maxWidth * 0.90f * widthEnvelope
        val chevron = Path()
        chevron.moveTo(tipX - edgeDist, shaftY + s * 0.02f)
        chevron.lineTo(tipX, shaftY - s * 0.03f)
        chevron.lineTo(tipX + edgeDist, shaftY + s * 0.02f)
        canvas.drawPath(chevron, thin)
    }
}
