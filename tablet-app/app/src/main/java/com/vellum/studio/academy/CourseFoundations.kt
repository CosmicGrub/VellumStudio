package com.vellum.studio.academy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

private fun fLinePaint(strokeWidth: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    this.strokeWidth = strokeWidth
    this.color = color
}

private fun fFillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color
}

private fun fLabelPaint(textSize: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.textSize = textSize
    textAlign = Paint.Align.CENTER
}

/**
 * Course 1 of the Academy. This is the very first thing a brand-new user sees, so it assumes
 * zero prior drawing experience or vocabulary.
 */
object CourseFoundations {

    private val lineConfidence = Lesson(
        id = "line-confidence",
        title = "Loosen Up: Line Confidence",
        summary = "Warm up your hand and stop fearing the 'wrong' line.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "If you've ever put your stylus down on a blank canvas and just... frozen, you're not " +
                    "broken and you're not alone. That blank white rectangle feels like it's daring you to " +
                    "mess it up. Here's the secret almost nobody tells beginners: professional artists don't " +
                    "draw one perfect, careful line. They draw a bunch of loose, fast, 'wrong' ones first, and " +
                    "the right line is usually hiding somewhere in that mess. This lesson is about giving " +
                    "yourself permission to make those messy lines on purpose, before you ever worry about " +
                    "getting something 'right.'"
            ),
            LessonBlock.Heading("Why Loose and Fast Beats Slow and Careful"),
            LessonBlock.Paragraph(
                "When you draw one line slowly, trying to make it perfect the first time, your hand tenses " +
                    "up and the line actually gets shakier — you're fighting yourself the whole way. When you " +
                    "draw the same line fast and loose, letting your arm swing through the motion, the line " +
                    "comes out smoother, even though it feels riskier. Professional artists lean on this " +
                    "constantly: they'll sketch a shape five or six times in fast, overlapping strokes, then " +
                    "pick the one line that reads best and build on that. Nobody sees the four 'wrong' lines " +
                    "underneath. That's the actual, unglamorous process."
            ),
            LessonBlock.Heading("Draw From Your Shoulder, Not Just Your Wrist"),
            LessonBlock.Paragraph(
                "Try this right now: draw a long line using only your wrist, keeping your arm still. Notice " +
                    "how it curves a little and runs out of steam partway through — your wrist only has so " +
                    "much range. Now draw a similar line but let the motion come from your shoulder, keeping " +
                    "your wrist and elbow fairly locked, like you're wiping a big window. That second line can " +
                    "travel much farther and stays smoother, because you're using one big, stable joint " +
                    "instead of a small, wobbly one. Wrist motion is great for short, careful strokes and tiny " +
                    "details. Shoulder motion is what you want for anything long — the edge of a table, a tree " +
                    "branch, the side of a face."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Straight lines: fill a row with quick horizontal lines, side to side, keeping the same length and spacing",
                    "Circles: draw the same circle shape 10 times in a row, overlapping each attempt slightly, without stopping to fix anything",
                    "Curves: draw a big lazy 'S' shape across the screen using your shoulder, and repeat it a dozen times",
                    "Vertical lines: same as the horizontal ones, but top to bottom, aiming for consistent spacing"
                )
            ),
            LessonBlock.Tip(
                "If your straight lines keep curving in the same direction, that's not bad luck — it's " +
                    "almost always your wrist rotating slightly as it moves. Try rotating your tablet a little " +
                    "instead of forcing your hand into an awkward angle. Professionals turn the page or canvas " +
                    "constantly; you're allowed to too."
            ),
            LessonBlock.Tip(
                "Set a timer for 3 minutes and just fill a blank layer with loose lines and circles before " +
                    "you start any 'real' drawing today. Don't judge them, don't erase them, don't try to make " +
                    "them pretty. This is a warm-up, the same as stretching before a run — the goal isn't a " +
                    "good drawing, it's a loose hand."
            ),
            LessonBlock.Paragraph(
                "Nobody's first line of the day looks confident. The confidence comes from the tenth line, " +
                    "and the hundredth, not from getting it right on line one. Every artist you admire has " +
                    "thousands of ugly warm-up lines nobody ever sees."
            ),
            LessonBlock.MasterworkReference(
                caption = "The Starry Night — every stroke you see here is a loose, confident mark, repeated " +
                    "hundreds of times, not one careful perfect line. That's line confidence at a masterwork level.",
                assetPath = "masterworks/starry_night_reference.jpg",
                attribution = "Vincent van Gogh, 1889 — public domain (Museum of Modern Art)",
            ),
        )
    )

    private val seeingInShapes = Lesson(
        id = "seeing-in-shapes",
        title = "Seeing in Basic Shapes",
        summary = "Learn to spot the circle, oval, box, or cylinder hiding inside anything you want to draw.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Here's something that changes how you see the entire world once you notice it: almost " +
                    "everything complicated is just a few simple shapes wearing a costume. A human head is " +
                    "basically an egg shape. A car is a rounded box. A tree trunk is a cylinder. Once you start " +
                    "looking for the basic shape underneath the surface details, drawing stops being about " +
                    "copying a confusing tangle of lines and starts being about building up from something you " +
                    "already know how to draw."
            ),
            LessonBlock.Heading("The Four Building Blocks"),
            LessonBlock.Paragraph(
                "Nearly everything you'll ever want to draw is built from some combination of four basic " +
                    "forms: circles and spheres (anything round, like a ball or an eye), ovals (elongated " +
                    "rounds, like an egg or a face), boxes (anything with flat sides and corners, like a book " +
                    "or a phone), and cylinders (a tube shape, like a soda can or an arm). You don't need to " +
                    "identify the 'correct' shape with total precision — you just need a rough, honest guess " +
                    "to start from. A slightly-wrong box is still a much better starting point than a blank " +
                    "page."
            ),
            LessonBlock.Heading("Real Objects, Simple Shapes"),
            LessonBlock.Paragraph(
                "Let's make this concrete. An apple is basically a sphere with a small dent at the top. A " +
                    "coffee mug is a cylinder with a handle stuck on the side. A book lying on a table is a " +
                    "box — just a flat one. A lampshade is a cone or a cylinder depending on the style. Once " +
                    "you draw that simple shape lightly first, all the real details — the mug's handle, the " +
                    "apple's stem, the book's pages — get added on top of a form that's already sitting " +
                    "correctly in space."
            ),
            LessonBlock.BulletList(
                listOf(
                    "A doorknob → a small sphere on a short cylinder",
                    "A slice of pizza → a triangle (really a flattened cone)",
                    "A laptop, open → two connected boxes at an angle",
                    "A tree → an oval or cloud-like blob on top of a narrow cylinder"
                )
            ),
            LessonBlock.Diagram(
                caption = "Three everyday objects, reduced to their basic shapes",
                draw = { canvas: Canvas, size: Int ->
                    val s = size.toFloat()
                    val stroke = fLinePaint(s * 0.008f, Color.rgb(70, 70, 70))
                    val fill = fFillPaint(Color.rgb(225, 225, 225))
                    val label = fLabelPaint(s * 0.042f, Color.rgb(90, 90, 90))

                    val colW = s / 3f

                    // Apple -> circle
                    val cx1 = colW * 0.5f
                    val cy = s * 0.4f
                    val r = s * 0.15f
                    canvas.drawCircle(cx1, cy, r, fill)
                    canvas.drawCircle(cx1, cy, r, stroke)
                    canvas.drawText("apple -> circle", cx1, s * 0.68f, label)

                    // Mug -> cylinder
                    val cx2 = colW * 1.5f
                    val cylW = s * 0.2f
                    val ellipseRy = s * 0.045f
                    val topY = s * 0.28f
                    val botY = s * 0.52f
                    val topOval = RectF(cx2 - cylW / 2f, topY - ellipseRy, cx2 + cylW / 2f, topY + ellipseRy)
                    val botOval = RectF(cx2 - cylW / 2f, botY - ellipseRy, cx2 + cylW / 2f, botY + ellipseRy)
                    canvas.drawOval(botOval, fill)
                    canvas.drawLine(cx2 - cylW / 2f, topY, cx2 - cylW / 2f, botY, stroke)
                    canvas.drawLine(cx2 + cylW / 2f, topY, cx2 + cylW / 2f, botY, stroke)
                    canvas.drawOval(botOval, stroke)
                    canvas.drawOval(topOval, fill)
                    canvas.drawOval(topOval, stroke)
                    canvas.drawText("mug -> cylinder", cx2, s * 0.68f, label)

                    // Book -> box
                    val cx3 = colW * 2.5f
                    val boxW = s * 0.18f
                    val boxH = s * 0.12f
                    val boxTop = s * 0.34f
                    val left = cx3 - boxW / 2f
                    val right = cx3 + boxW / 2f
                    val depth = s * 0.05f
                    val front = RectF(left, boxTop, right, boxTop + boxH)
                    canvas.drawRect(front, fill)
                    canvas.drawRect(front, stroke)
                    val top = Path().apply {
                        moveTo(left, boxTop)
                        lineTo(left + depth, boxTop - depth)
                        lineTo(right + depth, boxTop - depth)
                        lineTo(right, boxTop)
                        close()
                    }
                    canvas.drawPath(top, fill)
                    canvas.drawPath(top, stroke)
                    canvas.drawText("book -> box", cx3, s * 0.68f, label)
                }
            ),
            LessonBlock.Tip(
                "Right now, without drawing anything, look around the room and pick three objects near you. " +
                    "Say their basic shape out loud — 'that lamp is a cone on a cylinder,' 'that mug is a " +
                    "cylinder.' This takes ten seconds and trains the exact skill this whole lesson is about, " +
                    "before you've even picked up the stylus."
            ),
            LessonBlock.Tip(
                "Before you move on, pick 3 or 4 objects around you right now and sketch just their basic " +
                    "shape, nothing else. No handles, no screens, no leaves — just the circle, box, or " +
                    "cylinder underneath. This feels almost too simple. That's the point."
            )
        )
    )

    // Mirrors the sphere/cylinder layout in buildingVolume's own Diagram above, so the flagship
    // demo below visually lines up with what the lesson just showed as a static picture.
    private val buildingVolumeDemo = run {
        val cx1 = 0.30f
        val cy1 = 0.42f
        val r = 0.17f
        val circlePath = Path().apply { addCircle(cx1, cy1, r, Path.Direction.CW) }
        val horizonPath = Path().apply {
            addOval(RectF(cx1 - r, cy1 - r * 0.32f, cx1 + r, cy1 + r * 0.32f), Path.Direction.CW)
        }

        val cx2 = 0.72f
        val cylW = 0.22f
        val ellipseRy = 0.045f
        val topY = 0.26f
        val botY = 0.56f
        val topOvalPath = Path().apply {
            addOval(RectF(cx2 - cylW / 2f, topY - ellipseRy, cx2 + cylW / 2f, topY + ellipseRy), Path.Direction.CW)
        }
        val botOvalPath = Path().apply {
            addOval(RectF(cx2 - cylW / 2f, botY - ellipseRy, cx2 + cylW / 2f, botY + ellipseRy), Path.Direction.CW)
        }
        val leftLinePath = Path().apply { moveTo(cx2 - cylW / 2f, topY); lineTo(cx2 - cylW / 2f, botY) }
        val rightLinePath = Path().apply { moveTo(cx2 + cylW / 2f, topY); lineTo(cx2 + cylW / 2f, botY) }

        val ink = 0xFF3A3A3A.toInt()
        val guide = 0xFF9A9A9A.toInt()

        LessonDemo(
            stages = listOf(
                DemoStage(
                    caption = "Start with a plain circle.",
                    strokes = listOf(DemoStroke(path = circlePath, brushId = "fineliner", colorArgb = ink)),
                ),
                DemoStage(
                    caption = "Add one curved line across it, like a belt — that alone reads as a sphere.",
                    strokes = listOf(DemoStroke(path = horizonPath, brushId = "pencil", colorArgb = guide, sizeMultiplier = 0.8f)),
                ),
                DemoStage(
                    caption = "Now a cylinder: the top ellipse, then a matching one below it.",
                    strokes = listOf(
                        DemoStroke(path = topOvalPath, brushId = "fineliner", colorArgb = ink),
                        DemoStroke(path = botOvalPath, brushId = "fineliner", colorArgb = ink),
                    ),
                ),
                DemoStage(
                    caption = "Connect the left edges, then the right edges, with two straight lines.",
                    strokes = listOf(
                        DemoStroke(path = leftLinePath, brushId = "fineliner", colorArgb = ink),
                        DemoStroke(path = rightLinePath, brushId = "fineliner", colorArgb = ink),
                    ),
                ),
            ),
        )
    }

    private val buildingVolume = Lesson(
        id = "building-volume",
        title = "Building Volume: From Flat Shapes to Solid Forms",
        summary = "Learn the handful of tricks that make a flat circle read as a solid ball.",
        demo = buildingVolumeDemo,
        blocks = listOf(
            LessonBlock.Paragraph(
                "There's a big difference between a circle and a ball, even though they start as the exact " +
                    "same line. A circle just sits flat on the page, like a coin viewed straight-on. A ball " +
                    "has weight — it has a 'front' and a 'back' curving away from you, and it looks like you " +
                    "could reach out and pick it up. The difference isn't the outline — it's a few small " +
                    "additions that trick the eye into seeing depth on a flat screen. This lesson covers those " +
                    "tricks."
            ),
            LessonBlock.Heading("Turning a Circle Into a Sphere"),
            LessonBlock.Paragraph(
                "Draw a circle. Now, instead of leaving it as a flat outline, add one curved line running " +
                    "across it like a belt — think of it as the equator wrapping around a globe, tilted at " +
                    "whatever angle you want the sphere to be seen from. That single curved line is often " +
                    "enough for your brain to read the shape as round instead of flat. Add a bit of shading on " +
                    "the side away from your imagined light source, and the sphere effect gets even stronger. " +
                    "You don't need to master shading yet — just knowing that one curved line changes " +
                    "everything is the win for today."
            ),
            LessonBlock.Heading("Building a Cylinder From Two Ellipses and Two Lines"),
            LessonBlock.Paragraph(
                "A cylinder — think of a can, a cup, or an arm — breaks down into exactly four pieces: an " +
                    "ellipse on top, a matching ellipse on the bottom, and two straight lines connecting their " +
                    "edges. Draw the top ellipse first, then the bottom one directly below it (same width, " +
                    "same tilt), then connect the leftmost points and the rightmost points with two straight " +
                    "vertical lines. That's the whole construction. Once you're comfortable with it, you can " +
                    "draw a soda can, a candle, a leg, or a tree trunk using this exact same process."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Draw the top ellipse — flatter means you're viewing it more from the side",
                    "Draw the bottom ellipse the same width and tilt, positioned straight down from the top one",
                    "Connect the left edges of both ellipses with one straight line",
                    "Connect the right edges of both ellipses with one straight line"
                )
            ),
            LessonBlock.Diagram(
                caption = "A flat circle becomes a sphere; two ellipses plus two lines become a cylinder",
                draw = { canvas: Canvas, size: Int ->
                    val s = size.toFloat()
                    val stroke = fLinePaint(s * 0.008f, Color.rgb(70, 70, 70))
                    val guideStroke = fLinePaint(s * 0.006f, Color.rgb(140, 140, 140)).apply {
                        pathEffect = DashPathEffect(floatArrayOf(s * 0.02f, s * 0.015f), 0f)
                    }
                    val fill = fFillPaint(Color.rgb(225, 225, 225))
                    val label = fLabelPaint(s * 0.04f, Color.rgb(90, 90, 90))

                    // Left: circle -> sphere
                    val cx1 = s * 0.27f
                    val cy1 = s * 0.42f
                    val r = s * 0.17f
                    canvas.drawCircle(cx1, cy1, r, fill)
                    canvas.drawCircle(cx1, cy1, r, stroke)
                    val horizon = RectF(cx1 - r, cy1 - r * 0.32f, cx1 + r, cy1 + r * 0.32f)
                    canvas.drawOval(horizon, guideStroke)
                    canvas.drawText("circle -> sphere", cx1, cy1 + r + s * 0.08f, label)

                    // Right: two ellipses + two lines -> cylinder
                    val cx2 = s * 0.73f
                    val cylW = s * 0.22f
                    val ellipseRy = s * 0.045f
                    val topY = s * 0.26f
                    val botY = s * 0.56f
                    val topOval = RectF(cx2 - cylW / 2f, topY - ellipseRy, cx2 + cylW / 2f, topY + ellipseRy)
                    val botOval = RectF(cx2 - cylW / 2f, botY - ellipseRy, cx2 + cylW / 2f, botY + ellipseRy)
                    canvas.drawOval(botOval, fill)
                    canvas.drawLine(cx2 - cylW / 2f, topY, cx2 - cylW / 2f, botY, stroke)
                    canvas.drawLine(cx2 + cylW / 2f, topY, cx2 + cylW / 2f, botY, stroke)
                    canvas.drawOval(botOval, stroke)
                    canvas.drawOval(topOval, fill)
                    canvas.drawOval(topOval, stroke)
                    canvas.drawText("2 ellipses + 2 lines -> cylinder", cx2, botY + s * 0.14f, label)
                }
            ),
            LessonBlock.Tip(
                "If your ellipses keep coming out as pointy lemon shapes instead of smooth ovals, slow down " +
                    "right at the two 'tips' of the curve — most people rush through those points because " +
                    "they feel like a finish line. Ease through them instead of stopping there, and the shape " +
                    "rounds out."
            ),
            LessonBlock.Tip(
                "Here's a free depth trick with zero shading required: when one shape overlaps and covers " +
                    "part of another, your brain automatically reads the covering shape as closer to you. " +
                    "Draw two circles that overlap slightly and it instantly reads as two balls at different " +
                    "distances. Try adding one overlap to your next sketch and see how much more " +
                    "three-dimensional it feels."
            )
        )
    )

    private val proportionsWithoutRuler = Lesson(
        id = "proportions-without-ruler",
        title = "Proportions Without a Ruler",
        summary = "Use one shape as your measuring stick so nothing on the page comes out lopsided.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Ever draw the head of a person perfectly, then realize halfway down that there's no room " +
                    "left for the rest of the body? That's not a talent problem — it's a measuring problem, " +
                    "and it's probably the single most common mistake beginners make. The good news is you " +
                    "don't need a ruler or any special tool to fix it. You just need to pick one shape and use " +
                    "it as your 'unit' to measure everything else against."
            ),
            LessonBlock.Heading("The Pen-Length Trick (and Its Tablet Equivalent)"),
            LessonBlock.Paragraph(
                "Traditional artists hold a pencil out at arm's length, close one eye, and use the visible " +
                    "length of the pencil against their subject to compare sizes — 'this vase is about two " +
                    "pencil-lengths tall.' On a tablet you can do the same thing digitally: pick one shape " +
                    "you've already drawn, like the width of a head or the size of the nearest object, and " +
                    "treat it as your unit. Then ask, 'how many of these fit into the total height I'm " +
                    "drawing?' If a body is 'about seven heads tall,' you now have a simple, repeatable way to " +
                    "check your proportions as you go, instead of guessing and hoping."
            ),
            LessonBlock.Heading("The Classic Mistake: Too Big, Too Soon"),
            LessonBlock.Paragraph(
                "The single most common proportion mistake is drawing the first element — a head, the front " +
                    "edge of a building, the first flower in a bouquet — too large, because it's the only " +
                    "thing on the page and there's nothing to compare it to yet. By the time you get to the " +
                    "rest of the drawing, you've run out of space and everything else gets cramped or cut " +
                    "off. The fix is almost embarrassingly simple: before you commit to any detail, lightly " +
                    "sketch the overall size and boundary of the whole thing first, even if that outline is " +
                    "just a loose box or oval."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Lightly sketch a loose box or oval marking the outer edges of your whole subject, before drawing any detail",
                    "Pick one clear shape inside your subject to use as your measuring unit",
                    "Count roughly how many units tall and wide the whole subject is, and sanity-check that against your loose outline",
                    "Only once the big shapes and proportions feel right, start adding detail — details are the very last step, not the first"
                )
            ),
            LessonBlock.Tip(
                "If you keep running out of room on the right or bottom of your canvas, you're probably " +
                    "starting too close to an edge. Leave a visible margin on all sides before you draw your " +
                    "first line — it costs you nothing and gives you room to be wrong."
            ),
            LessonBlock.Tip(
                "Comparative measurement gets easier with practice, but even experienced artists still " +
                    "eyeball wrong sometimes — that's normal, not a sign you're doing it incorrectly. The " +
                    "habit that actually matters is checking and adjusting your loose guideline shapes before " +
                    "adding detail, not getting the guideline perfect on the first try."
            ),
            LessonBlock.Paragraph(
                "None of this needs to be precise. A loose guideline box that's roughly the right " +
                    "proportions will save a drawing far more often than no guideline at all. You're not " +
                    "measuring for a blueprint — you're just giving yourself a rough map before you commit."
            )
        )
    )

    private val gestureDrawing = Lesson(
        id = "gesture-drawing",
        title = "Gesture Drawing: Capturing the Feel, Not the Details",
        summary = "Quick, loose sketches that capture motion and energy before any detail exists.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Everything you've practiced so far has been about slowing down and looking carefully. " +
                    "Gesture drawing flips that completely: you're going to draw fast — 30 to 60 seconds per " +
                    "sketch — and the goal isn't accuracy at all, it's capturing the feel of a pose. No " +
                    "outlines, no details, no erasing. Just the energy of the shape moving across the page. " +
                    "This might be the most freeing exercise in this whole course, precisely because 'good' " +
                    "and 'bad' don't really apply here."
            ),
            LessonBlock.Heading("What Gesture Drawing Actually Captures"),
            LessonBlock.Paragraph(
                "A gesture drawing isn't a shrunk-down version of a finished drawing — it's a completely " +
                    "different kind of mark. Instead of tracing the outline of a pose, you're chasing the line " +
                    "of action: the single curve that best describes how the body (or object) is moving " +
                    "through space. Think of a person leaning to catch a ball — the gesture is that big " +
                    "diagonal lean, not the fingers or the shoelaces. You can always add detail later. What " +
                    "you can't add later is energy that wasn't there in the first 30 seconds."
            ),
            LessonBlock.Heading("Why Professionals Warm Up This Way"),
            LessonBlock.Paragraph(
                "Working artists use gesture drawing as a daily warm-up for a simple reason: it forces you to " +
                    "make quick decisions instead of getting stuck. When you only have 30 seconds, there's no " +
                    "time to second-guess a line or erase it — you just commit and move to the next one. That " +
                    "habit of committing without overthinking carries over into every other kind of drawing, " +
                    "including the slow, careful kind. It also keeps your hand loose, the same way lesson " +
                    "one's warm-up lines do, but applied to actual subjects instead of random scribbles."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Pick one simple pose — a photo of someone walking, reaching, or sitting works great, or even just imagine one",
                    "Set a timer for 45 seconds",
                    "Sketch just the line of action and the biggest shapes — no fingers, no facial features, no clothing folds",
                    "When the timer goes off, stop, even mid-line — that's part of the exercise",
                    "Repeat the same pose 5 times in a row, each in under a minute, and compare the 5 sketches side by side"
                )
            ),
            LessonBlock.Tip(
                "When you compare your 5 quick sketches, don't look for which one is 'most accurate' — look " +
                    "for which one has the most energy or feels most alive. That's usually not the slowest, " +
                    "most careful one. It's often the third or fourth, once your hand has loosened up and " +
                    "stopped worrying."
            ),
            LessonBlock.Tip(
                "If your gesture sketches keep turning into detailed drawings even though you set a short " +
                    "timer, try covering the timer or turning off the display while you draw. A lot of the " +
                    "'sneaking in detail' habit is really just anxiety about the time running out, and " +
                    "removing the visible countdown often loosens people up more than the timer itself."
            ),
            LessonBlock.Paragraph(
                "Gesture drawing feels uncomfortable at first because it asks you to stop chasing 'correct' " +
                    "and start chasing 'alive.' That trade is worth it. A wobbly, energetic gesture sketch " +
                    "usually says more about a pose than a slow, accurate one ever could."
            )
        )
    )

    val course: Course = Course(
        id = "foundations",
        title = "Drawing Foundations",
        instructorId = Instructors.rowan.id,
        description = "The absolute starting point — no experience assumed. Loosen up your hand, learn to " +
            "see everyday objects as simple shapes, give those shapes weight and volume, keep your " +
            "proportions in check without a ruler, and finish with the loose, fast sketching habit every " +
            "artist warms up with.",
        lessons = listOf(
            lineConfidence,
            seeingInShapes,
            buildingVolume,
            proportionsWithoutRuler,
            gestureDrawing
        )
    )
}
