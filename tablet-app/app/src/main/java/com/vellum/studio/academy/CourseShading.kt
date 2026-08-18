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
 */
object CourseShading {

    private val fiveValuesOfLight = Lesson(
        id = "five-values-of-light",
        title = "The Five Values of Light",
        summary = "Learn the five zones of light and shadow that make anything you shade look solid, and the difference between core shadow and cast shadow that trips up almost every beginner.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Every object that looks solid and three-dimensional in a drawing is using the same trick, " +
                    "whether it's a sphere, an apple, or a human face. Light hits it, and the surface responds " +
                    "in five predictable ways. Learn to see those five zones and you can shade almost anything " +
                    "- it's a pattern you can learn once, not something you have to reinvent every time."
            ),
            LessonBlock.Heading("The Five Zones, In Order"),
            LessonBlock.Paragraph(
                "Picture a plain ball sitting on a table with a light shining on it from one side. Starting " +
                    "from the brightest spot and working around to the shadow, here's what you'll see:"
            ),
            LessonBlock.BulletList(
                listOf(
                    "Highlight - the small, brightest spot where light hits the surface most directly. Often close to pure white.",
                    "Light side (halftone) - the broad, medium-bright area around the highlight. This is most of what you'd call the lit side of the object.",
                    "Core shadow - the darkest band on the object itself, where the surface curves away from the light completely. Not the shadow on the table - the shadow ON the ball.",
                    "Reflected light - a thin, subtle strip of light inside the shadow side, bounced back onto the object from the ground or nearby surfaces. It's never as bright as the light side.",
                    "Cast shadow - the shadow the object throws onto the surface it's sitting on, or onto nearby objects. Usually the darkest value in the whole picture, especially close to where the object touches the ground."
                )
            ),
            LessonBlock.Heading("The Mix-Up Almost Everyone Makes"),
            LessonBlock.Paragraph(
                "If there's one thing that trips up almost every beginner, it's this: core shadow and cast " +
                    "shadow are not the same thing, even though they're both 'the shadow part.' Core shadow " +
                    "lives ON the object - it's the darkest part of the ball's own surface, roughly where the " +
                    "curve turns away from the light. Cast shadow lives OFF the object - it's the shadow shape " +
                    "the ball drops onto the table next to it. Mix them up and your shading won't make sense: " +
                    "you'll either forget to darken the object's own far side, or you'll darken the table where " +
                    "the object's shadow should be and call it done. They need each other in a finished drawing, " +
                    "but they're two separate jobs."
            ),
            LessonBlock.Diagram(
                caption = "The five value zones on a lit sphere",
                draw = { canvas, size -> drawFiveValueSphere(canvas, size) }
            ),
            LessonBlock.Tip(
                "You don't need to memorize the names. What matters is training your eye to look for these " +
                    "five value changes every time you look at a shaded object - a photo, a piece of fruit on " +
                    "your counter, your own hand under a lamp. Once you start looking for them, you'll see them everywhere."
            ),
            LessonBlock.Tip(
                "If your shaded sphere doesn't look 'right' yet, it's almost never because you can't draw. " +
                    "It's because one of the five zones is missing or too faint - usually the core shadow. " +
                    "Beginners often shade a light side and a shadow side and stop there, which reads flat. " +
                    "It's that dark core shadow band that makes something look like it's actually curving away from you."
            )
        )
    )

    private val pickingALightSource = Lesson(
        id = "picking-a-light-source",
        title = "Picking a Light Source and Sticking to It",
        summary = "Decide where your light is coming from before you start shading, and keep every shadow in the drawing pointing the same way - it's the single biggest fix for shading that looks off.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Here's something that trips up shaded drawings more than almost anything else, and most " +
                    "people can't put their finger on why it's happening: the light is coming from more than " +
                    "one direction. One shape is shaded like the light's on the left, the next shape is shaded " +
                    "like it's on the right, and the whole drawing quietly stops making sense - even though " +
                    "every individual shape might be shaded pretty well on its own."
            ),
            LessonBlock.Heading("Decide Before You Shade"),
            LessonBlock.Paragraph(
                "Before you put down a single shadow, stop and pick one light source. It can be as simple as " +
                    "'light is coming from the upper left.' That's it - that's the decision. Every highlight in " +
                    "your drawing goes on the side facing that direction. Every core shadow and cast shadow " +
                    "goes on the opposite side. Every object in the scene, no matter how small, follows the same rule."
            ),
            LessonBlock.Heading("The Arrow Trick"),
            LessonBlock.Paragraph(
                "Your eyes are really good at noticing when light direction is inconsistent, but your hand " +
                    "is not always great at remembering it stroke to stroke, especially partway through a " +
                    "drawing when you get absorbed in one object and lose track of the whole picture. So don't " +
                    "rely on memory - draw a small arrow off to the side of your sketch pointing in the " +
                    "direction the light is coming from. Glance at it before you shade each new shape. It takes " +
                    "two seconds and it will save you from the single most common shading mistake there is."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Pick a light direction first, before shading anything - upper left and upper right are the easiest to start with.",
                    "Draw a small arrow near your sketch pointing toward the light source, and leave it there the whole time you're shading.",
                    "Before shading each object, glance at the arrow and ask: which side of THIS shape is facing the light?",
                    "Keep cast shadows falling the same general direction on every object in the scene - they should all point away from the light, roughly parallel to each other.",
                    "If a drawing feels off but you can't say why, check the light direction on each shape before you check anything else."
                )
            ),
            LessonBlock.Tip(
                "You can erase the arrow before you call the piece finished - it's a working tool, not part of " +
                    "the final drawing. Some artists keep a tiny one in the corner as a habit forever, and there's no shame in that either."
            ),
            LessonBlock.Tip(
                "Don't worry about picking the 'best' light direction. There isn't one. What matters isn't " +
                    "which way you choose, it's that you choose and then stay consistent. A drawing with light " +
                    "coming from a slightly odd angle but applied consistently will always look more convincing " +
                    "than one with 'correct' lighting that changes halfway through."
            )
        )
    )

    private val shadingASphere = Lesson(
        id = "shading-a-sphere-step-by-step",
        title = "Shading a Sphere, Step by Step",
        summary = "Apply the five values from the last lesson to an actual circle, in the order that keeps beginners from getting stuck: block in flat shadow shapes first, then soften.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Now let's actually do it. You know the five zones, you've picked a light direction - here's " +
                    "the order that makes shading a sphere feel manageable instead of overwhelming, because the " +
                    "biggest reason beginners get stuck isn't lack of skill, it's trying to blend a perfect " +
                    "smooth gradient from the very first mark. Don't do that. Block in shapes first."
            ),
            LessonBlock.Heading("Step 1: Block In Flat Shapes"),
            LessonBlock.Paragraph(
                "Forget blending for now. Look at your circle and, using your light arrow as a guide, roughly " +
                    "divide it into two shapes with a soft line: the light side and the shadow side. Fill the " +
                    "shadow side in as one flat, even value - don't worry about it looking smooth or realistic " +
                    "yet. You're building a map, not a finished drawing."
            ),
            LessonBlock.Heading("Step 2: Add Core Shadow and Reflected Light as Flat Shapes Too"),
            LessonBlock.Paragraph(
                "Within that shadow shape, add two more flat shapes, still not blending anything: a darker " +
                    "band for the core shadow (roughly where the curve turns away from the light most directly " +
                    "- not right at the very edge), and a slightly lighter sliver right at the edge of the " +
                    "shadow for reflected light. Then add your cast shadow on the ground as its own flat shape, " +
                    "usually your darkest value. At this point your sphere should look like a slightly odd flat " +
                    "pattern of three or four gray shapes. That's exactly right so far."
            ),
            LessonBlock.Heading("Step 3: Now Soften"),
            LessonBlock.Paragraph(
                "Only now do you start blending - working the hard edges between your flat shapes so the " +
                    "values flow into each other instead of stopping abruptly. Use whichever blending approach " +
                    "fits your tools (more on that in a couple lessons): the goal is to soften the boundary " +
                    "lines between your value shapes without losing the shapes themselves. The core shadow band " +
                    "should still read as the darkest part even after blending."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Sketch a light circle and decide your light direction first.",
                    "Block in the shadow side as one flat, even value - resist blending.",
                    "Add the core shadow as a darker flat band, not touching the very edge of the sphere.",
                    "Add a thin, lighter reflected-light sliver right at that edge, inside the shadow.",
                    "Add the cast shadow on the ground as a flat shape, usually your darkest value.",
                    "Only after all the flat shapes are in place, soften the edges between them."
                )
            ),
            LessonBlock.Tip(
                "If you jump straight to blending, you'll end up chasing a gradient around in circles - " +
                    "literally - because you don't have a plan for where each value actually belongs. Blocking " +
                    "in flat shapes first gives you a map to follow, and if it looks wrong, you can see exactly " +
                    "which shape is the problem instead of a smeared mess you can't diagnose."
            ),
            LessonBlock.Tip(
                "It's genuinely fine if your first ten spheres look like they're made of gray patches instead " +
                    "of smooth light. That flat, blocky stage isn't a mistake on the way to real shading - it " +
                    "IS the first half of real shading. Every artist's sphere goes through that stage, every time, even after years of practice."
            )
        )
    )

    private val shadingCubesAndCylinders = Lesson(
        id = "shading-cubes-and-cylinders",
        title = "Shading Cubes and Cylinders",
        summary = "The same five-value logic from the sphere lesson still applies to cubes and cylinders, but it behaves differently depending on the form: flat hard-edged planes on a cube, a smooth curve with a sharp shadow edge on a cylinder.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "The five values you learned on the sphere don't disappear when the object isn't round - " +
                    "highlight, light side, core shadow, reflected light, and cast shadow are all still there " +
                    "on a cube or a cylinder. What changes is how they behave, because a cube and a cylinder " +
                    "are built out of different kinds of surfaces than a sphere is."
            ),
            LessonBlock.Heading("Cubes: Flat Planes, Hard Edges"),
            LessonBlock.Paragraph(
                "A cube is made of flat faces, and each flat face gets exactly one value - no gradient, no " +
                    "blending within a single face, ever. The change happens only at the edges where two faces " +
                    "meet, and that change should be a clean, hard line, not a soft transition. Typically the " +
                    "face most directly facing your light source is lightest, the face at a steep angle to it " +
                    "is a mid-value, and the face facing away is darkest. Three faces, three flat values, hard seams between them."
            ),
            LessonBlock.Heading("Cylinders: Smooth Curve, Sharp Shadow"),
            LessonBlock.Paragraph(
                "A cylinder is the opposite kind of surface for shading purposes - it's curving continuously " +
                    "around its length, so the value should flow smoothly from light to dark as you go around " +
                    "it, the same way it flows around a sphere. Where cylinders differ from spheres is the cast " +
                    "shadow: because a cylinder usually has a flat bottom edge sitting flush on a surface, its " +
                    "cast shadow tends to have a crisp, sharp edge rather than the softer, more gradual shadow edge a sphere throws."
            ),
            LessonBlock.Diagram(
                caption = "The same light logic on a cube and a cylinder",
                draw = { canvas, size -> drawCubeAndCylinder(canvas, size) }
            ),
            LessonBlock.BulletList(
                listOf(
                    "On a cube: pick one flat value per face and keep it completely even - no gradient inside a single face.",
                    "On a cube: make the seams between faces sharp, clean lines - that hard edge is what sells 'flat plane' to the eye.",
                    "On a cylinder: blend smoothly around the curved surface, the same instinct as shading a sphere.",
                    "On a cylinder: keep the cast shadow's edge crisp and defined, even though the object's own surface is soft and blended.",
                    "Before shading either one, check your light arrow from the last lesson - the same consistent light direction rule applies here too."
                )
            ),
            LessonBlock.Tip(
                "A quick way to tell if a cube reads as solid: cover up two of the three faces and look at just " +
                    "one. It should look like a flat, evenly colored shape with no gradient in it at all. If you " +
                    "can see the value shifting within a single face, soften that back to one flat tone."
            ),
            LessonBlock.Tip(
                "It's genuinely fine if your first cylinders look a little lumpy where the smooth curve meets " +
                    "the flat top and bottom - that transition is one of the trickier ones in this whole course. " +
                    "Slow down right at that seam and it'll come together faster than you'd expect."
            )
        )
    )

    private val blendingTechniques = Lesson(
        id = "blending-techniques-for-this-app",
        title = "Blending Techniques for This App",
        summary = "A practical walkthrough of using Vellum Studio's Pencil, Airbrush, low-opacity passes, and Eraser together to build smooth value transitions instead of drawing everything in one dark pass.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Knowing the theory of the five values is one thing - actually getting smooth, controlled " +
                    "value changes on a screen with a stylus is a different skill, and it depends a lot on " +
                    "which tool you reach for. Here's how to use the brushes already in Vellum Studio to get " +
                    "there, and a couple of workflow habits that make blending far less frustrating."
            ),
            LessonBlock.Heading("Pencil for Soft, Pressure-Based Transitions"),
            LessonBlock.Paragraph(
                "The Pencil brush responds to how hard you press, which makes it the best tool in the app for " +
                    "gradual, hand-controlled value changes. Start a stroke pressing lightly for a faint value, " +
                    "and gradually increase pressure as you move into darker areas like the core shadow. Layer " +
                    "a few passes rather than trying to get the exact right darkness in one stroke - it's much " +
                    "easier to build up to the right value than to guess it in one go."
            ),
            LessonBlock.Heading("Airbrush for Smooth, Even Gradients"),
            LessonBlock.Paragraph(
                "When you want a genuinely soft, smooth gradient with no visible stroke texture at all - like " +
                    "the gradual blend around a cylinder or sphere - the Airbrush is built for exactly that. It " +
                    "lays down soft, diffused color rather than a hard-edged mark, so overlapping passes blend " +
                    "into each other naturally. It's a great choice for the light-side-to-core-shadow " +
                    "transition where you don't want any visible edges."
            ),
            LessonBlock.Heading("Build Darkness in Low-Opacity Passes"),
            LessonBlock.Paragraph(
                "Whichever brush you're using, resist the urge to find the 'right' dark color and lay it down " +
                    "in one confident pass - that almost always ends up too dark, too flat, or both, and it's " +
                    "hard to walk back. Instead, drop the brush's opacity down and build up your darkest values " +
                    "gradually, pass by pass. Each light pass deepens the value a little more, and you stay in " +
                    "control the entire time instead of committing too early."
            ),
            LessonBlock.Heading("Erase the Highlight Instead of Drawing It Clean"),
            LessonBlock.Paragraph(
                "Here's a trick that surprises a lot of people: instead of carefully drawing a bright highlight " +
                    "on blank paper, shade the whole form first - light side, core shadow, all of it - and then " +
                    "use the Eraser to lift the highlight back out of the shading you already laid down. It " +
                    "tends to look softer and more integrated than a highlight drawn on top of nothing, and " +
                    "it's genuinely easier to control, because you're subtracting from a value that's already " +
                    "there instead of guessing where blank paper needs to stay blank."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Pencil - best for pressure-controlled, hand-driven transitions with a bit of texture.",
                    "Airbrush - best for smooth, soft gradients with no visible stroke marks.",
                    "Low opacity, multiple passes - build your darkest values gradually instead of committing to one dark stroke.",
                    "Eraser - pull highlights back out of shaded areas instead of drawing them in clean on blank paper.",
                    "Try switching tools mid-drawing - Pencil to block in, Airbrush to smooth the transitions, Eraser to finish the highlight."
                )
            ),
            LessonBlock.Tip(
                "If a value you laid down is too dark, don't panic and don't start over - a low-opacity pass " +
                    "with the Eraser can lighten it back down the same gradual way you built it up. Blending is " +
                    "much more forgiving when you think of it as a back-and-forth conversation with the drawing instead of a one-shot decision."
            ),
            LessonBlock.Tip(
                "There's no single correct brush for shading. Try the same simple sphere three times - once " +
                    "mostly Pencil, once mostly Airbrush, once mixing both - and see which result you like and " +
                    "which process felt more natural in your hand. That's the one to lean on."
            )
        )
    )

    private val crossHatching = Lesson(
        id = "cross-hatching-and-mark-making",
        title = "Cross-Hatching and Mark-Making as an Alternative to Blending",
        summary = "Hatching and cross-hatching build value through line density instead of smooth gradients - a real, respected drawing technique in its own right, not a fallback for people who can't blend.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "If smooth blending has been feeling frustrating - your stylus control isn't quite where you " +
                    "want it yet, or the gradients keep coming out patchy or streaky - here's some good news: " +
                    "you don't actually need smooth blending to shade convincingly. Hatching and cross-hatching " +
                    "build value out of lines instead of gradients, and it's not a workaround or a lesser " +
                    "version of 'real' shading. It's its own technique, used by illustrators, printmakers, and " +
                    "comic artists for centuries, and plenty of professionals reach for it by choice, not because they can't do anything else."
            ),
            LessonBlock.Heading("How It Works"),
            LessonBlock.Paragraph(
                "Instead of smoothly darkening an area, you fill it with a series of lines. Where the lines " +
                    "are spaced far apart, more of the paper shows through and the area reads as lighter. Where " +
                    "the lines are close together or overlapping, less light space shows through and the area " +
                    "reads darker. That's it - value comes from line density, not from how dark any single line " +
                    "is. All five value zones from earlier in this course still apply; you're just expressing them with line spacing instead of a gradient."
            ),
            LessonBlock.Heading("Hatching vs. Cross-Hatching"),
            LessonBlock.Paragraph(
                "Hatching is a set of roughly parallel lines. Cross-hatching is two or more layers of those " +
                    "lines laid over each other at different angles, which builds up darker values faster and " +
                    "gives you more range. A light side might just need a handful of spaced-out parallel lines. " +
                    "A core shadow might need three or four overlapping layers of crossed lines to get dark " +
                    "enough. You control value the same way you would with a low-opacity blended pass - just by adding more layers where you need it darker."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Widely spaced lines = lighter value. Densely packed or overlapping lines = darker value.",
                    "Follow the form with your line direction where it helps - curved lines that wrap around a cylinder or sphere can reinforce its shape, though straight lines work fine too.",
                    "Build darker areas gradually, one layer of crossed lines at a time, the same patient way you'd build up an opacity-based blend.",
                    "Keep your line spacing reasonably consistent within one value zone - it's the evenness of the spacing that reads as a clean value, not perfectly straight lines.",
                    "You can mix hatching with blended shading in the same piece - hatch the areas that give you trouble, blend the ones that don't. There's no rule against combining techniques."
                )
            ),
            LessonBlock.Tip(
                "If your hatching lines come out a little wobbly or uneven at first, that's genuinely part of " +
                    "the charm of the technique, not a flaw to eliminate - a lot of the appeal of hatched " +
                    "illustration comes from the visible hand-drawn quality of the lines. Aim for consistent spacing before you aim for perfectly straight lines."
            ),
            LessonBlock.Tip(
                "Cross-hatching can feel slow at first because you're laying down a lot of individual lines " +
                    "instead of one blended pass, but it gives you something blending doesn't: total control " +
                    "over exactly how dark each tiny area gets, one deliberate line at a time. For a lot of " +
                    "people, that control ends up feeling easier to manage than a stylus-pressure gradient, not harder."
            )
        )
    )

    val course: Course = Course(
        id = "shading",
        title = "Shading & Light",
        instructorId = Instructors.marisol.id,
        description = "A practical, step-by-step guide to using light and shadow to make anything you draw look solid and three-dimensional.",
        lessons = listOf(
            fiveValuesOfLight,
            pickingALightSource,
            shadingASphere,
            shadingCubesAndCylinders,
            blendingTechniques,
            crossHatching
        )
    )

    // ---- Diagram drawing ----

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
