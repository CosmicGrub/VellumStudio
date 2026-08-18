package com.vellum.studio.art

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * "Masterworks": original, hand-authored line-art coloring pages that are simplified, stylized
 * *interpretations* of the iconic composition/silhouette of specific, unambiguously public-domain
 * paintings (same genre as published books like Dover's "Great Impressionist Paintings Coloring
 * Book") - not traces or reproductions of the originals' actual brushwork or any photographic
 * reproduction of them. Every shape here is freshly drawn from scratch out of Path curves.
 *
 * As with the rest of the app's content, every path is stroked only (never filled), so every
 * enclosed region stays open for the fill/brush tools.
 *
 * Four works that used to be stylized interpretations here (Starry Night, The Great Wave, Mona
 * Lisa, Girl with a Pearl Earring) have been superseded by genuinely accurate line art derived
 * from real public-domain scans - see ColoringTemplatesMasterworksReal.kt and
 * tools/masterart_pipeline/. The remaining six stay as hand-authored interpretations for now.
 */
object ColoringTemplatesMasterworks {
    val templates: List<ColoringTemplate> = listOf(
        ColoringTemplate("masterwork-sunflowers", "Sunflowers", "Masterworks", ::drawSunflowers),
        ColoringTemplate("masterwork-birth-of-venus", "The Birth of Venus", "Masterworks", ::drawBirthOfVenus),
        ColoringTemplate("masterwork-tree-of-life", "The Tree of Life", "Masterworks", ::drawTreeOfLife),
        ColoringTemplate("masterwork-water-lily-pond", "Water Lily Pond", "Masterworks", ::drawWaterLilyPond),
        ColoringTemplate("masterwork-sunday-afternoon", "A Sunday Afternoon on the Island of La Grande Jatte", "Masterworks", ::drawSundayAfternoon),
        ColoringTemplate("masterwork-the-scream", "The Scream", "Masterworks", ::drawTheScream),
    )
}

// ---------------------------------------------------------------------------------------------
// Shared paint / geometry helpers
// ---------------------------------------------------------------------------------------------

private const val INK: Int = 0xFF1A1A1A.toInt()

/** Primary line weight - main silhouettes and outlines. Always stroked, never filled. */
private fun strokePaint(size: Int, factor: Float = 0.0032f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    isAntiAlias = true
    color = INK
    strokeWidth = (size * factor).coerceAtLeast(2f)
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** Lighter line weight - internal detail lines that subdivide a shape for coloring. */
private fun thinPaint(size: Int): Paint = strokePaint(size, 0.0026f)

private fun toRad(deg: Float): Float = (deg * PI / 180.0).toFloat()

/** Point at parameter [t] along a cubic Bezier defined by its four control points. */
private fun cubicPoint(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, t: Float): PointF {
    val u = 1f - t
    val x = u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
    val y = u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
    return PointF(x, y)
}

/** Point at parameter [t] along a quadratic Bezier defined by its three control points. */
private fun quadPoint(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, t: Float): PointF {
    val u = 1f - t
    val x = u * u * x0 + 2f * u * t * x1 + t * t * x2
    val y = u * u * y0 + 2f * u * t * y1 + t * t * y2
    return PointF(x, y)
}

/**
 * A curl/spiral polyline from [startR] to [endR] around (cx,cy), used as a reusable "swirl"
 * motif - Starry Night's sky clouds, and Klimt's Tree of Life branch-tip curls and root swirls.
 */
private fun spiralPath(cx: Float, cy: Float, startR: Float, endR: Float, turns: Float, startAngleDeg: Float = 0f, clockwise: Boolean = true, steps: Int = 40): Path {
    val path = Path()
    val dir = if (clockwise) 1f else -1f
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val r = startR + (endR - startR) * t
        val angle = toRad(startAngleDeg) + dir * toRad(turns * 360f * t)
        val x = cx + r * cos(angle)
        val y = cy + r * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

/**
 * Crescent (lune) outline: two tips (top/bottom) joined by a deep outer bulge and a shallower
 * inner bulge on the same side, so the sliver of space between the two curves reads as a
 * crescent moon. Used once for Starry Night's moon.
 */
private fun crescentPath(cx: Float, cy: Float, r: Float): Path {
    val path = Path()
    val topX = cx; val topY = cy - r
    val botX = cx; val botY = cy + r
    path.moveTo(topX, topY)
    path.cubicTo(cx - r * 1.25f, cy - r * 0.5f, cx - r * 1.25f, cy + r * 0.5f, botX, botY)
    path.cubicTo(cx - r * 0.55f, cy + r * 0.45f, cx - r * 0.55f, cy - r * 0.45f, topX, topY)
    path.close()
    return path
}

/** Small radiating burst of short lines around (cx,cy) - used for stars and the moon's glow. */
private fun drawGlowBurst(canvas: Canvas, paint: Paint, cx: Float, cy: Float, innerR: Float, outerR: Float, rays: Int, startDeg: Float = 0f) {
    for (i in 0 until rays) {
        val angle = toRad(startDeg + i * (360f / rays))
        val x1 = cx + innerR * cos(angle); val y1 = cy + innerR * sin(angle)
        val x2 = cx + outerR * cos(angle); val y2 = cy + outerR * sin(angle)
        canvas.drawLine(x1, y1, x2, y2, paint)
    }
}

/** A long pointed petal, base at the origin, tip pointing straight up (-y). Reused for every
 * sunflower's petals and for the small lily-flower blossoms on the water lily pond. */
private fun longPetalPath(len: Float, width: Float): Path = Path().apply {
    moveTo(0f, 0f)
    cubicTo(-width, -len * 0.35f, -width * 0.35f, -len * 0.85f, 0f, -len)
    cubicTo(width * 0.35f, -len * 0.85f, width, -len * 0.35f, 0f, 0f)
    close()
}

/**
 * A simple standing-figure silhouette: a round head (drawn separately in world space) plus a
 * torso that tapers to hips and then either a single closed hem (a robe/dress silhouette, when
 * [splitLegs] is false) or two separated bare legs (when true). Reused with different
 * proportions for Venus and for every park-goer in Seurat's "La Grande Jatte".
 */
private fun standingFigurePath(neckY: Float, shoulderW: Float, waistW: Float, hipW: Float, torsoBottomY: Float, legLen: Float, stanceW: Float, splitLegs: Boolean): Path {
    val path = Path()
    path.moveTo(-shoulderW / 2f, neckY)
    path.cubicTo(-shoulderW / 2f, neckY + (torsoBottomY - neckY) * 0.35f, -waistW / 2f, neckY + (torsoBottomY - neckY) * 0.6f, -hipW / 2f, torsoBottomY)
    if (splitLegs) {
        val bottomY = torsoBottomY + legLen
        val crotchY = torsoBottomY + legLen * 0.22f
        path.cubicTo(-hipW / 2f - stanceW * 0.08f, torsoBottomY + legLen * 0.5f, -hipW * 0.28f, torsoBottomY + legLen * 0.85f, -stanceW / 2f, bottomY)
        path.lineTo(-stanceW / 2f + hipW * 0.20f, bottomY)
        path.cubicTo(-hipW * 0.14f, torsoBottomY + legLen * 0.75f, -hipW * 0.06f, torsoBottomY + legLen * 0.35f, 0f, crotchY)
        path.cubicTo(hipW * 0.06f, torsoBottomY + legLen * 0.35f, hipW * 0.14f, torsoBottomY + legLen * 0.75f, stanceW / 2f - hipW * 0.20f, bottomY)
        path.lineTo(stanceW / 2f, bottomY)
        path.cubicTo(hipW * 0.28f, torsoBottomY + legLen * 0.85f, hipW / 2f + stanceW * 0.08f, torsoBottomY + legLen * 0.5f, hipW / 2f, torsoBottomY)
    } else {
        val bottomY = torsoBottomY + legLen
        val bottomHalfW = stanceW / 2f
        path.cubicTo(-hipW * 0.55f, torsoBottomY + legLen * 0.4f, -bottomHalfW * 1.05f, bottomY - legLen * 0.2f, -bottomHalfW, bottomY)
        path.lineTo(bottomHalfW, bottomY)
        path.cubicTo(bottomHalfW * 1.05f, bottomY - legLen * 0.2f, hipW * 0.55f, torsoBottomY + legLen * 0.4f, hipW / 2f, torsoBottomY)
    }
    path.cubicTo(waistW / 2f, neckY + (torsoBottomY - neckY) * 0.6f, shoulderW / 2f, neckY + (torsoBottomY - neckY) * 0.35f, shoulderW / 2f, neckY)
    path.close()
    return path
}

private fun drawStandingFigure(
    canvas: Canvas, paint: Paint, cx: Float, headCy: Float, headR: Float,
    shoulderW: Float, waistW: Float, hipW: Float, torsoBottomY: Float,
    legLen: Float, stanceW: Float, splitLegs: Boolean = false,
) {
    canvas.drawCircle(cx, headCy, headR, paint)
    val neckY = headCy + headR * 1.05f
    canvas.save()
    canvas.translate(cx, 0f)
    canvas.drawPath(standingFigurePath(neckY, shoulderW, waistW, hipW, torsoBottomY, legLen, stanceW, splitLegs), paint)
    canvas.restore()
}

// ---------------------------------------------------------------------------------------------
// 2. Sunflowers (Van Gogh, 1888)
// ---------------------------------------------------------------------------------------------

private fun drawSunflower(canvas: Canvas, cx: Float, cy: Float, r: Float, petalCount: Int, rotOffset: Float, paint: Paint, thin: Paint) {
    val petal = longPetalPath(r * 1.05f, r * 0.5f)
    for (i in 0 until petalCount) {
        val angle = rotOffset + i * (360f / petalCount)
        canvas.save(); canvas.translate(cx, cy); canvas.rotate(angle)
        canvas.drawPath(petal, paint)
        canvas.restore()
    }
    val petal2 = longPetalPath(r * 0.70f, r * 0.4f)
    for (i in 0 until petalCount) {
        val angle = rotOffset + (360f / petalCount) * 0.5f + i * (360f / petalCount)
        canvas.save(); canvas.translate(cx, cy); canvas.rotate(angle)
        canvas.drawPath(petal2, thin)
        canvas.restore()
    }
    canvas.drawCircle(cx, cy, r * 0.55f, paint)
    canvas.drawCircle(cx, cy, r * 0.36f, thin)
    canvas.drawCircle(cx, cy, r * 0.18f, thin)
    for (i in 0 until 10) {
        val angle = toRad(i * 36f + rotOffset)
        val x1 = cx + r * 0.2f * cos(angle); val y1 = cy + r * 0.2f * sin(angle)
        val x2 = cx + r * 0.5f * cos(angle); val y2 = cy + r * 0.5f * sin(angle)
        canvas.drawLine(x1, y1, x2, y2, thin)
    }
}

private fun drawSunflowers(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = strokePaint(size)
    val thin = thinPaint(size)
    val margin = s * 0.07f

    val tableY = s * 0.93f
    canvas.drawLine(margin, tableY, s - margin, tableY, paint)

    val vaseTop = s * 0.72f
    val vaseBottom = tableY
    val vaseCx = s * 0.5f
    val vase = Path()
    vase.moveTo(vaseCx - s * 0.10f, vaseTop)
    vase.cubicTo(vaseCx - s * 0.16f, vaseTop + s * 0.03f, vaseCx - s * 0.20f, vaseTop + s * 0.10f, vaseCx - s * 0.17f, vaseTop + s * 0.14f)
    vase.cubicTo(vaseCx - s * 0.14f, vaseBottom - s * 0.10f, vaseCx - s * 0.15f, vaseBottom - s * 0.03f, vaseCx - s * 0.19f, vaseBottom)
    vase.lineTo(vaseCx + s * 0.19f, vaseBottom)
    vase.cubicTo(vaseCx + s * 0.15f, vaseBottom - s * 0.03f, vaseCx + s * 0.14f, vaseBottom - s * 0.10f, vaseCx + s * 0.17f, vaseTop + s * 0.14f)
    vase.cubicTo(vaseCx + s * 0.20f, vaseTop + s * 0.10f, vaseCx + s * 0.16f, vaseTop + s * 0.03f, vaseCx + s * 0.10f, vaseTop)
    vase.close()
    canvas.drawPath(vase, paint)
    canvas.drawOval(RectF(vaseCx - s * 0.10f, vaseTop - s * 0.015f, vaseCx + s * 0.10f, vaseTop + s * 0.015f), thin)
    canvas.drawLine(vaseCx - s * 0.155f, vaseTop + s * 0.22f, vaseCx + s * 0.145f, vaseTop + s * 0.22f, thin)

    data class Flower(val x: Float, val y: Float, val r: Float, val petals: Int, val rot: Float)
    val flowers = listOf(
        Flower(s * 0.50f, s * 0.36f, s * 0.115f, 13, 0f),
        Flower(s * 0.34f, s * 0.32f, s * 0.10f, 12, 15f),
        Flower(s * 0.66f, s * 0.31f, s * 0.105f, 13, 8f),
        Flower(s * 0.23f, s * 0.44f, s * 0.095f, 12, 20f),
        Flower(s * 0.78f, s * 0.42f, s * 0.09f, 12, 5f),
        Flower(s * 0.42f, s * 0.20f, s * 0.075f, 11, 30f),
        Flower(s * 0.60f, s * 0.18f, s * 0.07f, 11, 12f),
        Flower(s * 0.29f, s * 0.18f, s * 0.065f, 10, 40f),
        Flower(s * 0.72f, s * 0.22f, s * 0.07f, 11, 0f),
        Flower(s * 0.17f, s * 0.28f, s * 0.075f, 10, 25f),
        Flower(s * 0.83f, s * 0.28f, s * 0.07f, 10, 10f),
        Flower(s * 0.50f, s * 0.53f, s * 0.10f, 12, 18f),
        Flower(s * 0.36f, s * 0.57f, s * 0.085f, 11, 5f),
    )
    for (f in flowers) {
        val stem = Path()
        stem.moveTo(f.x, f.y + f.r * 0.9f)
        stem.quadTo((f.x + vaseCx) / 2f, vaseTop + s * 0.02f, vaseCx + (f.x - vaseCx) * 0.15f, vaseTop + s * 0.01f)
        canvas.drawPath(stem, thin)
    }
    for (f in flowers) {
        drawSunflower(canvas, f.x, f.y, f.r, f.petals, f.rot, paint, thin)
    }
}

// ---------------------------------------------------------------------------------------------
// 5. The Birth of Venus (Botticelli, c. 1485)
// ---------------------------------------------------------------------------------------------

private fun drawBirthOfVenus(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = strokePaint(size)
    val thin = thinPaint(size)
    val margin = s * 0.07f
    val cx = s * 0.52f

    val waterTop = s * 0.86f
    for (i in 0 until 4) {
        val y = waterTop + i * s * 0.025f
        val wave = Path()
        var x = margin
        val len = s * 0.09f
        var up = true
        wave.moveTo(x, y)
        while (x < s - margin) {
            val nx = min(x + len, s - margin)
            wave.quadTo((x + nx) / 2f, y + (if (up) -s * 0.012f else s * 0.012f), nx, y)
            x = nx; up = !up
        }
        canvas.drawPath(wave, thin)
    }

    val shellCx = cx; val shellTopY = s * 0.78f; val shellW = s * 0.46f; val shellH = s * 0.12f
    val shellRibs = 9
    val shellOutline = Path()
    for (i in 0..shellRibs) {
        val t = i / shellRibs.toFloat()
        val angle = toRad(180f * t)
        val x = shellCx - shellW / 2f * cos(angle)
        val y = shellTopY + shellH * sin(angle)
        if (i == 0) shellOutline.moveTo(x, y) else shellOutline.lineTo(x, y)
    }
    canvas.drawPath(shellOutline, paint)
    canvas.drawLine(shellCx - shellW / 2f, shellTopY, shellCx + shellW / 2f, shellTopY, paint)
    for (i in 1 until shellRibs) {
        val t = i / shellRibs.toFloat()
        val angle = toRad(180f * t)
        val x = shellCx - shellW / 2f * cos(angle)
        val y = shellTopY + shellH * sin(angle)
        canvas.drawLine(shellCx, shellTopY, x, y, thin)
    }

    val headR = s * 0.052f
    val headCy = s * 0.28f
    val neckY = headCy + headR
    val torsoBottomY = s * 0.54f
    drawStandingFigure(canvas, paint, cx, headCy, headR, s * 0.15f, s * 0.10f, s * 0.14f, torsoBottomY, s * 0.22f, s * 0.13f, splitLegs = true)

    val hair = Path()
    hair.moveTo(cx - headR * 0.9f, headCy - headR * 0.3f)
    hair.cubicTo(cx - headR * 2.2f, headCy + s * 0.05f, cx - headR * 2.6f, headCy + s * 0.16f, cx - headR * 1.9f, headCy + s * 0.30f)
    hair.cubicTo(cx - headR * 1.3f, headCy + s * 0.42f, cx - headR * 1.8f, headCy + s * 0.52f, cx - headR * 1.2f, headCy + s * 0.62f)
    canvas.drawPath(hair, paint)
    val hairInner = Path()
    hairInner.moveTo(cx - headR * 0.6f, headCy + headR * 0.2f)
    hairInner.cubicTo(cx - headR * 1.6f, headCy + s * 0.12f, cx - headR * 1.6f, headCy + s * 0.30f, cx - headR * 1.0f, headCy + s * 0.48f)
    canvas.drawPath(hairInner, thin)

    val armL = Path()
    armL.moveTo(cx - s * 0.075f, neckY + s * 0.02f)
    armL.quadTo(cx - s * 0.12f, s * 0.40f, cx - s * 0.06f, s * 0.50f)
    canvas.drawPath(armL, thin)
    val armR = Path()
    armR.moveTo(cx + s * 0.075f, neckY + s * 0.02f)
    armR.quadTo(cx + s * 0.11f, s * 0.38f, cx + s * 0.05f, headCy + s * 0.30f)
    canvas.drawPath(armR, thin)

    val zx = s * 0.16f; val zy = s * 0.20f
    val zBody = Path()
    zBody.moveTo(zx - s * 0.09f, zy - s * 0.02f)
    zBody.cubicTo(zx - s * 0.02f, zy - s * 0.09f, zx + s * 0.08f, zy - s * 0.07f, zx + s * 0.14f, zy + s * 0.02f)
    zBody.cubicTo(zx + s * 0.09f, zy + s * 0.08f, zx - s * 0.02f, zy + s * 0.09f, zx - s * 0.09f, zy - s * 0.02f)
    canvas.drawPath(zBody, paint)
    canvas.drawCircle(zx - s * 0.10f, zy - s * 0.005f, s * 0.035f, paint)
    for (k in 0 until 3) {
        val wl = Path()
        val sy = zy + s * (0.02f + 0.05f * k)
        wl.moveTo(zx + s * 0.14f, sy - s * 0.02f)
        wl.cubicTo(zx + s * 0.28f, sy - s * 0.03f + s * 0.015f * k, zx + s * 0.42f, sy + s * 0.02f, zx + s * 0.55f, sy)
        canvas.drawPath(wl, thin)
    }
}

// ---------------------------------------------------------------------------------------------
// 7. The Tree of Life (Klimt, 1909)
// ---------------------------------------------------------------------------------------------

private fun drawEyeMotif(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, angleDeg: Float) {
    canvas.save(); canvas.translate(cx, cy); canvas.rotate(angleDeg)
    val eye = Path()
    eye.moveTo(-r, 0f)
    eye.quadTo(0f, -r * 0.7f, r, 0f)
    eye.quadTo(0f, r * 0.7f, -r, 0f)
    canvas.drawPath(eye, paint)
    canvas.drawCircle(0f, 0f, r * 0.28f, paint)
    canvas.restore()
}

private fun drawLeafCurlMotif(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, angleDeg: Float) {
    canvas.drawPath(spiralPath(cx, cy, r * 0.15f, r, 0.85f, startAngleDeg = angleDeg, steps = 20), paint)
}

private fun drawTreeOfLife(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = strokePaint(size)
    val thin = thinPaint(size)
    val margin = s * 0.06f
    val cx = s * 0.5f
    val baseY = s - margin

    canvas.drawLine(margin, baseY, s - margin, baseY, paint)
    canvas.drawPath(spiralPath(cx - s * 0.10f, baseY, s * 0.01f, s * 0.045f, 0.9f, startAngleDeg = 90f), thin)
    canvas.drawPath(spiralPath(cx + s * 0.10f, baseY, s * 0.01f, s * 0.045f, 0.9f, startAngleDeg = 90f, clockwise = false), thin)

    val trunkTopX = cx + s * 0.02f
    val trunkTopY = s * 0.52f
    val trunk = Path()
    trunk.moveTo(cx - s * 0.05f, baseY)
    trunk.cubicTo(cx - s * 0.06f, s * 0.78f, cx - s * 0.02f, s * 0.66f, trunkTopX - s * 0.03f, trunkTopY + s * 0.05f)
    canvas.drawPath(trunk, paint)
    val trunkR = Path()
    trunkR.moveTo(cx + s * 0.05f, baseY)
    trunkR.cubicTo(cx + s * 0.07f, s * 0.78f, cx + s * 0.04f, s * 0.66f, trunkTopX + s * 0.03f, trunkTopY + s * 0.05f)
    canvas.drawPath(trunkR, paint)

    data class Branch(val c1x: Float, val c1y: Float, val c2x: Float, val c2y: Float, val ex: Float, val ey: Float, val curlR: Float, val curlDir: Boolean)
    val branches = listOf(
        Branch(cx - s * 0.20f, trunkTopY - s * 0.05f, cx - s * 0.38f, s * 0.30f, cx - s * 0.30f, s * 0.16f, s * 0.055f, true),
        Branch(cx - s * 0.30f, trunkTopY + s * 0.02f, cx - s * 0.42f, s * 0.42f, cx - s * 0.40f, s * 0.30f, s * 0.05f, false),
        Branch(cx - s * 0.10f, trunkTopY - s * 0.15f, cx - s * 0.14f, s * 0.20f, cx - s * 0.06f, s * 0.10f, s * 0.045f, true),
        Branch(cx + s * 0.20f, trunkTopY - s * 0.05f, cx + s * 0.38f, s * 0.30f, cx + s * 0.30f, s * 0.16f, s * 0.055f, false),
        Branch(cx + s * 0.30f, trunkTopY + s * 0.02f, cx + s * 0.42f, s * 0.42f, cx + s * 0.40f, s * 0.30f, s * 0.05f, true),
        Branch(cx + s * 0.10f, trunkTopY - s * 0.15f, cx + s * 0.14f, s * 0.20f, cx + s * 0.06f, s * 0.10f, s * 0.045f, false),
        Branch(cx - s * 0.02f, trunkTopY - s * 0.20f, cx + s * 0.02f, s * 0.20f, cx - s * 0.01f, s * 0.09f, s * 0.05f, true),
    )
    val startX = trunkTopX; val startY = trunkTopY
    for (b in branches) {
        val branchPath = Path()
        branchPath.moveTo(startX, startY)
        branchPath.cubicTo(b.c1x, b.c1y, b.c2x, b.c2y, b.ex, b.ey)
        canvas.drawPath(branchPath, paint)
        canvas.drawPath(spiralPath(b.ex, b.ey, b.curlR * 0.15f, b.curlR, 1.4f, clockwise = b.curlDir), paint)
        for (i in 1..4) {
            val t = i / 5f
            val p = cubicPoint(startX, startY, b.c1x, b.c1y, b.c2x, b.c2y, b.ex, b.ey, t)
            val ang = i * 37f
            if (i % 2 == 0) drawEyeMotif(canvas, thin, p.x, p.y, s * 0.018f, ang) else drawLeafCurlMotif(canvas, thin, p.x, p.y, s * 0.022f, ang * 2f)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 8. Water Lily Pond (Monet, 1899)
// ---------------------------------------------------------------------------------------------

private fun lilyPadPath(cx: Float, cy: Float, r: Float, notchAngleDeg: Float): Path {
    val path = Path()
    val notchHalf = 18f
    val startDeg = notchAngleDeg + notchHalf
    val sweepDeg = 360f - notchHalf * 2f
    val rect = RectF(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.55f)
    path.arcTo(rect, startDeg, sweepDeg, true)
    path.close()
    return path
}

private fun drawWaterLilyPond(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = strokePaint(size)
    val thin = thinPaint(size)
    val margin = s * 0.07f

    for (i in 0 until 7) {
        val y = s * 0.42f + i * s * 0.075f
        val wave = Path()
        var x = margin
        val len = s * 0.10f
        var up = true
        wave.moveTo(x, y)
        while (x < s - margin) {
            val nx = min(x + len, s - margin)
            wave.quadTo((x + nx) / 2f, y + (if (up) -s * 0.010f else s * 0.010f), nx, y)
            x = nx; up = !up
        }
        canvas.drawPath(wave, thin)
    }

    val willowX = s * 0.10f; val willowY = s * 0.06f
    for (i in 0 until 9) {
        val t = i / 8f
        val dropLen = s * (0.20f + 0.14f * sin(t * PI.toFloat()))
        val startX = willowX + t * s * 0.34f
        val frond = Path()
        frond.moveTo(startX, willowY)
        frond.cubicTo(startX - s * 0.02f, willowY + dropLen * 0.5f, startX + s * 0.03f, willowY + dropLen * 0.8f, startX + s * 0.01f, willowY + dropLen)
        canvas.drawPath(frond, if (i % 2 == 0) paint else thin)
    }
    canvas.drawLine(willowX - s * 0.02f, willowY, willowX + s * 0.36f, willowY - s * 0.01f, paint)

    val bridgeY = s * 0.30f
    val bridgeLeft = s * 0.30f
    val bridgeRight = s * 0.86f
    val archTopC = ((bridgeLeft + bridgeRight) / 2f) to (bridgeY - s * 0.09f)
    val archBotC = ((bridgeLeft + bridgeRight) / 2f) to (bridgeY - s * 0.04f)
    val bridgeArch = Path()
    bridgeArch.moveTo(bridgeLeft, bridgeY + s * 0.05f)
    bridgeArch.quadTo(archTopC.first, archTopC.second, bridgeRight, bridgeY + s * 0.05f)
    canvas.drawPath(bridgeArch, paint)
    val bridgeArch2 = Path()
    bridgeArch2.moveTo(bridgeLeft + s * 0.01f, bridgeY + s * 0.10f)
    bridgeArch2.quadTo(archBotC.first, archBotC.second, bridgeRight - s * 0.01f, bridgeY + s * 0.10f)
    canvas.drawPath(bridgeArch2, paint)
    for (i in 0..8) {
        val t = i / 8f
        val topP = quadPoint(bridgeLeft, bridgeY + s * 0.05f, archTopC.first, archTopC.second, bridgeRight, bridgeY + s * 0.05f, t)
        val botP = quadPoint(bridgeLeft + s * 0.01f, bridgeY + s * 0.10f, archBotC.first, archBotC.second, bridgeRight - s * 0.01f, bridgeY + s * 0.10f, t)
        canvas.drawLine(topP.x, topP.y, botP.x, botP.y, thin)
    }
    canvas.drawLine(bridgeLeft + s * 0.02f, bridgeY + s * 0.09f, bridgeLeft + s * 0.02f, s * 0.42f, paint)
    canvas.drawLine(bridgeRight - s * 0.02f, bridgeY + s * 0.09f, bridgeRight - s * 0.02f, s * 0.42f, paint)

    data class Pad(val x: Float, val y: Float, val r: Float, val notch: Float, val hasFlower: Boolean)
    val pads = listOf(
        Pad(s * 0.20f, s * 0.52f, s * 0.055f, 30f, true),
        Pad(s * 0.34f, s * 0.62f, s * 0.045f, 200f, false),
        Pad(s * 0.50f, s * 0.50f, s * 0.06f, 100f, true),
        Pad(s * 0.62f, s * 0.68f, s * 0.05f, 300f, false),
        Pad(s * 0.74f, s * 0.56f, s * 0.055f, 150f, true),
        Pad(s * 0.85f, s * 0.72f, s * 0.045f, 250f, false),
        Pad(s * 0.42f, s * 0.80f, s * 0.05f, 60f, false),
        Pad(s * 0.18f, s * 0.82f, s * 0.05f, 320f, true),
    )
    for (p in pads) {
        canvas.drawPath(lilyPadPath(p.x, p.y, p.r, p.notch), paint)
        if (p.hasFlower) {
            val petal = longPetalPath(p.r * 0.9f, p.r * 0.4f)
            for (i in 0 until 6) {
                canvas.save(); canvas.translate(p.x, p.y - p.r * 0.1f); canvas.rotate(i * 60f)
                canvas.drawPath(petal, thin)
                canvas.restore()
            }
            canvas.drawCircle(p.x, p.y - p.r * 0.1f, p.r * 0.18f, thin)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 9. A Sunday Afternoon on the Island of La Grande Jatte (Seurat, 1884)
// ---------------------------------------------------------------------------------------------

private fun drawSundayAfternoon(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = strokePaint(size)
    val thin = thinPaint(size)
    val margin = s * 0.07f
    val groundY = s - margin

    val bankY = s * 0.40f
    canvas.drawLine(margin, bankY, s - margin, bankY, thin)
    for (bx in listOf(s * 0.25f, s * 0.68f)) {
        val sail = Path()
        sail.moveTo(bx, bankY)
        sail.lineTo(bx, bankY - s * 0.05f)
        sail.lineTo(bx + s * 0.025f, bankY)
        canvas.drawPath(sail, thin)
    }

    data class Tree(val x: Float, val trunkTopY: Float, val canopyR: Float)
    // x positions keep each canopy's full circle (x ± canopyR) safely inside 0..s - the original
    // 0.10s/0.90s placements pushed the two outer canopies partly off-canvas, clipping them.
    val trees = listOf(Tree(s * 0.18f, s * 0.20f, s * 0.12f), Tree(s * 0.80f, s * 0.16f, s * 0.14f), Tree(s * 0.50f, s * 0.10f, s * 0.09f))
    for (t in trees) {
        canvas.drawLine(t.x, t.trunkTopY + t.canopyR * 0.6f, t.x, groundY, paint)
        canvas.drawCircle(t.x, t.trunkTopY, t.canopyR, paint)
        canvas.drawArc(RectF(t.x - t.canopyR * 0.6f, t.trunkTopY - t.canopyR * 0.4f, t.x + t.canopyR * 0.6f, t.trunkTopY + t.canopyR * 0.5f), 200f, 140f, false, thin)
    }

    // Standing woman with parasol and long dress, foreground left.
    val wCx = s * 0.30f; val wHeadCy = s * 0.44f; val wHeadR = s * 0.032f
    val wDressTopY = wHeadCy + wHeadR * 2.2f
    drawStandingFigure(canvas, paint, wCx, wHeadCy, wHeadR, s * 0.075f, s * 0.045f, s * 0.09f, wDressTopY, s * 0.30f, s * 0.28f)
    canvas.drawLine(wCx - s * 0.11f, groundY - s * 0.06f, wCx + s * 0.12f, groundY - s * 0.05f, thin)
    val parasolHandleTopY = wHeadCy - s * 0.12f
    canvas.drawLine(wCx + s * 0.04f, wHeadCy + wHeadR * 0.5f, wCx + s * 0.07f, parasolHandleTopY, thin)
    canvas.drawArc(RectF(wCx - s * 0.02f, parasolHandleTopY - s * 0.05f, wCx + s * 0.16f, parasolHandleTopY + s * 0.05f), 180f, 180f, false, paint)
    for (k in 0..4) {
        val a = 180f + k * 45f
        val rad = toRad(a)
        canvas.drawLine(wCx + s * 0.07f, parasolHandleTopY, wCx + s * 0.07f + s * 0.09f * cos(rad), parasolHandleTopY + s * 0.05f * sin(rad), thin)
    }

    // A couple, mid-ground.
    val cHeadCy = s * 0.48f; val cHeadR = s * 0.026f
    drawStandingFigure(canvas, paint, s * 0.55f, cHeadCy, cHeadR, s * 0.06f, s * 0.045f, s * 0.07f, cHeadCy + cHeadR * 2f, s * 0.20f, s * 0.10f)
    drawStandingFigure(canvas, paint, s * 0.62f, cHeadCy - s * 0.01f, cHeadR * 0.9f, s * 0.05f, s * 0.035f, s * 0.055f, cHeadCy - s * 0.01f + cHeadR * 1.8f, s * 0.18f, s * 0.16f)

    // Seated figure, foreground right.
    val seatX = s * 0.78f; val seatY = groundY - s * 0.02f
    val seated = Path()
    seated.moveTo(seatX - s * 0.10f, seatY)
    seated.cubicTo(seatX - s * 0.11f, seatY - s * 0.08f, seatX - s * 0.06f, seatY - s * 0.14f, seatX, seatY - s * 0.13f)
    seated.cubicTo(seatX + s * 0.07f, seatY - s * 0.13f, seatX + s * 0.10f, seatY - s * 0.06f, seatX + s * 0.09f, seatY)
    seated.close()
    canvas.drawPath(seated, paint)
    canvas.drawCircle(seatX + s * 0.005f, seatY - s * 0.19f, s * 0.028f, paint)

    // Small child.
    val childCx = s * 0.42f; val childHeadCy = groundY - s * 0.15f; val childHeadR = s * 0.018f
    drawStandingFigure(canvas, paint, childCx, childHeadCy, childHeadR, s * 0.035f, s * 0.028f, s * 0.04f, childHeadCy + childHeadR * 2f, s * 0.10f, s * 0.06f)

    // Small dog.
    val dogX = s * 0.47f; val dogY = groundY - s * 0.015f
    val dog = Path()
    dog.moveTo(dogX - s * 0.035f, dogY)
    dog.cubicTo(dogX - s * 0.045f, dogY - s * 0.04f, dogX - s * 0.03f, dogY - s * 0.055f, dogX - s * 0.01f, dogY - s * 0.05f)
    dog.cubicTo(dogX + s * 0.01f, dogY - s * 0.055f, dogX + s * 0.03f, dogY - s * 0.05f, dogX + s * 0.04f, dogY - s * 0.035f)
    dog.cubicTo(dogX + s * 0.045f, dogY - s * 0.02f, dogX + s * 0.04f, dogY - s * 0.005f, dogX + s * 0.03f, dogY)
    dog.close()
    canvas.drawPath(dog, thin)

    // A solitary background figure near the right tree.
    val soloX = s * 0.85f; val soloHeadCy = s * 0.34f; val soloHeadR = s * 0.018f
    drawStandingFigure(canvas, paint, soloX, soloHeadCy, soloHeadR, s * 0.032f, s * 0.026f, s * 0.036f, soloHeadCy + soloHeadR * 2f, s * 0.10f, s * 0.05f)
    canvas.drawLine(soloX, soloHeadCy - soloHeadR * 1.3f, soloX, soloHeadCy - soloHeadR * 2.4f, thin)
    canvas.drawLine(soloX - soloHeadR * 0.9f, soloHeadCy - soloHeadR * 2.4f, soloX + soloHeadR * 0.9f, soloHeadCy - soloHeadR * 2.4f, thin)
}

// ---------------------------------------------------------------------------------------------
// 10. The Scream (Munch, 1893)
// ---------------------------------------------------------------------------------------------

private fun drawTheScream(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = strokePaint(size)
    val thin = thinPaint(size)
    val margin = s * 0.07f

    val bandYs = listOf(s * 0.12f, s * 0.20f, s * 0.28f, s * 0.36f, s * 0.44f, s * 0.52f)
    for ((i, by) in bandYs.withIndex()) {
        val band = Path()
        band.moveTo(margin, by)
        band.cubicTo(s * 0.20f, by - s * 0.05f + (i % 2) * s * 0.03f, s * 0.35f, by + s * 0.06f, s * 0.55f, by - s * 0.02f)
        band.cubicTo(s * 0.70f, by - s * 0.07f, s * 0.85f, by + s * 0.04f, s - margin, by - s * 0.01f)
        canvas.drawPath(band, if (i % 2 == 0) paint else thin)
    }

    val vpX = s * 0.86f; val vpY = s * 0.56f
    val rail1 = Path(); rail1.moveTo(margin, s * 0.92f); rail1.lineTo(vpX, vpY)
    canvas.drawPath(rail1, paint)
    val rail2 = Path(); rail2.moveTo(margin, s * 0.78f); rail2.lineTo(vpX, vpY)
    canvas.drawPath(rail2, paint)
    for (t in listOf(0.15f, 0.30f, 0.45f, 0.60f)) {
        val x1 = margin + (vpX - margin) * t; val y1 = s * 0.92f + (vpY - s * 0.92f) * t
        val x2 = margin + (vpX - margin) * t; val y2 = s * 0.78f + (vpY - s * 0.78f) * t
        canvas.drawLine(x1, y1, x2, y2, thin)
    }
    val rail3 = Path(); rail3.moveTo(s - margin, s * 0.95f); rail3.lineTo(vpX, vpY)
    canvas.drawPath(rail3, thin)

    val cx = s * 0.38f
    val headCy = s * 0.36f
    val headRx = s * 0.09f
    val headRy = s * 0.115f
    val head = Path()
    head.moveTo(cx, headCy - headRy)
    head.cubicTo(cx + headRx * 1.1f, headCy - headRy * 0.6f, cx + headRx * 0.9f, headCy + headRy * 0.7f, cx + headRx * 0.55f, headCy + headRy)
    head.cubicTo(cx + headRx * 0.15f, headCy + headRy * 1.15f, cx - headRx * 0.15f, headCy + headRy * 1.15f, cx - headRx * 0.55f, headCy + headRy)
    head.cubicTo(cx - headRx * 0.9f, headCy + headRy * 0.7f, cx - headRx * 1.1f, headCy - headRy * 0.6f, cx, headCy - headRy)
    canvas.drawPath(head, paint)

    canvas.drawOval(RectF(cx - headRx * 0.30f, headCy + headRy * 0.15f, cx + headRx * 0.30f, headCy + headRy * 0.75f), paint)
    canvas.drawOval(RectF(cx - headRx * 0.55f, headCy - headRy * 0.15f, cx - headRx * 0.15f, headCy + headRy * 0.05f), thin)
    canvas.drawOval(RectF(cx + headRx * 0.15f, headCy - headRy * 0.15f, cx + headRx * 0.55f, headCy + headRy * 0.05f), thin)

    for (side in listOf(-1f, 1f)) {
        val handCx = cx + side * headRx * 1.15f
        val handCy = headCy + headRy * 0.35f
        canvas.drawOval(RectF(handCx - headRx * 0.35f, handCy - headRy * 0.45f, handCx + headRx * 0.35f, handCy + headRy * 0.45f), paint)
        for (fi in -1..1) {
            canvas.drawLine(handCx + side * headRx * 0.30f, handCy + fi * headRy * 0.18f, handCx + side * headRx * 0.60f, handCy + fi * headRy * 0.24f, thin)
        }
        canvas.drawLine(handCx, handCy + headRy * 0.45f, cx + side * headRx * 0.5f, headCy + headRy * 1.4f, thin)
    }

    val bodyTopY = headCy + headRy * 1.1f
    val bodyBottomY = s * 0.90f
    val body = Path()
    body.moveTo(cx - headRx * 0.6f, bodyTopY)
    body.cubicTo(cx - headRx * 1.3f, bodyTopY + s * 0.06f, cx - headRx * 0.7f, bodyBottomY - s * 0.10f, cx - headRx * 0.9f, bodyBottomY)
    body.lineTo(cx + headRx * 0.9f, bodyBottomY)
    body.cubicTo(cx + headRx * 0.7f, bodyBottomY - s * 0.10f, cx + headRx * 1.3f, bodyTopY + s * 0.06f, cx + headRx * 0.6f, bodyTopY)
    // See the matching fix on Mona Lisa's shoulders path: this closes the collar edge so the
    // silhouette is fully sealed instead of relying on the head shape to visually bridge the gap.
    body.close()
    canvas.drawPath(body, paint)
    for (k in 0..1) {
        val dy = bodyTopY + (bodyBottomY - bodyTopY) * (0.4f + k * 0.25f)
        val drape = Path()
        drape.moveTo(cx - headRx * 0.5f, dy)
        drape.quadTo(cx, dy + s * 0.02f, cx + headRx * 0.5f, dy)
        canvas.drawPath(drape, thin)
    }
}
