package com.vellum.studio.academy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Color Theory" - five lessons that demystify the app's own HSV picker and Palette feature,
 * then build practical color-scheme habits (complementary/analogous, warm/cool, and planning a
 * palette upfront) on top of that foundation.
 */
object CourseColorTheory {

    private val hueSaturationValue = Lesson(
        id = "hue-saturation-value",
        title = "Hue, Saturation, and Value - What You're Actually Adjusting",
        summary = "A plain-language guide to what the app's HSV color picker's three sliders actually control, and why value matters more than the other two for a drawing to read correctly.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Open the color picker in this app and you'll see three sliders, usually labeled H, S, and V. " +
                    "If you've ever dragged them around without really knowing what each one does, you're not " +
                    "alone - most people learn this by trial and error. Here's what's actually happening under " +
                    "each slider, in plain terms, and why one of the three matters more than the other two."
            ),
            LessonBlock.Heading("Hue: Which Color Family"),
            LessonBlock.Paragraph(
                "Hue is the slider that answers 'what color is this, roughly' - red, orange, yellow, green, " +
                    "blue, purple, and everywhere between. It's the one that matches what most people casually " +
                    "mean when they say 'color.' Slide it and you travel around the color wheel from the next " +
                    "lesson, cycling through every family of color without changing how bright or how pure any of them are."
            ),
            LessonBlock.Heading("Saturation: How Pure or How Muted"),
            LessonBlock.Paragraph(
                "Saturation controls how intense or how washed-out a color looks. High saturation is vivid and " +
                    "pure - think traffic-cone orange or fire-engine red. Low saturation pulls that same hue " +
                    "toward gray, giving you a dusty, muted, more 'realistic' version of the same color family. " +
                    "Neither end is better - vivid colors grab attention, muted colors feel calmer and more " +
                    "natural, and most good pieces use a mix of both."
            ),
            LessonBlock.Heading("Value: How Light or How Dark"),
            LessonBlock.Paragraph(
                "Value controls how light or dark a color is, all the way from near-white to near-black. It's " +
                    "the slider most people underestimate, and it's also the most important one. If you could " +
                    "strip all the color out of your drawing and look at it in plain grayscale, value is the " +
                    "only thing left - and if the drawing doesn't read clearly in grayscale, it usually won't read clearly in color either, no matter how nicely you've chosen your hues."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Hue = which color family (red, blue, green, and so on).",
                    "Saturation = how pure and vivid versus how muted and grayed-out.",
                    "Value = how light versus how dark.",
                    "Value is doing most of the work that makes a drawing look three-dimensional and easy to read - it's the same value logic from the Shading & Light course, just applied through color instead of gray pencil.",
                    "A quick gut-check: if you're not sure a color choice is working, try imagining the drawing in black and white. If the shapes are still clear, the value is doing its job."
                )
            ),
            LessonBlock.Tip(
                "When you're stuck on a color decision, try adjusting value first and hue last. A slightly " +
                    "off hue with the right value will usually still read fine. The right hue with the wrong value often won't."
            ),
            LessonBlock.Tip(
                "It's worth spending five minutes just dragging each slider on its own with nothing else " +
                    "changing, so you can watch exactly what it does in isolation. That little bit of play will teach you more than a written description ever could."
            )
        )
    )

    private val colorWheel = Lesson(
        id = "color-wheel",
        title = "The Color Wheel and Why It's Useful",
        summary = "Primary, secondary, and tertiary colors laid out on a wheel, as a practical tool for picking colors that actually work together rather than as trivia to memorize.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "You've probably seen a color wheel before, maybe in a school art class, and it might have " +
                    "felt like something to memorize for a quiz rather than something useful. It's actually " +
                    "one of the most practical tools available to you as an artist - not because you need to " +
                    "know the names, but because of what its layout tells you about which colors naturally play well together."
            ),
            LessonBlock.Heading("Primary, Secondary, Tertiary"),
            LessonBlock.Paragraph(
                "The wheel starts with three primary colors - red, yellow, and blue - spaced evenly around " +
                    "it. Mix two neighboring primaries and you get the secondary colors between them: orange, " +
                    "green, and violet. Mix a primary with the secondary next to it and you get the six " +
                    "tertiary colors that fill in the rest of the wheel, like red-orange or blue-green. Twelve " +
                    "colors, evenly spaced, in a predictable order - and that order is the actual useful part."
            ),
            LessonBlock.Diagram(
                caption = "A twelve-color wheel: primary, secondary, and tertiary colors",
                draw = { canvas, size -> drawColorWheel(canvas, size) }
            ),
            LessonBlock.Heading("What the Layout Actually Tells You"),
            LessonBlock.Paragraph(
                "Once the colors are arranged in this order, their position on the wheel tells you how " +
                    "they'll behave together before you even try it. Colors sitting right next to each other " +
                    "share a lot in common and tend to blend harmoniously. Colors sitting across from each " +
                    "other share almost nothing in common and create strong contrast. That relationship - near " +
                    "versus opposite - is the whole basis for the color schemes in the next lesson, and it's a much faster way to pick colors than guessing and checking."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Primary colors: red, yellow, blue - can't be mixed from other colors.",
                    "Secondary colors: orange, green, violet - each sits between the two primaries that mix to make it.",
                    "Tertiary colors: the six in between, like yellow-orange or blue-violet - each named for the two colors that combine to make it.",
                    "Colors near each other on the wheel = naturally harmonious.",
                    "Colors opposite each other on the wheel = naturally high contrast."
                )
            ),
            LessonBlock.Tip(
                "You don't need to memorize which colors mix to make which - you'll absorb that naturally the " +
                    "more you use the wheel. What's worth remembering is just the layout logic: neighbors are harmonious, opposites are contrast."
            ),
            LessonBlock.Tip(
                "Try this next time you're picking colors for a piece: before you choose anything, glance at " +
                    "where your main color sits on the wheel and decide on purpose whether you want your other " +
                    "colors to be neighbors (calm, unified) or opposites (bold, eye-catching). Deciding on purpose beats picking at random almost every time."
            )
        )
    )

    private val complementaryAndAnalogous = Lesson(
        id = "complementary-and-analogous",
        title = "Complementary and Analogous Color Schemes",
        summary = "Complementary colors sit opposite on the wheel for vibrant contrast, analogous colors sit next to each other for calm harmony - knowing which to reach for changes the whole feel of a piece.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Now that you know how the color wheel is laid out, here are the two simplest, most useful " +
                    "ways to actually use it when picking colors for a piece. Almost every color scheme you've " +
                    "ever admired in someone else's art is built on one of these two ideas, or a combination of both."
            ),
            LessonBlock.Heading("Complementary: Opposite Colors, High Contrast"),
            LessonBlock.Paragraph(
                "Complementary colors sit directly across the wheel from each other - red and green, blue and " +
                    "orange, yellow and violet. Because they have nothing in common, placing them next to each " +
                    "other creates strong, vibrant contrast that immediately draws the eye. Think of a red " +
                    "apple with a green leaf, or a basketball court's orange floor against blue accents. Reach " +
                    "for a complementary scheme when you want something to feel bold, energetic, or eye- " +
                    "catching, or when you need one specific part of a piece to stand out clearly from everything around it."
            ),
            LessonBlock.Heading("Analogous: Neighboring Colors, Calm Harmony"),
            LessonBlock.Paragraph(
                "Analogous colors sit next to each other on the wheel - like the oranges, pinks, and reds " +
                    "you'd see in a sunset, or the blues and blue-greens of an ocean scene. Because they're " +
                    "close relatives, they blend together smoothly and naturally, without any single color " +
                    "fighting for attention. Reach for an analogous scheme when you want something to feel " +
                    "cohesive, calm, or naturalistic - it's a very hard combination to get wrong because the colors are already related to begin with."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Complementary = opposite on the wheel, high contrast, energetic - example: a red apple with a green leaf.",
                    "Analogous = neighboring on the wheel, harmonious, calm - example: an orange-pink-violet sunset.",
                    "Use complementary when you want something specific to pop or grab attention.",
                    "Use analogous when you want a piece to feel unified, calm, or naturally cohesive.",
                    "You can combine them: an analogous palette for most of a piece, with one small complementary accent to draw the eye exactly where you want it."
                )
            ),
            LessonBlock.Tip(
                "If a piece feels visually loud or chaotic in a way you didn't intend, check whether you've " +
                    "accidentally used several complementary pairs at full saturation all at once. Toning down " +
                    "the saturation on one side of the pair, or leaning analogous instead, usually calms it right down."
            ),
            LessonBlock.Tip(
                "A great low-stakes way to practice this: take any simple drawing you've already made and " +
                    "color it twice on two different layers - once with a complementary scheme, once with an " +
                    "analogous one. Seeing the same exact linework produce two completely different moods is " +
                    "one of the fastest ways to really feel how much color scheme matters."
            )
        )
    )

    private val warmAndCoolColors = Lesson(
        id = "warm-and-cool-colors",
        title = "Warm and Cool Colors",
        summary = "Warm colors feel close and energetic while cool colors feel distant and calm, and you can use that effect on purpose to push backgrounds back and pull subjects forward.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Colors don't just look different from each other - they feel different, and they can even " +
                    "change how close or far away something seems, before you've drawn a single line of actual " +
                    "perspective. That's the warm-and-cool effect, and once you know it's there, you can use it on purpose instead of by accident."
            ),
            LessonBlock.Heading("Warm Colors: Advancing and Energetic"),
            LessonBlock.Paragraph(
                "Reds, oranges, and yellows are considered warm. They tend to feel energetic, close, and " +
                    "attention-grabbing - warm colors seem to push forward and advance toward the viewer, even " +
                    "on a flat surface. That's part of why a warm-colored subject in the foreground of a piece tends to feel present and immediate."
            ),
            LessonBlock.Heading("Cool Colors: Receding and Calm"),
            LessonBlock.Paragraph(
                "Blues, greens, and violets are considered cool. They tend to feel calmer, quieter, and " +
                    "farther away - cool colors seem to recede or sink back into the distance, which is exactly " +
                    "why hazy mountains in the background of a landscape are almost always painted in cool " +
                    "blues and purples, even when you know their actual color is closer to green or gray."
            ),
            LessonBlock.Heading("Using It On Purpose"),
            LessonBlock.Paragraph(
                "This effect is genuinely useful in something as simple as a coloring page. If you want your " +
                    "background to feel like it's sitting behind your main subject, lean cooler back there - " +
                    "blues, muted greens, soft violets. Then keep your main subject in warmer tones - reds, " +
                    "oranges, warm yellows. Even with zero actual perspective drawing, that temperature " +
                    "contrast alone will make the subject feel like it's sitting in front of the background instead of pasted flat on top of it."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Warm = red, orange, yellow family - feels close, energetic, advancing.",
                    "Cool = blue, green, violet family - feels distant, calm, receding.",
                    "Push backgrounds back with cooler, more muted colors.",
                    "Pull your main subject forward with warmer colors, or simply higher saturation.",
                    "Even a single piece can mix both - a warm subject in front of a cool background is one of the most reliable combinations there is."
                )
            ),
            LessonBlock.Tip(
                "Not every color is purely warm or purely cool - a yellow-green can lean either way depending " +
                    "on what's near it, and that's normal. Don't get stuck trying to sort every color into a " +
                    "strict category. Trust your gut on whether something feels warmer or cooler in context, and adjust from there."
            ),
            LessonBlock.Tip(
                "Try a simple experiment on a coloring page you've already got: color the background in cool " +
                    "blues and the main subject in warm oranges and reds, even if that's not their realistic " +
                    "color. Notice how much the subject seems to lift off the page compared to when everything's the same temperature."
            )
        )
    )

    private val buildingAPalette = Lesson(
        id = "building-a-palette",
        title = "Building a Palette You'll Actually Use",
        summary = "A practical walkthrough of picking five to eight colors before you start coloring and saving them as a custom palette in Vellum Studio, so your color choices look intentional instead of random.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Here's a habit that makes a bigger difference than almost anything else in this course: " +
                    "deciding on your colors before you start coloring, instead of picking them one at a time " +
                    "as you go. It sounds like a small change in order, but it's the difference between a piece " +
                    "that looks planned and cohesive and one that looks like it was colored by accident, even when the individual color choices weren't bad."
            ),
            LessonBlock.Heading("Why Constrain Yourself First"),
            LessonBlock.Paragraph(
                "When you pick colors as you go, piece by piece, you're making dozens of tiny independent " +
                    "decisions with no memory of each other - and it shows, because nothing was chosen with the " +
                    "whole picture in mind. When you pick a small set of colors upfront and commit to using " +
                    "only those, every choice you make afterward is automatically related to every other " +
                    "choice, because they're all coming from the same limited set. That constraint is what " +
                    "makes a finished piece look intentional. It's not about having fewer options - it's about every option you do have already working together."
            ),
            LessonBlock.Heading("Picking Your 5-8 Colors"),
            LessonBlock.Paragraph(
                "Start with the tools from the last few lessons. Pick one or two main hues using the color " +
                    "wheel, then decide whether you want the rest of your palette to lean analogous " +
                    "(neighboring, harmonious) or to include a complementary accent (opposite, high-contrast) " +
                    "for something you want to stand out. Include a range of values - don't just pick five " +
                    "colors that are all medium-bright, or you'll have nothing to shade with. A solid starting " +
                    "palette usually has at least one color that's quite light, one that's quite dark, and a few in between."
            ),
            LessonBlock.Heading("Saving It as a Custom Palette"),
            LessonBlock.Paragraph(
                "Once you've settled on your colors, save them as a custom palette using the app's Palette " +
                    "feature, so they're sitting right there ready to tap while you work, instead of re-mixing " +
                    "the same color from scratch every time you need it. This does double duty: it saves you " +
                    "time, and it physically keeps you from wandering outside your planned colors out of " +
                    "convenience partway through the piece, which is one of the easiest ways a palette quietly falls apart."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Pick 5-8 colors before you start coloring, not as you go.",
                    "Choose your main hue(s) from the color wheel, then decide analogous or complementary for the rest.",
                    "Make sure your palette includes a real range of values - light, dark, and a few steps between.",
                    "Save the set as a custom palette in the app so it's one tap away the whole time you're working.",
                    "If you find yourself wanting a color that's not in your saved palette partway through, pause and ask whether it actually fits, or whether you're reaching for it out of habit."
                )
            ),
            LessonBlock.Tip(
                "It's completely fine to build a palette, start coloring, and realize partway through that one " +
                    "color isn't working - swap it out and update your saved palette. Planning ahead doesn't " +
                    "mean the plan is locked in stone, it just means you're making decisions on purpose instead of by default."
            ),
            LessonBlock.Tip(
                "If you're not sure where to start, steal a palette. Look at a photo, a piece of art, or even " +
                    "a few tiles from a game or a movie still that you like the color feel of, and pull five or " +
                    "six colors straight from it into a saved palette. Building your eye for color by copying " +
                    "palettes you admire is a completely normal, respected way to learn - you'll start creating your own from scratch naturally over time."
            )
        )
    )

    val course: Course = Course(
        id = "color-theory",
        title = "Color Theory",
        instructorId = Instructors.marisol.id,
        description = "A practical introduction to hue, saturation, and value, and how to combine colors so a piece feels intentional instead of random.",
        lessons = listOf(
            hueSaturationValue,
            colorWheel,
            complementaryAndAnalogous,
            warmAndCoolColors,
            buildingAPalette
        )
    )

    // ---- Diagram drawing ----

    private val wheelColors = intArrayOf(
        Color.rgb(232, 52, 43),   // 0  red (primary)
        Color.rgb(232, 103, 43),  // 1  red-orange (tertiary)
        Color.rgb(232, 150, 43),  // 2  orange (secondary)
        Color.rgb(232, 194, 43),  // 3  yellow-orange (tertiary)
        Color.rgb(240, 232, 43),  // 4  yellow (primary)
        Color.rgb(160, 232, 43),  // 5  yellow-green (tertiary)
        Color.rgb(43, 232, 75),   // 6  green (secondary)
        Color.rgb(43, 232, 174),  // 7  blue-green (tertiary)
        Color.rgb(43, 142, 232),  // 8  blue (primary)
        Color.rgb(91, 43, 232),   // 9  blue-violet (tertiary)
        Color.rgb(154, 43, 232),  // 10 violet (secondary)
        Color.rgb(232, 43, 126)   // 11 red-violet (tertiary)
    )

    private fun angleToPoint(cx: Float, cy: Float, angleDeg: Float, r: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val px = cx + r * cos(rad).toFloat()
        val py = cy + r * sin(rad).toFloat()
        return Pair(px, py)
    }

    private fun drawColorWheel(canvas: Canvas, size: Int) {
        val s = size.toFloat()
        val cx = s * 0.5f
        val cy = s * 0.5f
        val outerRadius = s * 0.33f

        val wedgeRect = RectF(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
        val wedgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 12) {
            wedgePaint.color = wheelColors[i]
            val startAngle = -105f + i * 30f
            canvas.drawArc(wedgeRect, startAngle, 30f, true, wedgePaint)
        }

        // Thin separators between wedges.
        val separatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            strokeWidth = s * 0.0025f
        }
        for (i in 0 until 12) {
            val boundaryAngle = -105f + i * 30f
            val (ex, ey) = angleToPoint(cx, cy, boundaryAngle, outerRadius)
            canvas.drawLine(cx, cy, ex, ey, separatorPaint)
        }

        // Outer edge.
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.004f
            color = Color.rgb(60, 55, 50)
        }
        canvas.drawCircle(cx, cy, outerRadius, outlinePaint)

        // Callout labels: one primary, one secondary, one tertiary example.
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 46, 42)
            textSize = s * 0.026f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 84, 78)
            strokeWidth = s * 0.0025f
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(50, 46, 42)
            style = Paint.Style.FILL
        }

        fun callout(text: String, angleDeg: Float, align: Paint.Align) {
            val (ax, ay) = angleToPoint(cx, cy, angleDeg, outerRadius * 0.85f)
            val (tx, ty) = angleToPoint(cx, cy, angleDeg, outerRadius * 1.35f)
            canvas.drawLine(ax, ay, tx, ty, linePaint)
            canvas.drawCircle(ax, ay, s * 0.006f, dotPaint)
            textPaint.textAlign = align
            canvas.drawText(text, tx, ty, textPaint)
        }

        callout("primary", -90f, Paint.Align.CENTER)
        callout("tertiary", -60f, Paint.Align.LEFT)
        callout("secondary", -30f, Paint.Align.RIGHT)
    }
}
