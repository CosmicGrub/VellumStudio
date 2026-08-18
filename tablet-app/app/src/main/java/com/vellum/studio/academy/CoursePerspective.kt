package com.vellum.studio.academy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

private fun pLinePaint(strokeWidth: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    this.strokeWidth = strokeWidth
    this.color = color
}

private fun pFillPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color
}

private fun pLabelPaint(textSize: Float, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    this.textSize = textSize
    textAlign = Paint.Align.CENTER
}

/**
 * Course 2 of the Academy. Builds directly on Drawing Foundations — assumes the reader has already
 * done the loosening-up, basic-shapes, and volume lessons from that course.
 */
object CoursePerspective {

    private val whatPerspectiveSolves = Lesson(
        id = "what-perspective-solves",
        title = "What Perspective Actually Solves",
        summary = "Understand the problem perspective fixes, so it stops feeling like abstract math.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "You've probably drawn a road, a hallway, or a row of fence posts and had it come out " +
                    "looking flat and wrong, even though every individual shape seemed fine. That's not a " +
                    "shape problem — it's a perspective problem. Perspective is just the set of rules for how " +
                    "things appear to shrink and lean as they get farther from you. Once you see the problem " +
                    "it's solving, the 'rules' stop feeling like arbitrary math and start feeling like common " +
                    "sense you already half-know from looking at the world your whole life."
            ),
            LessonBlock.Heading("The Problem: Things Get Smaller and Lines Converge"),
            LessonBlock.Paragraph(
                "Stand in the middle of a long hallway and look down it. The walls on either side aren't " +
                    "actually getting closer together — they're parallel, the same distance apart the whole " +
                    "way down. But visually, they appear to lean inward and meet somewhere far in the " +
                    "distance. That apparent meeting point is the whole secret behind perspective. Objects " +
                    "farther away look smaller, and parallel lines receding into the distance look like " +
                    "they're converging toward a single point, even though in reality they never touch."
            ),
            LessonBlock.Heading("A Tool You Look Up, Not Memorize Under Pressure"),
            LessonBlock.Paragraph(
                "Here's some real relief: you do not need to have perspective rules memorized cold, ready to " +
                    "recite on demand. Perspective is a tool, like a formula you look up when you need it, not " +
                    "a test you have to pass from memory. Even experienced artists sketch out a horizon line " +
                    "and vanishing points as a guide before drawing something in perspective — they're not " +
                    "eyeballing it from pure memorized instinct. Treat the next few lessons as 'here's where " +
                    "to find the tool when you need it,' not 'here's something you must have memorized by " +
                    "Friday.'"
            ),
            LessonBlock.BulletList(
                listOf(
                    "A road, hallway, or railroad track that needs to feel like it goes somewhere",
                    "A room or building where you want walls and furniture to feel solid, not paper-flat",
                    "Any scene with more than one object at clearly different distances from the viewer",
                    "A box, table, or book drawn at an angle rather than straight-on"
                )
            ),
            LessonBlock.Tip(
                "If a drawing feels 'off' but you can't say why, check whether things that should look " +
                    "smaller with distance are actually the same size on the page. Our brains are extremely " +
                    "sensitive to this — even a small size mismatch reads as 'something's wrong here' before " +
                    "you consciously know what it is."
            ),
            LessonBlock.Tip(
                "You don't need perspective for everything. A straight-on portrait, a flat pattern, or a " +
                    "stylized flat illustration might not need any of this. Perspective is a tool for a " +
                    "specific problem — depth and distance — not a requirement for every drawing you'll ever " +
                    "make."
            )
        )
    )

    private val onePointPerspective = Lesson(
        id = "one-point-perspective",
        title = "One-Point Perspective",
        summary = "Use a horizon line and a single vanishing point to make a box or hallway recede convincingly.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "One-point perspective is the simplest version of the trick, and it's exactly what you use " +
                    "for a hallway, a road, or a train track heading straight away from you. Everything you " +
                    "need boils down to two marks on the page: a horizon line and one vanishing point. From " +
                    "there, construction is mostly just connecting dots."
            ),
            LessonBlock.Heading("The Horizon Line and the Vanishing Point"),
            LessonBlock.Paragraph(
                "The horizon line represents your eye level — an imaginary flat line stretching across your " +
                    "view, at the exact height your eyes are. The vanishing point (often just written 'VP') " +
                    "is a single point somewhere on that horizon line, and it represents the spot infinitely " +
                    "far away where parallel lines receding from you appear to meet. In one-point perspective, " +
                    "every line that's heading 'away from you' in the scene — the edges of a hallway, the " +
                    "sides of a road — points toward that one single VP."
            ),
            LessonBlock.Heading("Drawing a Box That Recedes to the Vanishing Point"),
            LessonBlock.Paragraph(
                "Start by drawing your horizon line and marking one vanishing point somewhere on it. Draw a " +
                    "simple square or rectangle below (or above) the horizon line — that's the front face of " +
                    "your box, the part closest to you. Now draw a light guideline from each of the four " +
                    "corners of that square back to the vanishing point. Pick a spot along those guidelines to " +
                    "mark the back face of the box, and connect those points into a smaller rectangle. Ignore " +
                    "the extra guideline past the back face, and you've got a box that convincingly recedes " +
                    "into the distance."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Draw a horizon line roughly a third of the way up your canvas, and one vanishing point on it",
                    "Draw a hallway: two vertical lines for the side walls near the bottom corners of the canvas, both aimed toward the VP",
                    "Add a rectangle partway down each guideline for doorways along the hallway",
                    "Try the same setup as a straight road instead, with a few evenly-spaced fence posts, each one shorter than the last as it nears the VP"
                )
            ),
            LessonBlock.Diagram(
                caption = "A horizon line, one vanishing point, and a box built from it",
                draw = { canvas: Canvas, size: Int ->
                    val s = size.toFloat()
                    val horizonPaint = pLinePaint(s * 0.006f, Color.rgb(120, 120, 120))
                    val stroke = pLinePaint(s * 0.008f, Color.rgb(60, 60, 60))
                    val guide = pLinePaint(s * 0.005f, Color.rgb(160, 160, 160)).apply {
                        pathEffect = DashPathEffect(floatArrayOf(s * 0.018f, s * 0.014f), 0f)
                    }
                    val fill = pFillPaint(Color.rgb(225, 225, 225))
                    val vpDot = pFillPaint(Color.rgb(200, 90, 60))
                    val label = pLabelPaint(s * 0.04f, Color.rgb(90, 90, 90))

                    val horizonY = s * 0.38f
                    val vpX = s * 0.5f
                    canvas.drawLine(0f, horizonY, s, horizonY, horizonPaint)
                    canvas.drawCircle(vpX, horizonY, s * 0.01f, vpDot)
                    canvas.drawText("VP", vpX, horizonY - s * 0.025f, label)

                    val boxLeft = s * 0.22f
                    val boxRight = s * 0.46f
                    val boxTop = s * 0.55f
                    val boxBottom = s * 0.8f
                    val front = RectF(boxLeft, boxTop, boxRight, boxBottom)

                    canvas.drawLine(boxLeft, boxTop, vpX, horizonY, guide)
                    canvas.drawLine(boxRight, boxTop, vpX, horizonY, guide)
                    canvas.drawLine(boxLeft, boxBottom, vpX, horizonY, guide)
                    canvas.drawLine(boxRight, boxBottom, vpX, horizonY, guide)

                    val t = 0.45f
                    fun lerp(a: Float, b: Float) = a + (b - a) * t
                    val backLeft = lerp(boxLeft, vpX)
                    val backRight = lerp(boxRight, vpX)
                    val backTop = lerp(boxTop, horizonY)
                    val backBottom = lerp(boxBottom, horizonY)
                    val back = RectF(backLeft, backTop, backRight, backBottom)

                    canvas.drawRect(back, fill)
                    canvas.drawRect(back, stroke)
                    canvas.drawRect(front, fill)
                    canvas.drawRect(front, stroke)
                    canvas.drawText("box receding to VP", (boxLeft + boxRight) / 2f, boxBottom + s * 0.06f, label)
                }
            ),
            LessonBlock.Tip(
                "If your receding lines look like they're going to totally different places instead of one " +
                    "shared point, draw the vanishing point first and darker than everything else, then draw " +
                    "every guideline starting from your shape and ending exactly on that dot. It's much easier " +
                    "to hit one visible target than to eyeball several lines into agreement."
            ),
            LessonBlock.Tip(
                "Keep your first guideline construction lines very light — you'll be erasing or covering " +
                    "most of them once the box shape is settled. Don't worry about neatness at this stage; " +
                    "the guidelines are scaffolding, not the finished drawing."
            )
        )
    )

    private val twoPointPerspective = Lesson(
        id = "two-point-perspective",
        title = "Two-Point Perspective",
        summary = "Add a second vanishing point to draw a box corner-first, the way most everyday objects actually appear.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Look around any room you're in and you'll notice most objects aren't lined up straight-on " +
                    "with you — you're usually seeing a corner first, with two sides receding away at angles. " +
                    "That's two-point perspective, and it's the perspective you'll actually use most often, " +
                    "because it's how furniture, buildings, and boxes usually sit relative to how we look at " +
                    "them."
            ),
            LessonBlock.Heading("Two Vanishing Points on the Same Horizon Line"),
            LessonBlock.Paragraph(
                "Two-point perspective works the same way as one-point, except now there are two vanishing " +
                    "points sitting on the horizon line instead of one, usually spaced pretty far apart — " +
                    "often even past the edges of your canvas. One set of receding lines heads toward the " +
                    "left VP, and a different set heads toward the right VP, while the only truly vertical " +
                    "lines in the whole drawing are the ones running straight up and down."
            ),
            LessonBlock.Heading("Building a Box Corner-First"),
            LessonBlock.Paragraph(
                "Start with your horizon line and two vanishing points, one on each side. Draw a single " +
                    "vertical line somewhere between them — this is the nearest corner of your box, the edge " +
                    "closest to you. From the top and bottom of that line, draw light guidelines heading to " +
                    "both vanishing points. Pick a point along each guideline to mark where the box's side " +
                    "edges will be, and draw a vertical line at each of those points. Connect the tops and " +
                    "bottoms back toward the vanishing points to finish the two visible side faces, and you've " +
                    "got a box sitting corner-first, exactly like a building or a piece of furniture would " +
                    "look from an angle."
            ),
            LessonBlock.BulletList(
                listOf(
                    "The nearest vertical edge of the object is the only line you draw without referencing a vanishing point at all",
                    "Every other 'horizontal-ish' edge on the object should point at one of your two VPs, not be drawn freehand",
                    "Widely spaced vanishing points (even off-canvas) give a more natural, less distorted look than two VPs crammed close together",
                    "This is the perspective to reach for most often — it's the one everyday objects and buildings actually use"
                )
            ),
            LessonBlock.Diagram(
                caption = "One vertical corner edge, two vanishing points, two receding faces",
                draw = { canvas: Canvas, size: Int ->
                    val s = size.toFloat()
                    val horizonPaint = pLinePaint(s * 0.006f, Color.rgb(120, 120, 120))
                    val stroke = pLinePaint(s * 0.01f, Color.rgb(50, 50, 50))
                    val guide = pLinePaint(s * 0.005f, Color.rgb(160, 160, 160)).apply {
                        pathEffect = DashPathEffect(floatArrayOf(s * 0.018f, s * 0.014f), 0f)
                    }
                    val fillLight = pFillPaint(Color.rgb(235, 235, 235))
                    val fillShadow = pFillPaint(Color.rgb(190, 190, 190))
                    val vpDot = pFillPaint(Color.rgb(200, 90, 60))
                    val label = pLabelPaint(s * 0.038f, Color.rgb(90, 90, 90))

                    val horizonY = s * 0.34f
                    val vpLeftX = s * 0.06f
                    val vpRightX = s * 0.94f
                    canvas.drawLine(0f, horizonY, s, horizonY, horizonPaint)
                    canvas.drawCircle(vpLeftX, horizonY, s * 0.01f, vpDot)
                    canvas.drawCircle(vpRightX, horizonY, s * 0.01f, vpDot)
                    canvas.drawText("VP1", vpLeftX, horizonY - s * 0.025f, label)
                    canvas.drawText("VP2", vpRightX, horizonY - s * 0.025f, label)

                    val cornerX = s * 0.5f
                    val cornerTopY = s * 0.42f
                    val cornerBottomY = s * 0.76f

                    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
                    val tFace = 0.55f
                    val leftTopX = lerp(cornerX, vpLeftX, tFace)
                    val leftTopY = lerp(cornerTopY, horizonY, tFace)
                    val leftBotX = lerp(cornerX, vpLeftX, tFace)
                    val leftBotY = lerp(cornerBottomY, horizonY, tFace)
                    val rightTopX = lerp(cornerX, vpRightX, tFace)
                    val rightTopY = lerp(cornerTopY, horizonY, tFace)
                    val rightBotX = lerp(cornerX, vpRightX, tFace)
                    val rightBotY = lerp(cornerBottomY, horizonY, tFace)

                    canvas.drawLine(cornerX, cornerTopY, vpLeftX, horizonY, guide)
                    canvas.drawLine(cornerX, cornerBottomY, vpLeftX, horizonY, guide)
                    canvas.drawLine(cornerX, cornerTopY, vpRightX, horizonY, guide)
                    canvas.drawLine(cornerX, cornerBottomY, vpRightX, horizonY, guide)

                    val leftFace = Path().apply {
                        moveTo(cornerX, cornerTopY)
                        lineTo(leftTopX, leftTopY)
                        lineTo(leftBotX, leftBotY)
                        lineTo(cornerX, cornerBottomY)
                        close()
                    }
                    canvas.drawPath(leftFace, fillShadow)
                    canvas.drawPath(leftFace, stroke)

                    val rightFace = Path().apply {
                        moveTo(cornerX, cornerTopY)
                        lineTo(rightTopX, rightTopY)
                        lineTo(rightBotX, rightBotY)
                        lineTo(cornerX, cornerBottomY)
                        close()
                    }
                    canvas.drawPath(rightFace, fillLight)
                    canvas.drawPath(rightFace, stroke)

                    canvas.drawLine(cornerX, cornerTopY, cornerX, cornerBottomY, stroke)
                    canvas.drawText(
                        "corner edge first, faces to each VP",
                        cornerX,
                        cornerBottomY + s * 0.07f,
                        label
                    )
                }
            ),
            LessonBlock.Tip(
                "When your two vanishing points feel too close together, your box will look warped and " +
                    "overly dramatic, like a wide-angle lens. If that's not the effect you're going for, try " +
                    "placing your vanishing points farther apart — even off the edge of the canvas is normal " +
                    "and often looks more natural."
            ),
            LessonBlock.Tip(
                "Shade one of the two side faces a little darker than the other, even roughly. It costs " +
                    "almost nothing and immediately makes the box look like a solid object catching light " +
                    "from one direction, instead of a flat diagram."
            )
        )
    )

    private val eyeLevelAndHorizon = Lesson(
        id = "eye-level-and-horizon",
        title = "Eye Level and the Horizon Line",
        summary = "Decide where your horizon line goes based on where your 'camera' is looking from.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Here's something that trips people up at first: the horizon line in a drawing isn't a fixed " +
                    "thing that always goes in the same spot. It always sits exactly at your eye level — " +
                    "wherever the viewer's eyes are — which means it moves depending on the vantage point you " +
                    "choose. Understanding this one idea gives you a lot of control over how dramatic or " +
                    "ordinary a drawing feels."
            ),
            LessonBlock.Heading("How Eye Level Changes What You See"),
            LessonBlock.Paragraph(
                "If you're standing and looking at a building, your eye level (and horizon line) is roughly " +
                    "at your own head height, so you see mostly the front of the building and a little bit of " +
                    "the roof or ground planes. Now imagine looking up at that same building from right at its " +
                    "base — your eye level, and the horizon line, drops way down near the bottom of the page, " +
                    "and suddenly you're seeing a lot more of the undersides of things. Look down at a table " +
                    "from above instead, and the horizon line rises up near the top of the page, revealing " +
                    "more of the tops of objects. Same subject, completely different feeling, just from " +
                    "moving the horizon line."
            ),
            LessonBlock.Heading("Worm's-Eye View vs Bird's-Eye View"),
            LessonBlock.Paragraph(
                "A worm's-eye view puts the horizon line very low on the page (or even below it), as if " +
                    "you're lying on the ground looking up — it makes subjects feel huge, towering, or " +
                    "dramatic, which is why it's a favorite for superhero art and monster reveals. A " +
                    "bird's-eye view puts the horizon line very high on the page (or above it), as if you're " +
                    "looking down from a height — it makes subjects feel small and distant, and gives a " +
                    "helpful 'map-like' overview of a scene. Most everyday drawings sit somewhere in between, " +
                    "with the horizon line roughly in the middle third of the page, matching normal standing " +
                    "eye level."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Where would the 'camera' actually be for this scene — standing, crouched, on a balcony, lying down?",
                    "Do I want this subject to feel big and imposing (horizon low) or small and distant (horizon high)?",
                    "Is the horizon line roughly at the eye height of the people or objects in my scene?",
                    "Am I staying consistent — every object in the same scene shares the same horizon line and eye level"
                )
            ),
            LessonBlock.Tip(
                "A fast way to sanity-check your horizon line placement: find two objects that should be at " +
                    "roughly the same actual eye level as each other (two people standing near each other, " +
                    "say) and confirm they're both using the same horizon line to figure out their " +
                    "proportions. If one person's eyes are near the horizon and another isn't, that's usually " +
                    "the bug."
            ),
            LessonBlock.Tip(
                "Don't stress about picking the 'perfect' eye level before you start. Sketch your horizon " +
                    "line loosely, rough in your scene, and if it's not giving you the feeling you wanted, " +
                    "move the horizon line and try again. It's just one line — moving it costs you nothing at " +
                    "the sketch stage."
            )
        )
    )

    private val perspectiveEverydayObjects = Lesson(
        id = "perspective-everyday-objects",
        title = "Perspective for Everyday Objects (Not Just Buildings)",
        summary = "Apply the exact same vanishing-point logic to a table, a book, or a cereal box.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "It's easy to think of perspective as something for buildings and city streets, and then " +
                    "completely forget to use it on a table, a book, or a box of cereal sitting in a still " +
                    "life. But the truth is every object with straight edges plays by the exact same rules, " +
                    "at whatever smaller scale it happens to be. A cereal box on a table follows the same " +
                    "vanishing points as the building down the street."
            ),
            LessonBlock.Heading("The Same Rules, Smaller Scale"),
            LessonBlock.Paragraph(
                "A table is just a box with thin legs. An open book is two rectangles hinged along one edge, " +
                    "each receding slightly differently depending on how it's angled toward you. A cereal box " +
                    "is, well, a box. None of these need special new rules — they use the exact same horizon " +
                    "line and vanishing point construction you already practiced on buildings and hallways, " +
                    "just at a much smaller, closer scale, often with the vanishing points spaced closer " +
                    "together because the object itself is close to you."
            ),
            LessonBlock.Heading("One Scene, One Set of Vanishing Points"),
            LessonBlock.Paragraph(
                "Here's the practical shortcut that makes still-life and interior drawing much easier: every " +
                    "object with straight edges in the same scene, viewed from the same spot, shares the same " +
                    "horizon line and the same vanishing points. If you've already established where your VPs " +
                    "are for a table, then a book resting on that table, a box next to it, and the edge of a " +
                    "shelf in the background should all send their receding edges toward those same points. " +
                    "You don't need to re-derive perspective for every single object — you set it up once per " +
                    "scene and reuse it."
            ),
            LessonBlock.BulletList(
                listOf(
                    "A table: draw it like a simple box with the legs as thin extensions straight down from each bottom corner",
                    "An open book: two rectangles sharing a hinge edge, each face tilted toward its own side",
                    "A cereal box: a straightforward two-point perspective box, just smaller and closer than a building",
                    "A shelf of a few stacked boxes: reuse one horizon line and one pair of vanishing points for every box on it"
                )
            ),
            LessonBlock.Tip(
                "Curved objects — a mug, a bowl, a bottle — aren't left out either. Their round parts still " +
                    "follow the same ellipse-narrowing logic from the volume lesson, but their straight parts, " +
                    "like a book resting next to them, still need to agree with the scene's vanishing points. " +
                    "Mixing curved and straight objects in one scene is common; just keep both sets of rules " +
                    "working together instead of picking one and ignoring the other."
            ),
            LessonBlock.Tip(
                "When in doubt on a busy scene, pick the one object you're most confident about, nail its " +
                    "perspective first, and then check every other object against it rather than solving them " +
                    "all independently. One solid anchor makes the rest of the scene much easier to get " +
                    "right."
            )
        )
    )

    val course: Course = Course(
        id = "perspective",
        title = "Perspective Basics",
        instructorId = Instructors.rowan.id,
        description = "Builds on Drawing Foundations. Learn why perspective works, how to use one and two " +
            "vanishing points to make boxes and buildings feel solid, how eye level changes what a drawing " +
            "shows, and how to apply the same logic to everyday objects, not just architecture.",
        lessons = listOf(
            whatPerspectiveSolves,
            onePointPerspective,
            twoPointPerspective,
            eyeLevelAndHorizon,
            perspectiveEverydayObjects
        )
    )
}
