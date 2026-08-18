package com.vellum.studio.art

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * "Kids": original, wholly hand-authored friendly-character and simple-scene coloring pages,
 * aimed at young colorists — big rounded shapes, few sharp angles, generous line weight, minimal
 * fine detail so a small hand with a stylus can stay inside the lines. These are entirely
 * original designs (not modeled on, named after, or meant to evoke any existing show or
 * franchise's characters) — see the same house rule as ColoringTemplatesMasterworks: original
 * work only, never a redrawn copy of someone else's protected character design.
 *
 * As with the rest of the app's content, every path is stroked only (never filled), so every
 * enclosed region stays open for the fill/brush tools.
 */
object ColoringTemplatesKids {
    val templates: List<ColoringTemplate> = listOf(
        ColoringTemplate("kids-puppy", "Friendly Puppy", "Kids", ::drawPuppy),
        ColoringTemplate("kids-kitten", "Curious Kitten", "Kids", ::drawKitten),
        ColoringTemplate("kids-elephant", "Cheerful Elephant", "Kids", ::drawElephant),
        ColoringTemplate("kids-bear", "Happy Bear", "Kids", ::drawBear),
        ColoringTemplate("kids-robot", "Friendly Robot Pal", "Kids", ::drawRobot),
        ColoringTemplate("kids-house-sun", "Sunny Little House", "Kids", ::drawHouseAndSun),
        ColoringTemplate("kids-garden", "Garden Friends", "Kids", ::drawGardenFriends),
    )
}

// ---------------------------------------------------------------------------------------------
// Shared paint helpers (generous weight, forgiving for young hands)
// ---------------------------------------------------------------------------------------------

private fun kidsLinePaint(size: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = 0xFF1A1A1A.toInt()
    strokeWidth = (size * 0.0055f).coerceAtLeast(3f)
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

private fun kidsThinPaint(size: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = 0xFF1A1A1A.toInt()
    strokeWidth = (size * 0.0038f).coerceAtLeast(2.5f)
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** A big friendly round eye: outer circle plus a small solid-feeling pupil ring. */
private fun drawBigEye(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float) {
    canvas.drawCircle(cx, cy, r, paint)
    canvas.drawCircle(cx + r * 0.12f, cy - r * 0.12f, r * 0.42f, paint)
}

// ---------------------------------------------------------------------------------------------
// 1. Friendly Puppy
// ---------------------------------------------------------------------------------------------

private fun drawPuppy(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = kidsLinePaint(size)
    val thin = kidsThinPaint(size)
    val cx = s * 0.5f
    val headCy = s * 0.38f
    val headR = s * 0.22f

    // Head
    canvas.drawCircle(cx, headCy, headR, paint)

    // Floppy ears, one each side
    for (side in listOf(-1f, 1f)) {
        val ear = Path()
        ear.moveTo(cx + side * headR * 0.75f, headCy - headR * 0.35f)
        ear.cubicTo(
            cx + side * headR * 1.55f, headCy - headR * 0.15f,
            cx + side * headR * 1.6f, headCy + headR * 0.75f,
            cx + side * headR * 0.85f, headCy + headR * 0.85f,
        )
        canvas.drawPath(ear, paint)
    }

    drawBigEye(canvas, paint, cx - headR * 0.35f, headCy - headR * 0.05f, headR * 0.16f)
    drawBigEye(canvas, paint, cx + headR * 0.35f, headCy - headR * 0.05f, headR * 0.16f)

    // Snout
    val snoutCy = headCy + headR * 0.45f
    canvas.drawOval(RectF(cx - headR * 0.32f, snoutCy - headR * 0.2f, cx + headR * 0.32f, snoutCy + headR * 0.22f), thin)
    canvas.drawCircle(cx, snoutCy - headR * 0.05f, headR * 0.1f, paint)
    val mouth = Path()
    mouth.moveTo(cx, snoutCy + headR * 0.1f)
    mouth.quadTo(cx - headR * 0.18f, snoutCy + headR * 0.28f, cx - headR * 0.32f, snoutCy + headR * 0.14f)
    canvas.drawPath(mouth, thin)
    val mouth2 = Path()
    mouth2.moveTo(cx, snoutCy + headR * 0.1f)
    mouth2.quadTo(cx + headR * 0.18f, snoutCy + headR * 0.28f, cx + headR * 0.32f, snoutCy + headR * 0.14f)
    canvas.drawPath(mouth2, thin)

    // Body (sitting)
    val bodyTop = headCy + headR * 0.85f
    val bodyBottom = s * 0.86f
    val bodyHalfW = s * 0.20f
    val body = Path()
    body.moveTo(cx - bodyHalfW * 0.7f, bodyTop)
    body.cubicTo(cx - bodyHalfW * 1.3f, bodyTop + s * 0.1f, cx - bodyHalfW * 1.1f, bodyBottom - s * 0.06f, cx - bodyHalfW, bodyBottom)
    body.lineTo(cx + bodyHalfW, bodyBottom)
    body.cubicTo(cx + bodyHalfW * 1.1f, bodyBottom - s * 0.06f, cx + bodyHalfW * 1.3f, bodyTop + s * 0.1f, cx + bodyHalfW * 0.7f, bodyTop)
    body.close()
    canvas.drawPath(body, paint)

    // Front paws
    for (side in listOf(-1f, 1f)) {
        canvas.drawOval(
            RectF(
                cx + side * bodyHalfW * 0.55f - s * 0.035f, bodyBottom - s * 0.07f,
                cx + side * bodyHalfW * 0.55f + s * 0.035f, bodyBottom + s * 0.02f,
            ),
            thin,
        )
    }

    // Wagging tail
    val tail = Path()
    tail.moveTo(cx + bodyHalfW * 0.9f, bodyTop + s * 0.12f)
    tail.cubicTo(cx + bodyHalfW * 1.7f, bodyTop, cx + bodyHalfW * 1.9f, bodyTop - s * 0.16f, cx + bodyHalfW * 1.5f, bodyTop - s * 0.24f)
    canvas.drawPath(tail, paint)
}

// ---------------------------------------------------------------------------------------------
// 2. Curious Kitten
// ---------------------------------------------------------------------------------------------

private fun drawKitten(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = kidsLinePaint(size)
    val thin = kidsThinPaint(size)
    val cx = s * 0.5f
    val headCy = s * 0.40f
    val headR = s * 0.20f

    canvas.drawCircle(cx, headCy, headR, paint)

    // Pointed triangular ears
    for (side in listOf(-1f, 1f)) {
        val ear = Path()
        ear.moveTo(cx + side * headR * 0.55f, headCy - headR * 0.75f)
        ear.lineTo(cx + side * headR * 1.05f, headCy - headR * 1.5f)
        ear.lineTo(cx + side * headR * 1.15f, headCy - headR * 0.45f)
        ear.close()
        canvas.drawPath(ear, paint)
        val earInner = Path()
        earInner.moveTo(cx + side * headR * 0.72f, headCy - headR * 0.82f)
        earInner.lineTo(cx + side * headR * 0.98f, headCy - headR * 1.15f)
        canvas.drawPath(earInner, thin)
    }

    drawBigEye(canvas, paint, cx - headR * 0.32f, headCy, headR * 0.17f)
    drawBigEye(canvas, paint, cx + headR * 0.32f, headCy, headR * 0.17f)

    canvas.drawCircle(cx, headCy + headR * 0.28f, headR * 0.06f, paint)
    val mouth = Path()
    mouth.moveTo(cx, headCy + headR * 0.34f)
    mouth.quadTo(cx - headR * 0.14f, headCy + headR * 0.48f, cx - headR * 0.24f, headCy + headR * 0.38f)
    canvas.drawPath(mouth, thin)
    val mouth2 = Path()
    mouth2.moveTo(cx, headCy + headR * 0.34f)
    mouth2.quadTo(cx + headR * 0.14f, headCy + headR * 0.48f, cx + headR * 0.24f, headCy + headR * 0.38f)
    canvas.drawPath(mouth2, thin)

    // Whiskers
    for (side in listOf(-1f, 1f)) {
        for (t in listOf(-1f, 0f, 1f)) {
            val w = Path()
            w.moveTo(cx + side * headR * 0.5f, headCy + headR * 0.22f + t * headR * 0.08f)
            w.lineTo(cx + side * headR * 1.15f, headCy + headR * 0.16f + t * headR * 0.14f)
            canvas.drawPath(w, thin)
        }
    }

    // Body sitting with tail curled around
    val bodyTop = headCy + headR * 0.8f
    val bodyBottom = s * 0.84f
    val bodyHalfW = s * 0.17f
    val body = Path()
    body.moveTo(cx - bodyHalfW * 0.7f, bodyTop)
    body.cubicTo(cx - bodyHalfW * 1.2f, bodyTop + s * 0.08f, cx - bodyHalfW, bodyBottom - s * 0.05f, cx - bodyHalfW * 0.9f, bodyBottom)
    body.lineTo(cx + bodyHalfW * 0.9f, bodyBottom)
    body.cubicTo(cx + bodyHalfW, bodyBottom - s * 0.05f, cx + bodyHalfW * 1.2f, bodyTop + s * 0.08f, cx + bodyHalfW * 0.7f, bodyTop)
    body.close()
    canvas.drawPath(body, paint)

    val tail = Path()
    tail.moveTo(cx + bodyHalfW * 0.85f, bodyBottom - s * 0.04f)
    tail.cubicTo(cx + bodyHalfW * 1.8f, bodyBottom, cx + bodyHalfW * 2.1f, bodyTop + s * 0.05f, cx + bodyHalfW * 1.5f, bodyTop - s * 0.02f)
    tail.cubicTo(cx + bodyHalfW * 1.1f, bodyTop - s * 0.06f, cx + bodyHalfW * 1.3f, bodyTop + s * 0.12f, cx + bodyHalfW * 1.6f, bodyTop + s * 0.1f)
    canvas.drawPath(tail, paint)
}

// ---------------------------------------------------------------------------------------------
// 3. Cheerful Elephant
// ---------------------------------------------------------------------------------------------

private fun drawElephant(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = kidsLinePaint(size)
    val thin = kidsThinPaint(size)
    val cx = s * 0.48f
    val headCy = s * 0.36f
    val headR = s * 0.20f

    // Big ears behind the head
    for (side in listOf(-1f, 1f)) {
        val ear = Path()
        val ex = cx + side * headR * 1.1f
        ear.addOval(RectF(ex - headR * 0.62f, headCy - headR * 0.6f, ex + headR * 0.62f, headCy + headR * 0.62f), Path.Direction.CW)
        canvas.drawPath(ear, paint)
    }

    canvas.drawCircle(cx, headCy, headR, paint)
    drawBigEye(canvas, paint, cx - headR * 0.3f, headCy - headR * 0.05f, headR * 0.15f)
    drawBigEye(canvas, paint, cx + headR * 0.3f, headCy - headR * 0.05f, headR * 0.15f)

    // Trunk, curling at the tip
    val trunk = Path()
    trunk.moveTo(cx - headR * 0.14f, headCy + headR * 0.55f)
    trunk.cubicTo(cx - headR * 0.2f, headCy + headR * 1.1f, cx - headR * 0.5f, headCy + headR * 1.3f, cx - headR * 0.42f, headCy + headR * 1.65f)
    trunk.cubicTo(cx - headR * 0.36f, headCy + headR * 1.9f, cx - headR * 0.05f, headCy + headR * 1.85f, cx - headR * 0.12f, headCy + headR * 1.6f)
    canvas.drawPath(trunk, paint)
    val trunkInner = Path()
    trunkInner.moveTo(cx + headR * 0.12f, headCy + headR * 0.55f)
    trunkInner.cubicTo(cx + headR * 0.08f, headCy + headR * 1.05f, cx - headR * 0.1f, headCy + headR * 1.3f, cx - headR * 0.15f, headCy + headR * 1.55f)
    canvas.drawPath(trunkInner, thin)

    // Round body
    val bodyCy = s * 0.72f
    val bodyRx = s * 0.28f
    val bodyRy = s * 0.20f
    canvas.drawOval(RectF(cx - bodyRx, bodyCy - bodyRy, cx + bodyRx, bodyCy + bodyRy), paint)

    // Four sturdy legs
    for (dx in listOf(-0.16f, -0.04f, 0.06f, 0.18f)) {
        val lx = cx + s * dx
        canvas.drawRoundRect(RectF(lx - s * 0.035f, bodyCy + bodyRy - s * 0.02f, lx + s * 0.035f, bodyCy + bodyRy + s * 0.11f), s * 0.02f, s * 0.02f, thin)
    }

    // Small tail
    val tail = Path()
    tail.moveTo(cx + bodyRx * 0.95f, bodyCy)
    tail.quadTo(cx + bodyRx * 1.2f, bodyCy + s * 0.06f, cx + bodyRx * 1.05f, bodyCy + s * 0.13f)
    canvas.drawPath(tail, thin)
}

// ---------------------------------------------------------------------------------------------
// 4. Happy Bear
// ---------------------------------------------------------------------------------------------

private fun drawBear(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = kidsLinePaint(size)
    val thin = kidsThinPaint(size)
    val cx = s * 0.5f
    val headCy = s * 0.38f
    val headR = s * 0.21f

    for (side in listOf(-1f, 1f)) {
        canvas.drawCircle(cx + side * headR * 0.85f, headCy - headR * 0.8f, headR * 0.28f, paint)
    }
    canvas.drawCircle(cx, headCy, headR, paint)

    drawBigEye(canvas, paint, cx - headR * 0.32f, headCy - headR * 0.05f, headR * 0.14f)
    drawBigEye(canvas, paint, cx + headR * 0.32f, headCy - headR * 0.05f, headR * 0.14f)

    // Muzzle
    canvas.drawOval(RectF(cx - headR * 0.35f, headCy + headR * 0.15f, cx + headR * 0.35f, headCy + headR * 0.62f), thin)
    canvas.drawCircle(cx, headCy + headR * 0.28f, headR * 0.09f, paint)
    val mouth = Path()
    mouth.moveTo(cx, headCy + headR * 0.37f)
    mouth.lineTo(cx, headCy + headR * 0.48f)
    mouth.moveTo(cx - headR * 0.12f, headCy + headR * 0.55f)
    canvas.drawPath(mouth, thin)
    val smileL = Path()
    smileL.moveTo(cx, headCy + headR * 0.48f)
    smileL.quadTo(cx - headR * 0.18f, headCy + headR * 0.6f, cx - headR * 0.28f, headCy + headR * 0.46f)
    canvas.drawPath(smileL, thin)
    val smileR = Path()
    smileR.moveTo(cx, headCy + headR * 0.48f)
    smileR.quadTo(cx + headR * 0.18f, headCy + headR * 0.6f, cx + headR * 0.28f, headCy + headR * 0.46f)
    canvas.drawPath(smileR, thin)

    // Round sitting body
    val bodyTop = headCy + headR * 0.9f
    val bodyBottom = s * 0.88f
    val bodyHalfW = s * 0.24f
    val body = Path()
    body.moveTo(cx - bodyHalfW * 0.75f, bodyTop)
    body.cubicTo(cx - bodyHalfW * 1.35f, bodyTop + s * 0.08f, cx - bodyHalfW * 1.15f, bodyBottom - s * 0.05f, cx - bodyHalfW, bodyBottom)
    body.lineTo(cx + bodyHalfW, bodyBottom)
    body.cubicTo(cx + bodyHalfW * 1.15f, bodyBottom - s * 0.05f, cx + bodyHalfW * 1.35f, bodyTop + s * 0.08f, cx + bodyHalfW * 0.75f, bodyTop)
    body.close()
    canvas.drawPath(body, paint)

    // Belly patch
    canvas.drawOval(RectF(cx - bodyHalfW * 0.45f, bodyTop + s * 0.1f, cx + bodyHalfW * 0.45f, bodyBottom - s * 0.02f), thin)

    // Paws
    for (side in listOf(-1f, 1f)) {
        canvas.drawCircle(cx + side * bodyHalfW * 0.7f, bodyBottom - s * 0.02f, s * 0.045f, thin)
    }
}

// ---------------------------------------------------------------------------------------------
// 5. Friendly Robot Pal
// ---------------------------------------------------------------------------------------------

private fun drawRobot(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = kidsLinePaint(size)
    val thin = kidsThinPaint(size)
    val cx = s * 0.5f

    // Antenna
    val antennaBaseY = s * 0.14f
    canvas.drawLine(cx, antennaBaseY, cx, antennaBaseY - s * 0.06f, thin)
    canvas.drawCircle(cx, antennaBaseY - s * 0.09f, s * 0.025f, paint)

    // Head (rounded square)
    val headTop = s * 0.16f
    val headBottom = s * 0.40f
    val headHalfW = s * 0.17f
    canvas.drawRoundRect(RectF(cx - headHalfW, headTop, cx + headHalfW, headBottom), s * 0.03f, s * 0.03f, paint)

    // Screen face
    val screenInset = s * 0.03f
    canvas.drawRoundRect(
        RectF(cx - headHalfW + screenInset, headTop + screenInset, cx + headHalfW - screenInset, headBottom - screenInset),
        s * 0.02f, s * 0.02f, thin,
    )
    drawBigEye(canvas, paint, cx - headHalfW * 0.45f, (headTop + headBottom) / 2f, headHalfW * 0.18f)
    drawBigEye(canvas, paint, cx + headHalfW * 0.45f, (headTop + headBottom) / 2f, headHalfW * 0.18f)
    val smile = Path()
    smile.moveTo(cx - headHalfW * 0.35f, headBottom - s * 0.07f)
    smile.quadTo(cx, headBottom - s * 0.01f, cx + headHalfW * 0.35f, headBottom - s * 0.07f)
    canvas.drawPath(smile, thin)

    // Neck
    canvas.drawRect(RectF(cx - s * 0.025f, headBottom, cx + s * 0.025f, headBottom + s * 0.025f), thin)

    // Body (rounded rectangle) with a control panel
    val bodyTop = headBottom + s * 0.025f
    val bodyBottom = s * 0.78f
    val bodyHalfW = s * 0.22f
    canvas.drawRoundRect(RectF(cx - bodyHalfW, bodyTop, cx + bodyHalfW, bodyBottom), s * 0.04f, s * 0.04f, paint)

    val panelCy = (bodyTop + bodyBottom) / 2f
    canvas.drawRoundRect(RectF(cx - bodyHalfW * 0.55f, panelCy - s * 0.08f, cx + bodyHalfW * 0.55f, panelCy + s * 0.08f), s * 0.015f, s * 0.015f, thin)
    for (i in 0 until 3) {
        canvas.drawCircle(cx - bodyHalfW * 0.3f + i * bodyHalfW * 0.3f, panelCy, s * 0.02f, thin)
    }

    // Arms
    for (side in listOf(-1f, 1f)) {
        val arm = Path()
        arm.moveTo(cx + side * bodyHalfW, bodyTop + s * 0.06f)
        arm.lineTo(cx + side * (bodyHalfW + s * 0.09f), bodyTop + s * 0.16f)
        canvas.drawPath(arm, paint)
        canvas.drawCircle(cx + side * (bodyHalfW + s * 0.09f), bodyTop + s * 0.2f, s * 0.03f, thin)
    }

    // Legs
    for (side in listOf(-1f, 1f)) {
        canvas.drawRoundRect(
            RectF(cx + side * bodyHalfW * 0.5f - s * 0.03f, bodyBottom, cx + side * bodyHalfW * 0.5f + s * 0.03f, bodyBottom + s * 0.09f),
            s * 0.015f, s * 0.015f, thin,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 6. Sunny Little House
// ---------------------------------------------------------------------------------------------

private fun drawHouseAndSun(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = kidsLinePaint(size)
    val thin = kidsThinPaint(size)

    // Sun with a friendly face, upper corner
    val sunCx = s * 0.76f
    val sunCy = s * 0.20f
    val sunR = s * 0.09f
    for (i in 0 until 8) {
        val a = i * (Math.PI.toFloat() * 2f / 8f)
        val x1 = sunCx + kotlin.math.cos(a) * sunR * 1.25f
        val y1 = sunCy + kotlin.math.sin(a) * sunR * 1.25f
        val x2 = sunCx + kotlin.math.cos(a) * sunR * 1.7f
        val y2 = sunCy + kotlin.math.sin(a) * sunR * 1.7f
        canvas.drawLine(x1, y1, x2, y2, thin)
    }
    canvas.drawCircle(sunCx, sunCy, sunR, paint)
    drawBigEye(canvas, paint, sunCx - sunR * 0.35f, sunCy - sunR * 0.1f, sunR * 0.12f)
    drawBigEye(canvas, paint, sunCx + sunR * 0.35f, sunCy - sunR * 0.1f, sunR * 0.12f)
    val sunSmile = Path()
    sunSmile.moveTo(sunCx - sunR * 0.3f, sunCy + sunR * 0.25f)
    sunSmile.quadTo(sunCx, sunCy + sunR * 0.55f, sunCx + sunR * 0.3f, sunCy + sunR * 0.25f)
    canvas.drawPath(sunSmile, thin)

    // A puffy cloud
    val cloudCx = s * 0.22f
    val cloudCy = s * 0.18f
    canvas.drawCircle(cloudCx, cloudCy, s * 0.045f, thin)
    canvas.drawCircle(cloudCx + s * 0.05f, cloudCy - s * 0.02f, s * 0.055f, thin)
    canvas.drawCircle(cloudCx + s * 0.11f, cloudCy, s * 0.04f, thin)

    // House body
    val houseLeft = s * 0.22f
    val houseRight = s * 0.78f
    val roofY = s * 0.42f
    val eaveY = s * 0.5f
    val groundY = s * 0.86f
    canvas.drawRect(RectF(houseLeft, eaveY, houseRight, groundY), paint)

    // Triangular roof
    val roof = Path()
    roof.moveTo(houseLeft - s * 0.04f, eaveY)
    roof.lineTo((houseLeft + houseRight) / 2f, roofY)
    roof.lineTo(houseRight + s * 0.04f, eaveY)
    canvas.drawPath(roof, paint)

    // Chimney
    canvas.drawRect(RectF(houseRight - s * 0.13f, roofY - s * 0.02f, houseRight - s * 0.06f, eaveY - s * 0.02f), thin)

    // Door
    val doorCx = (houseLeft + houseRight) / 2f
    canvas.drawRoundRect(RectF(doorCx - s * 0.06f, groundY - s * 0.2f, doorCx + s * 0.06f, groundY), s * 0.02f, s * 0.02f, thin)
    canvas.drawCircle(doorCx + s * 0.035f, groundY - s * 0.1f, s * 0.008f, thin)

    // Two windows with cross panes
    for (side in listOf(-1f, 1f)) {
        val wx = doorCx + side * s * 0.18f
        val wTop = eaveY + s * 0.08f
        val wBottom = wTop + s * 0.13f
        canvas.drawRect(RectF(wx - s * 0.06f, wTop, wx + s * 0.06f, wBottom), thin)
        canvas.drawLine(wx, wTop, wx, wBottom, thin)
        canvas.drawLine(wx - s * 0.06f, (wTop + wBottom) / 2f, wx + s * 0.06f, (wTop + wBottom) / 2f, thin)
    }

    // Ground line and a little flower
    canvas.drawLine(s * 0.06f, groundY, s * 0.94f, groundY, paint)
    val flowerCx = s * 0.10f
    val flowerCy = groundY - s * 0.04f
    canvas.drawLine(flowerCx, flowerCy, flowerCx, groundY, thin)
    for (i in 0 until 5) {
        val a = i * (Math.PI.toFloat() * 2f / 5f)
        canvas.drawCircle(flowerCx + kotlin.math.cos(a) * s * 0.02f, flowerCy - s * 0.04f + kotlin.math.sin(a) * s * 0.02f, s * 0.016f, thin)
    }
}

// ---------------------------------------------------------------------------------------------
// 7. Garden Friends
// ---------------------------------------------------------------------------------------------

private fun drawGardenFriends(canvas: Canvas, size: Int) {
    val s = size.toFloat()
    val paint = kidsLinePaint(size)
    val thin = kidsThinPaint(size)

    // Ground line
    val groundY = s * 0.84f
    canvas.drawLine(s * 0.06f, groundY, s * 0.94f, groundY, thin)

    // Three tall flowers of varying height
    val flowerXs = listOf(s * 0.24f, s * 0.5f, s * 0.74f)
    val flowerHeights = listOf(s * 0.30f, s * 0.40f, s * 0.24f)
    for (i in flowerXs.indices) {
        val fx = flowerXs[i]
        val topY = groundY - flowerHeights[i]
        canvas.drawLine(fx, groundY, fx, topY, paint)
        // simple leaf on the stem
        val leaf = Path()
        leaf.moveTo(fx, topY + flowerHeights[i] * 0.4f)
        leaf.quadTo(fx + s * 0.05f, topY + flowerHeights[i] * 0.32f, fx, topY + flowerHeights[i] * 0.24f)
        canvas.drawPath(leaf, thin)
        // six-petal blossom at the top
        for (p in 0 until 6) {
            val a = p * (Math.PI.toFloat() * 2f / 6f)
            canvas.drawCircle(fx + kotlin.math.cos(a) * s * 0.028f, topY + kotlin.math.sin(a) * s * 0.028f, s * 0.022f, paint)
        }
        canvas.drawCircle(fx, topY, s * 0.018f, thin)
    }

    // A friendly ladybug on a leaf
    val bugCx = s * 0.5f
    val bugCy = groundY - flowerHeights[1] * 0.55f - s * 0.04f
    canvas.drawCircle(bugCx, bugCy, s * 0.035f, paint)
    canvas.drawLine(bugCx - s * 0.035f, bugCy, bugCx + s * 0.035f, bugCy, thin)
    canvas.drawCircle(bugCx - s * 0.015f, bugCy - s * 0.012f, s * 0.006f, thin)
    canvas.drawCircle(bugCx + s * 0.015f, bugCy + s * 0.01f, s * 0.006f, thin)

    // A butterfly fluttering above
    val flyCx = s * 0.68f
    val flyCy = s * 0.36f
    for (side in listOf(-1f, 1f)) {
        canvas.drawOval(RectF(flyCx + side * s * 0.035f - s * 0.028f, flyCy - s * 0.03f, flyCx + side * s * 0.035f + s * 0.028f, flyCy + s * 0.014f), paint)
        canvas.drawOval(RectF(flyCx + side * s * 0.03f - s * 0.02f, flyCy + s * 0.012f, flyCx + side * s * 0.03f + s * 0.02f, flyCy + s * 0.045f), thin)
    }
    canvas.drawLine(flyCx, flyCy - s * 0.025f, flyCx, flyCy + s * 0.04f, paint)

    // A small sitting rabbit on the other side, keeping it simple/friendly
    val rCx = s * 0.14f
    val rCy = groundY - s * 0.09f
    val rR = s * 0.075f
    canvas.drawCircle(rCx, rCy, rR, paint)
    for (side in listOf(-1f, 1f)) {
        val ear = Path()
        ear.moveTo(rCx + side * rR * 0.35f, rCy - rR * 0.8f)
        ear.cubicTo(rCx + side * rR * 0.5f, rCy - rR * 1.9f, rCx + side * rR * 0.15f, rCy - rR * 2.0f, rCx + side * rR * 0.1f, rCy - rR * 0.9f)
        canvas.drawPath(ear, paint)
    }
    drawBigEye(canvas, paint, rCx - rR * 0.3f, rCy - rR * 0.05f, rR * 0.14f)
    drawBigEye(canvas, paint, rCx + rR * 0.3f, rCy - rR * 0.05f, rR * 0.14f)
    canvas.drawCircle(rCx, rCy + rR * 0.25f, rR * 0.08f, thin)
}
