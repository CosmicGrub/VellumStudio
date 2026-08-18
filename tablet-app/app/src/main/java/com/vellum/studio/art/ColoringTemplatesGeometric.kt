package com.vellum.studio.art

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Original, procedurally-generated mandala and geometric coloring-book pages.
 *
 * Every shape is pure vector line art: a stroked [Path]/[Canvas] drawing with no fills, so the
 * app's flood-fill tool (or a hand-drawn brush stroke) has clean closed regions to color inside.
 * Nothing here reproduces any existing published coloring book — every pattern is built from
 * scratch out of radial-symmetry and tessellation math (rotated motifs, regular polygons,
 * parametric spirals, triangular/hex lattices).
 */
object ColoringTemplatesGeometric {

    val templates: List<ColoringTemplate> = listOf(
        ColoringTemplate(
            id = "mandala-lotus",
            name = "Lotus Mandala",
            category = "Mandala",
            draw = { canvas, size -> drawLotusMandala(canvas, size) },
        ),
        ColoringTemplate(
            id = "mandala-starburst",
            name = "Star Burst Mandala",
            category = "Mandala",
            draw = { canvas, size -> drawStarburstMandala(canvas, size) },
        ),
        ColoringTemplate(
            id = "mandala-spiral-bloom",
            name = "Spiral Bloom Mandala",
            category = "Mandala",
            draw = { canvas, size -> drawSpiralBloomMandala(canvas, size) },
        ),
        ColoringTemplate(
            id = "mandala-diamond-lattice",
            name = "Diamond Lattice Mandala",
            category = "Mandala",
            draw = { canvas, size -> drawDiamondLatticeMandala(canvas, size) },
        ),
        ColoringTemplate(
            id = "mandala-scalloped-sun",
            name = "Scalloped Sun Mandala",
            category = "Mandala",
            draw = { canvas, size -> drawScallopedSunMandala(canvas, size) },
        ),
        ColoringTemplate(
            id = "geo-hex-tessellation",
            name = "Hex Tessellation",
            category = "Geometric",
            draw = { canvas, size -> drawHexTessellation(canvas, size) },
        ),
        ColoringTemplate(
            id = "geo-triangle-weave",
            name = "Interlocking Triangle Weave",
            category = "Geometric",
            draw = { canvas, size -> drawTriangleWeave(canvas, size) },
        ),
        ColoringTemplate(
            id = "geo-celtic-interlace",
            name = "Celtic Knot Interlace",
            category = "Geometric",
            draw = { canvas, size -> drawCelticInterlace(canvas, size) },
        ),
        ColoringTemplate(
            id = "geo-islamic-star",
            name = "Islamic Star Tile",
            category = "Geometric",
            draw = { canvas, size -> drawIslamicStarTile(canvas, size) },
        ),
    )
}

// ---------------------------------------------------------------------------------------------
// Core drawing primitives
// ---------------------------------------------------------------------------------------------

private const val INK: Int = 0xFF1A1A1A.toInt()

/** Solid near-black stroke-only paint, scaled for the given absolute width. Never filled. */
private fun newStrokePaint(strokeWidthPx: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    isAntiAlias = true
    color = INK
    strokeWidth = strokeWidthPx
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** Stroke width proportional to canvas [size], clamped so it never gets too thin to color against. */
private fun strokeW(size: Int, factor: Float): Float = (size * factor).coerceAtLeast(2f)

private fun toRad(deg: Float): Float = (deg * PI / 180.0).toFloat()

/**
 * Deterministic pseudo-random value in [0,1) derived from [seed] and [index]. Used to add subtle,
 * fully-repeatable organic variation (small rotation jitter) to a mandala without pulling in
 * kotlin.random.
 */
private fun hash01(seed: Long, index: Int): Float {
    val v = sin(seed.toDouble() * 12.9898 + index.toDouble() * 78.233) * 43758.5453
    return (v - floor(v)).toFloat()
}

/**
 * Closed regular-polygon path centered at (cx,cy). At rotationDeg = 0 the first vertex points
 * straight up, so sides=3/rotation=0 is an upward triangle, sides=4 is a diamond/square, etc.
 */
private fun regularPolygonPath(cx: Float, cy: Float, radius: Float, sides: Int, rotationDeg: Float = 0f): Path {
    val path = Path()
    for (i in 0 until sides) {
        val angle = toRad(rotationDeg - 90f + i * (360f / sides))
        val x = cx + radius * cos(angle)
        val y = cy + radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/**
 * Radial-symmetry workhorse: invokes [draw] [count] times, rotating the canvas by 360/count
 * around (cx,cy) each time via save()/rotate()/restore(). Any motif drawn "pointing up" (toward
 * negative Y from the pivot) inside [draw] therefore gets stamped evenly all the way around.
 */
private inline fun radialRepeat(canvas: Canvas, cx: Float, cy: Float, count: Int, startDeg: Float = 0f, draw: (Int) -> Unit) {
    val step = 360f / count
    for (i in 0 until count) {
        canvas.save()
        canvas.rotate(startDeg + step * i, cx, cy)
        draw(i)
        canvas.restore()
    }
}

// ---------------------------------------------------------------------------------------------
// Mandala motif primitives — each draws one unit "pointing up" from (cx,cy), meant to be stamped
// around a center via radialRepeat. All are closed-enough stroked shapes a colorer can fill.
// ---------------------------------------------------------------------------------------------

private enum class MandalaMotif { PETAL, TEARDROP, DIAMOND, SCALLOP, DOT, TRIANGLE, HOOK, LEAF }

private fun drawPetalMotif(canvas: Canvas, cx: Float, cy: Float, innerR: Float, outerR: Float, halfWidth: Float, paint: Paint) {
    val baseY = cy - innerR
    val tipY = cy - outerR
    val midY = cy - (innerR + outerR) / 2f
    val path = Path()
    path.moveTo(cx, baseY)
    path.quadTo(cx - halfWidth, midY, cx, tipY)
    path.quadTo(cx + halfWidth, midY, cx, baseY)
    path.close()
    canvas.drawPath(path, paint)
}

private fun drawTeardropMotif(canvas: Canvas, cx: Float, cy: Float, innerR: Float, outerR: Float, halfWidth: Float, paint: Paint) {
    val tipY = cy - innerR
    val bulbY = cy - outerR
    val midY = cy - (innerR + outerR) * 0.5f
    val path = Path()
    path.moveTo(cx, tipY)
    path.quadTo(cx - halfWidth * 1.4f, midY, cx - halfWidth, bulbY + halfWidth * 0.3f)
    path.quadTo(cx - halfWidth * 0.5f, bulbY - halfWidth * 0.6f, cx, bulbY - halfWidth)
    path.quadTo(cx + halfWidth * 0.5f, bulbY - halfWidth * 0.6f, cx + halfWidth, bulbY + halfWidth * 0.3f)
    path.quadTo(cx + halfWidth * 1.4f, midY, cx, tipY)
    path.close()
    canvas.drawPath(path, paint)
}

private fun drawDiamondMotif(canvas: Canvas, cx: Float, cy: Float, innerR: Float, outerR: Float, halfWidth: Float, paint: Paint) {
    val midY = cy - (innerR + outerR) / 2f
    val path = Path()
    path.moveTo(cx, cy - innerR)
    path.lineTo(cx + halfWidth, midY)
    path.lineTo(cx, cy - outerR)
    path.lineTo(cx - halfWidth, midY)
    path.close()
    canvas.drawPath(path, paint)
}

private fun drawTriangleSpikeMotif(canvas: Canvas, cx: Float, cy: Float, innerR: Float, outerR: Float, halfWidth: Float, paint: Paint) {
    val path = Path()
    path.moveTo(cx - halfWidth, cy - innerR)
    path.lineTo(cx, cy - outerR)
    path.lineTo(cx + halfWidth, cy - innerR)
    path.close()
    canvas.drawPath(path, paint)
}

private fun drawScallopBumpMotif(canvas: Canvas, cx: Float, cy: Float, radius: Float, bumpRadius: Float, paint: Paint) {
    val rect = RectF(cx - bumpRadius, cy - radius - bumpRadius, cx + bumpRadius, cy - radius + bumpRadius)
    canvas.drawArc(rect, 180f, 180f, false, paint)
}

private fun drawDotMotif(canvas: Canvas, cx: Float, cy: Float, radius: Float, dotRadius: Float, paint: Paint) {
    canvas.drawCircle(cx, cy - radius, dotRadius, paint)
}

private fun drawHookMotif(canvas: Canvas, cx: Float, cy: Float, innerR: Float, outerR: Float, paint: Paint) {
    val bandHalf = (outerR - innerR) / 2f
    val bandCenter = (outerR + innerR) / 2f
    val rect = RectF(cx - bandHalf, cy - bandCenter - bandHalf, cx + bandHalf, cy - bandCenter + bandHalf)
    canvas.drawArc(rect, -40f, 250f, false, paint)
}

private fun drawLeafDoubleMotif(canvas: Canvas, cx: Float, cy: Float, innerR: Float, outerR: Float, halfWidth: Float, paint: Paint) {
    drawPetalMotif(canvas, cx, cy, innerR, outerR, halfWidth, paint)
    val vein = Path()
    vein.moveTo(cx, cy - innerR)
    vein.lineTo(cx, cy - outerR)
    canvas.drawPath(vein, paint)
}

private fun drawMotifAt(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    motif: MandalaMotif,
    innerR: Float,
    outerR: Float,
    halfWidth: Float,
    paint: Paint,
) {
    when (motif) {
        MandalaMotif.PETAL -> drawPetalMotif(canvas, cx, cy, innerR, outerR, halfWidth, paint)
        MandalaMotif.TEARDROP -> drawTeardropMotif(canvas, cx, cy, innerR, outerR, halfWidth, paint)
        MandalaMotif.DIAMOND -> drawDiamondMotif(canvas, cx, cy, innerR, outerR, halfWidth, paint)
        MandalaMotif.TRIANGLE -> drawTriangleSpikeMotif(canvas, cx, cy, innerR, outerR, halfWidth, paint)
        MandalaMotif.SCALLOP -> drawScallopBumpMotif(canvas, cx, cy, (innerR + outerR) / 2f, halfWidth, paint)
        MandalaMotif.DOT -> drawDotMotif(canvas, cx, cy, (innerR + outerR) / 2f, halfWidth, paint)
        MandalaMotif.HOOK -> drawHookMotif(canvas, cx, cy, innerR, outerR, paint)
        MandalaMotif.LEAF -> drawLeafDoubleMotif(canvas, cx, cy, innerR, outerR, halfWidth, paint)
    }
}

/** A continuous wavy/scalloped circle (a chain of outward bumps), as one closed stroked path. */
private fun drawScallopedRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, bumps: Int, bumpDepth: Float, paint: Paint) {
    val path = Path()
    val step = 360f / bumps
    for (i in 0..bumps) {
        val angle = toRad(-90f + i * step)
        val x = cx + radius * cos(angle)
        val y = cy + radius * sin(angle)
        if (i == 0) {
            path.moveTo(x, y)
        } else {
            val midAngle = toRad(-90f + (i - 0.5f) * step)
            val bx = cx + (radius + bumpDepth) * cos(midAngle)
            val by = cy + (radius + bumpDepth) * sin(midAngle)
            path.quadTo(bx, by, x, y)
        }
    }
    path.close()
    canvas.drawPath(path, paint)
}

/** One arm of a parametric spiral (r grows linearly with angle), drawn "pointing up" from center. */
private fun drawSpiralArm(canvas: Canvas, cx: Float, cy: Float, startRadius: Float, endRadius: Float, turns: Float, paint: Paint, steps: Int = 48) {
    val path = Path()
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val radius = startRadius + (endRadius - startRadius) * t
        val angle = toRad(turns * 360f * t) - (PI / 2).toFloat()
        val x = cx + radius * cos(angle)
        val y = cy + radius * sin(angle)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    canvas.drawPath(path, paint)
}

// ---------------------------------------------------------------------------------------------
// The genuinely-parameterized mandala helper: concentric rings of motifs stamped around a center
// with radial symmetry, plus structural guide circles and a small center rosette. Five visually
// distinct templates below all funnel through this one function with different ring recipes.
// ---------------------------------------------------------------------------------------------

private data class MandalaRing(
    val innerRadiusFrac: Float,
    val outerRadiusFrac: Float,
    val count: Int,
    val motif: MandalaMotif,
    val widthFrac: Float = 0.05f,
    val startDeg: Float = 0f,
)

private fun drawMandala(
    canvas: Canvas,
    size: Int,
    rings: List<MandalaRing>,
    guideCircleFracs: List<Float> = emptyList(),
    centerPetals: Int = 8,
    centerMotif: MandalaMotif = MandalaMotif.PETAL,
    seed: Long = 0L,
    strokeFactor: Float = 0.0032f,
) {
    val cx = size / 2f
    val cy = size / 2f
    val maxRadius = size * 0.44f // 0.5 +/- 0.44 keeps everything within the 6% margin
    val paint = newStrokePaint(strokeW(size, strokeFactor))
    val guidePaint = newStrokePaint(strokeW(size, 0.0026f))

    for (f in guideCircleFracs) {
        canvas.drawCircle(cx, cy, maxRadius * f, guidePaint)
    }

    rings.forEachIndexed { ringIndex, ring ->
        val jitter = (hash01(seed, ringIndex * 7 + 3) - 0.5f) * 5f
        radialRepeat(canvas, cx, cy, ring.count, ring.startDeg + jitter) {
            drawMotifAt(
                canvas, cx, cy, ring.motif,
                maxRadius * ring.innerRadiusFrac,
                maxRadius * ring.outerRadiusFrac,
                maxRadius * ring.widthFrac,
                paint,
            )
        }
    }

    val centerR = maxRadius * 0.16f
    canvas.drawCircle(cx, cy, centerR * 0.32f, paint)
    radialRepeat(canvas, cx, cy, centerPetals, hash01(seed, 991) * 45f) {
        drawMotifAt(canvas, cx, cy, centerMotif, centerR * 0.28f, centerR, centerR * 0.34f, paint)
    }
}

// ---------------------------------------------------------------------------------------------
// Mandala templates (5 distinct silhouettes, all built on drawMandala)
// ---------------------------------------------------------------------------------------------

private fun drawLotusMandala(canvas: Canvas, size: Int) {
    drawMandala(
        canvas, size,
        rings = listOf(
            MandalaRing(0.16f, 0.34f, 8, MandalaMotif.PETAL, 0.075f),
            MandalaRing(0.30f, 0.54f, 12, MandalaMotif.TEARDROP, 0.065f, startDeg = 15f),
            MandalaRing(0.44f, 0.44f, 24, MandalaMotif.SCALLOP, 0.025f),
            MandalaRing(0.48f, 0.74f, 16, MandalaMotif.PETAL, 0.085f),
            MandalaRing(0.66f, 0.94f, 8, MandalaMotif.LEAF, 0.12f, startDeg = 22.5f),
        ),
        guideCircleFracs = listOf(0.28f, 0.46f, 0.64f, 0.94f),
        centerPetals = 8,
        centerMotif = MandalaMotif.PETAL,
        seed = 101L,
    )
}

private fun drawStarburstMandala(canvas: Canvas, size: Int) {
    drawMandala(
        canvas, size,
        rings = listOf(
            MandalaRing(0.10f, 0.28f, 12, MandalaMotif.TRIANGLE, 0.045f),
            MandalaRing(0.24f, 0.60f, 12, MandalaMotif.TRIANGLE, 0.07f),
            MandalaRing(0.55f, 0.95f, 12, MandalaMotif.TRIANGLE, 0.10f),
            MandalaRing(0.38f, 0.50f, 24, MandalaMotif.DIAMOND, 0.028f, startDeg = 7.5f),
        ),
        guideCircleFracs = listOf(0.24f, 0.55f),
        centerPetals = 12,
        centerMotif = MandalaMotif.TRIANGLE,
        seed = 202L,
    )
}

private fun drawSpiralBloomMandala(canvas: Canvas, size: Int) {
    val cx = size / 2f
    val cy = size / 2f
    val maxRadius = size * 0.44f
    val spiralPaint = newStrokePaint(strokeW(size, 0.0028f))
    radialRepeat(canvas, cx, cy, 6) {
        drawSpiralArm(canvas, cx, cy, maxRadius * 0.12f, maxRadius * 0.92f, 0.85f, spiralPaint)
    }
    drawMandala(
        canvas, size,
        rings = listOf(
            MandalaRing(0.16f, 0.30f, 6, MandalaMotif.PETAL, 0.07f),
            MandalaRing(0.36f, 0.50f, 12, MandalaMotif.DOT, 0.022f),
            MandalaRing(0.50f, 0.58f, 10, MandalaMotif.HOOK, 0.05f),
            MandalaRing(0.58f, 0.76f, 18, MandalaMotif.TEARDROP, 0.05f, startDeg = 10f),
        ),
        guideCircleFracs = listOf(0.16f, 0.90f),
        centerPetals = 6,
        centerMotif = MandalaMotif.PETAL,
        seed = 303L,
    )
}

private fun drawDiamondLatticeMandala(canvas: Canvas, size: Int) {
    val cx = size / 2f
    val cy = size / 2f
    val maxRadius = size * 0.44f

    drawMandala(
        canvas, size,
        rings = listOf(
            MandalaRing(0.14f, 0.30f, 8, MandalaMotif.DIAMOND, 0.06f),
            MandalaRing(0.28f, 0.52f, 16, MandalaMotif.DIAMOND, 0.05f, startDeg = 11.25f),
            MandalaRing(0.50f, 0.74f, 16, MandalaMotif.DIAMOND, 0.065f),
            MandalaRing(0.70f, 0.90f, 24, MandalaMotif.DIAMOND, 0.035f, startDeg = 7.5f),
        ),
        guideCircleFracs = listOf(0.30f, 0.52f, 0.74f, 0.90f),
        centerPetals = 8,
        centerMotif = MandalaMotif.DIAMOND,
        seed = 404L,
    )

    val spokePaint = newStrokePaint(strokeW(size, 0.0026f))
    radialRepeat(canvas, cx, cy, 16) {
        val line = Path()
        line.moveTo(cx, cy - maxRadius * 0.14f)
        line.lineTo(cx, cy - maxRadius * 0.90f)
        canvas.drawPath(line, spokePaint)
    }

    val framePaint = newStrokePaint(strokeW(size, 0.003f))
    canvas.drawPath(regularPolygonPath(cx, cy, maxRadius * 0.97f, 4, 0f), framePaint)
    canvas.drawPath(regularPolygonPath(cx, cy, maxRadius * 0.97f, 4, 45f), framePaint)
}

private fun drawScallopedSunMandala(canvas: Canvas, size: Int) {
    val cx = size / 2f
    val cy = size / 2f
    val maxRadius = size * 0.44f

    drawMandala(
        canvas, size,
        rings = listOf(
            MandalaRing(0.10f, 0.22f, 12, MandalaMotif.TRIANGLE, 0.04f),
            MandalaRing(0.30f, 0.30f, 20, MandalaMotif.DOT, 0.018f),
            MandalaRing(0.60f, 0.60f, 28, MandalaMotif.DOT, 0.016f),
        ),
        guideCircleFracs = listOf(0.30f, 0.45f, 0.60f),
        centerPetals = 12,
        centerMotif = MandalaMotif.TRIANGLE,
        seed = 505L,
    )

    val scallopPaint = newStrokePaint(strokeW(size, 0.003f))
    drawScallopedRing(canvas, cx, cy, maxRadius * 0.45f, 16, maxRadius * 0.05f, scallopPaint)
    drawScallopedRing(canvas, cx, cy, maxRadius * 0.85f, 24, maxRadius * 0.055f, scallopPaint)
}

// ---------------------------------------------------------------------------------------------
// Geometric (non-mandala) templates
// ---------------------------------------------------------------------------------------------

/** A pointy-top hexagon tessellation filling the square canvas, each cell nested with a second hex. */
private fun drawHexTessellation(canvas: Canvas, size: Int) {
    val paint = newStrokePaint(strokeW(size, 0.0032f))
    val innerPaint = newStrokePaint(strokeW(size, 0.0026f))
    val margin = size * 0.07f
    val hexR = size * 0.085f
    val colSpacing = hexR * sqrt(3f)
    val rowSpacing = hexR * 1.5f

    var row = 0
    var cy = margin + hexR
    while (cy + hexR <= size - margin + 0.5f) {
        val offsetX = if (row % 2 == 1) colSpacing / 2f else 0f
        var cx = margin + hexR + offsetX
        while (cx + hexR <= size - margin + 0.5f) {
            canvas.drawPath(regularPolygonPath(cx, cy, hexR, 6), paint)
            canvas.drawPath(regularPolygonPath(cx, cy, hexR * 0.55f, 6, 30f), innerPaint)
            cx += colSpacing
        }
        cy += rowSpacing
        row++
    }
}

private fun drawHexStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
    canvas.drawPath(regularPolygonPath(cx, cy, radius, 3, 0f), paint)
    canvas.drawPath(regularPolygonPath(cx, cy, radius, 3, 180f), paint)
}

/** A triangular lattice of grid lines with a six-pointed interlocking-triangle star at each node. */
private fun drawTriangleWeave(canvas: Canvas, size: Int) {
    val paint = newStrokePaint(strokeW(size, 0.003f))
    val margin = size * 0.07f
    val spacing = size * 0.12f
    val rowH = spacing * sqrt(3f) / 2f
    val minB = margin - 0.5f
    val maxB = size - margin + 0.5f
    val rows = floor((size - 2 * margin) / rowH).toInt()
    val cols = floor((size - 2 * margin) / spacing).toInt() + 1

    fun pt(r: Int, c: Int): FloatArray {
        val offset = if (r % 2 == 1) spacing / 2f else 0f
        return floatArrayOf(margin + offset + c * spacing, margin + r * rowH)
    }

    for (r in 0..rows) {
        for (c in -1..cols) {
            val p = pt(r, c)
            if (p[0] < minB || p[0] > maxB || p[1] < minB || p[1] > maxB) continue

            val pr = pt(r, c + 1)
            if (pr[0] in minB..maxB) {
                val line = Path()
                line.moveTo(p[0], p[1])
                line.lineTo(pr[0], pr[1])
                canvas.drawPath(line, paint)
            }

            if (r < rows) {
                val dc1 = if (r % 2 == 0) c - 1 else c
                val dc2 = if (r % 2 == 0) c else c + 1
                val pd1 = pt(r + 1, dc1)
                val pd2 = pt(r + 1, dc2)
                if (pd1[0] in minB..maxB) {
                    val line1 = Path()
                    line1.moveTo(p[0], p[1])
                    line1.lineTo(pd1[0], pd1[1])
                    canvas.drawPath(line1, paint)
                }
                if (pd2[0] in minB..maxB) {
                    val line2 = Path()
                    line2.moveTo(p[0], p[1])
                    line2.lineTo(pd2[0], pd2[1])
                    canvas.drawPath(line2, paint)
                }
            }

            drawHexStar(canvas, p[0], p[1], spacing * 0.3f, paint)
        }
    }
}

/** A circular Celtic-style interlace: nested rings of overlapping linked loops around a trinity knot. */
private fun drawCelticInterlace(canvas: Canvas, size: Int) {
    val cx = size / 2f
    val cy = size / 2f
    val maxRadius = size * 0.44f
    val paint = newStrokePaint(strokeW(size, 0.003f))
    val thinPaint = newStrokePaint(strokeW(size, 0.0026f))

    canvas.drawCircle(cx, cy, maxRadius, thinPaint)
    canvas.drawCircle(cx, cy, maxRadius * 0.62f, thinPaint)

    radialRepeat(canvas, cx, cy, 20) {
        canvas.drawCircle(cx, cy - maxRadius * 0.86f, maxRadius * 0.135f, paint)
    }
    radialRepeat(canvas, cx, cy, 14) {
        canvas.drawCircle(cx, cy - maxRadius * 0.58f, maxRadius * 0.115f, paint)
    }
    radialRepeat(canvas, cx, cy, 10) {
        canvas.drawCircle(cx, cy - maxRadius * 0.34f, maxRadius * 0.075f, paint)
    }
    radialRepeat(canvas, cx, cy, 3) {
        canvas.drawCircle(cx, cy - maxRadius * 0.16f, maxRadius * 0.22f, paint)
    }
    canvas.drawCircle(cx, cy, maxRadius * 0.06f, paint)
}

private fun drawOctagram(canvas: Canvas, cx: Float, cy: Float, radius: Float, paint: Paint) {
    canvas.drawPath(regularPolygonPath(cx, cy, radius, 4, 0f), paint)
    canvas.drawPath(regularPolygonPath(cx, cy, radius, 4, 45f), paint)
}

/** An Islamic-star-tile-inspired rosette: a central octagram linked by spokes to a ring of smaller ones. */
private fun drawIslamicStarTile(canvas: Canvas, size: Int) {
    val cx = size / 2f
    val cy = size / 2f
    val maxRadius = size * 0.44f
    val paint = newStrokePaint(strokeW(size, 0.0032f))
    val thinPaint = newStrokePaint(strokeW(size, 0.0026f))

    canvas.drawPath(regularPolygonPath(cx, cy, maxRadius, 8), thinPaint)

    radialRepeat(canvas, cx, cy, 8) {
        val ringR = maxRadius * 0.62f
        val tileR = maxRadius * 0.20f
        val line = Path()
        line.moveTo(cx, cy - maxRadius * 0.24f)
        line.lineTo(cx, cy - ringR + tileR * 0.7f)
        canvas.drawPath(line, thinPaint)
        drawOctagram(canvas, cx, cy - ringR, tileR, paint)
        canvas.drawPath(regularPolygonPath(cx, cy - ringR, tileR * 0.55f, 8, 0f), thinPaint)
    }

    radialRepeat(canvas, cx, cy, 8, 22.5f) {
        val ringR = maxRadius * 0.40f
        val tileR = maxRadius * 0.115f
        canvas.drawPath(regularPolygonPath(cx, cy - ringR, tileR, 8, 0f), thinPaint)
    }

    drawOctagram(canvas, cx, cy, maxRadius * 0.24f, paint)
    canvas.drawPath(regularPolygonPath(cx, cy, maxRadius * 0.13f, 8, 0f), thinPaint)
    canvas.drawCircle(cx, cy, maxRadius * 0.06f, thinPaint)
}
