package com.vellum.studio.academy

import android.graphics.Path

/**
 * Graffiti lettering and style: tag anatomy, the construction order a piece is actually built in
 * (sketch, outline, fill, highlight, texture), and the five graffiti-specific brushes
 * (BrushPresets.SprayCan/FatCapOutline/WildstyleChisel/Drip/Stencil) that aren't locked to this
 * course — they're just five more entries in the app's normal brush lineup, usable anywhere.
 * Taught by Kai (see Instructor.kt) — original persona.
 */
object CourseGraffiti {

    private val letterformFoundation = Lesson(
        id = "letterform-foundation",
        title = "The Letterform Underneath",
        summary = "Every tag, no matter how wild it looks, is a legible letter skeleton with style added on top — not the other way around.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Here's the thing almost every beginner gets backwards: the wild, tangled, " +
                    "almost-unreadable pieces you've seen aren't drawn by starting wild. They start " +
                    "as a completely legible letter, written out plainly, and the style — the flares, " +
                    "the connections, the exaggerated curves — gets built on top of that legible " +
                    "skeleton afterward. Skip the skeleton and you don't get wildstyle, you get " +
                    "scribbles that happen to be in spray-can colors."
            ),
            LessonBlock.Heading("Why Letters Come First, Every Time"),
            LessonBlock.Paragraph(
                "A piece that reads as confident and intentional, even at a glance, has real letter " +
                    "structure holding it together underneath the style. That's not a purity rule for " +
                    "its own sake — it's the actual mechanical reason a good piece looks controlled " +
                    "and a bad one looks chaotic. Every lesson in this course builds in that order: " +
                    "legible letters first, style decisions after, never the reverse."
            ),
            LessonBlock.Heading("The Four Layers of a Piece"),
            LessonBlock.Paragraph(
                "Nearly every finished piece, from a simple throw-up to a complex wildstyle burner, " +
                    "is built in the same four passes: a light pencil sketch to place the letters, a " +
                    "bold outline that locks in the final shape, a fill that gives it color, and " +
                    "highlights plus texture that make it pop off the wall. We'll go through each of " +
                    "those passes properly over this course, and by the end you'll have real muscle " +
                    "memory for the order."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Sketch: loose pencil, just placing where each letter goes — this is the only stage where 'messy' is fine.",
                    "Outline: a bold, confident, closed line that locks in the letter's final shape — no more changes after this.",
                    "Fill: color inside the locked outline — flat, gradient, or textured, your call.",
                    "Highlight + texture: the smaller details — a shine line, a drip, a fade — that make a flat fill read as a real piece.",
                )
            ),
            LessonBlock.Tip(
                "Before you touch a 'real' brush, write your own name in plain block letters with a " +
                    "regular Pencil brush, nothing fancy. If you can't read your own name back clearly " +
                    "at that stage, no amount of graffiti styling on top will fix it — go back and fix " +
                    "the letters first."
            ),
        ),
    )

    // Flagship demo: one bubble letter built through the real four-layer order — sketch skeleton,
    // bold outline (Fat Cap), fill (Spray Can), and a highlight accent — using the actual new
    // graffiti brushes so this plays back through the real stroke-rendering pipeline, not a canned
    // animation.
    private val bubbleLetterDemo = run {
        val skeletonPath = Path().apply {
            moveTo(0.30f, 0.70f)
            cubicTo(0.20f, 0.70f, 0.18f, 0.30f, 0.34f, 0.28f)
            cubicTo(0.50f, 0.26f, 0.55f, 0.45f, 0.42f, 0.50f)
            cubicTo(0.32f, 0.54f, 0.55f, 0.55f, 0.60f, 0.72f)
        }
        val outlinePath = Path().apply {
            // A bold bubble "S" outline, traced as one continuous stroke.
            moveTo(0.58f, 0.32f)
            cubicTo(0.50f, 0.22f, 0.26f, 0.24f, 0.22f, 0.38f)
            cubicTo(0.18f, 0.52f, 0.34f, 0.54f, 0.46f, 0.56f)
            cubicTo(0.60f, 0.58f, 0.70f, 0.64f, 0.64f, 0.76f)
            cubicTo(0.56f, 0.86f, 0.32f, 0.84f, 0.26f, 0.72f)
        }
        val fillPath = outlinePath // spray fill follows the same body, brush width does the rest
        val highlightPath = Path().apply {
            moveTo(0.30f, 0.34f)
            quadTo(0.34f, 0.28f, 0.42f, 0.30f)
        }

        val ink = 0xFF2A2A2A.toInt()
        val guide = 0xFF9A9A9A.toInt()
        val fillColor = 0xFFDD3355.toInt()
        val highlight = 0xFFFFFFFF.toInt()

        LessonDemo(
            stages = listOf(
                DemoStage(
                    caption = "Sketch first — just a loose skeleton line placing where the letter goes. Messy is fine here.",
                    strokes = listOf(DemoStroke(path = skeletonPath, brushId = "pencil", colorArgb = guide, sizeMultiplier = 0.8f)),
                ),
                DemoStage(
                    caption = "Outline locks in the final shape with a bold, confident line — the Fat Cap brush, built exactly for this.",
                    strokes = listOf(DemoStroke(path = outlinePath, brushId = "graffiti_fatcap", colorArgb = ink, sizeMultiplier = 1.3f)),
                ),
                DemoStage(
                    caption = "Fill with the Spray Can — notice the grainy, textured edge, not a flat clean gradient.",
                    strokes = listOf(DemoStroke(path = fillPath, brushId = "graffiti_spray", colorArgb = fillColor, sizeMultiplier = 2.4f)),
                ),
                DemoStage(
                    caption = "One small highlight stroke is enough to make the fill read as glossy instead of flat.",
                    strokes = listOf(DemoStroke(path = highlightPath, brushId = "graffiti_wildstyle", colorArgb = highlight, sizeMultiplier = 0.9f)),
                ),
            ),
        )
    }

    private val bubbleLetters = Lesson(
        id = "bubble-letters-throw-up",
        title = "Bubble Letters: Your First Throw-Up",
        summary = "The classic rounded, easy-to-read style — and the hands-on core of this course.",
        demo = bubbleLetterDemo,
        blocks = listOf(
            LessonBlock.Paragraph(
                "Bubble letters — big, round, friendly-looking forms — are where almost everyone " +
                    "starts, and for good reason: they're forgiving to draw, instantly readable, and " +
                    "they teach the exact four-layer construction order every more advanced style " +
                    "still uses. Watch the demo below, then build your own letter the same way."
            ),
            LessonBlock.Heading("Round Everything, On Purpose"),
            LessonBlock.Paragraph(
                "The whole visual identity of a bubble letter comes from rounding what would " +
                    "normally be a sharp corner in a regular typeface. A capital 'A' loses its pointed " +
                    "peak and gains a rounded dome; a straight vertical stroke gets a gentle outward " +
                    "curve instead of staying perfectly straight. None of this is random — every curve " +
                    "still has to trace back to a legible letter, just softened."
            ),
            LessonBlock.Heading("The Fat Cap Is Doing Real Work Here"),
            LessonBlock.Paragraph(
                "This app's Fat Cap brush is deliberately built for this exact moment: a bold, " +
                    "steady-width line that barely reacts to pressure, so your outline stays " +
                    "confident and consistent instead of wobbling thin-to-thick like a sketching " +
                    "pencil would. Once you're happy with an outline, that shape is locked — the fill " +
                    "and highlight stages that come after live inside it, not on top of changing it."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Sketch the letter's skeleton loosely first, with Pencil — this stage is disposable.",
                    "Trace a bold, rounded outline over it with Fat Cap — this is the stage that's actually final.",
                    "Fill inside the locked outline with Spray Can — let its grainy texture show, don't fight it.",
                    "Add one small highlight stroke with a light color to suggest shine — a little goes a long way.",
                )
            ),
            LessonBlock.Tip(
                "If your outline and fill don't quite line up at the edges, that's not a mistake to " +
                    "panic over — a slightly imperfect fill edge is part of the hand-painted look. " +
                    "Real spray-painted pieces are never pixel-perfect either."
            ),
            LessonBlock.Tip(
                "Try your own initial first, at a big size — bubble letters are far more forgiving " +
                    "when they have room to breathe. Shrinking a complex letterform down is a much " +
                    "harder second step, not a good place to start."
            ),
        ),
    )

    private val layerConstruction = Lesson(
        id = "layers-outline-fill-highlight-shadow",
        title = "Building in Layers: Outline, Fill, Highlight, Shadow",
        summary = "The construction order that separates a piece that reads as intentional from one that reads as a mess.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "You've already used this order once, on a single bubble letter. This lesson is " +
                    "about scaling it up to a full word or a real piece — and about the one extra " +
                    "layer that separates a good piece from a great one: the drop shadow."
            ),
            LessonBlock.Heading("Adding the Shadow Layer"),
            LessonBlock.Paragraph(
                "A drop shadow — a darker, offset echo of your outline, usually down and to one side " +
                    "— is what makes flat letters look like they're actually sitting in front of the " +
                    "wall instead of printed on it. Paint it before your fill and highlight, in a " +
                    "darker or complementary color, offset a consistent direction and distance for " +
                    "every letter in the word. Consistency in the offset direction matters more than " +
                    "the exact distance — mixed shadow directions is what makes a piece look wrong at " +
                    "a glance, even to someone who couldn't tell you why."
            ),
            LessonBlock.Heading("Keep Every Letter's Layers in the Same Order"),
            LessonBlock.Paragraph(
                "When you're working across a whole word, it's tempting to fully finish one letter — " +
                    "outline through highlight — before starting the next. Resist that. Do the outline " +
                    "pass across every letter first, then the shadow pass across every letter, then " +
                    "fill, then highlight. Working layer-by-layer across the whole word (rather than " +
                    "letter-by-letter) keeps the styling consistent, because you're making each " +
                    "decision — how bold the outline, how offset the shadow — once per layer instead " +
                    "of re-deciding it letter by letter and drifting."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Order: sketch -> outline -> shadow -> fill -> highlight, every time.",
                    "Shadow offset direction and distance should stay consistent across every letter in a word.",
                    "Work layer-by-layer across the whole piece, not letter-by-letter start to finish.",
                    "The Drip brush is a good texture accent for the shadow or fill layer, used sparingly.",
                )
            ),
            LessonBlock.Tip(
                "Toggle each finished letter's Line Art off and on (if you're layering the way this " +
                    "app supports) to check that your outline alone is still legible on its own, with " +
                    "no fill or shadow to lean on. If it isn't, the fix belongs in the outline, not in " +
                    "more decoration on top of it."
            ),
        ),
    )

    private val wildstyleLesson = Lesson(
        id = "wildstyle-breaking-rules-on-purpose",
        title = "Wildstyle: Breaking the Rules on Purpose",
        summary = "Once bubble letters and layering are second nature, connect letters, add arrows, and interlock — deliberately, not randomly.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Wildstyle looks like chaos and is actually the opposite: it's bubble-letter " +
                    "discipline pushed further, where connections, arrows, and interlocking strokes " +
                    "are added as deliberate choices on top of a still-legible skeleton. This is why " +
                    "the earlier lessons insisted on letters first — wildstyle is what that " +
                    "foundation earns you, not a shortcut around it."
            ),
            LessonBlock.Heading("Connections Between Letters"),
            LessonBlock.Paragraph(
                "The simplest wildstyle move is letting the end of one letter's outline flow " +
                    "directly into the start of the next, instead of leaving a gap. Use the " +
                    "Wildstyle Chisel brush for these connecting strokes — its strong tilt response " +
                    "gives you the thick-to-thin swing that makes a connection look like one " +
                    "confident gesture instead of two separate letters awkwardly touching."
            ),
            LessonBlock.Heading("Arrows Point Somewhere, Even When Abstract"),
            LessonBlock.Paragraph(
                "Arrows are one of wildstyle's most recognizable elements, and the beginner mistake " +
                    "is treating them as decoration you can stick anywhere. A good arrow visually " +
                    "leads the eye — into a letter, along a connection, toward the piece's focal " +
                    "point. Before adding one, ask what it's actually pointing at. If the answer is " +
                    "'nothing in particular,' it's noise, not style."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Only start interlocking/connecting letters once your plain bubble letters are consistently legible.",
                    "Use Wildstyle Chisel for connecting strokes — its tilt-driven width swing is built for this.",
                    "Every arrow should visually lead somewhere specific, not just fill empty space.",
                    "When in doubt, a piece with fewer, more confident connections beats one with many uncertain ones.",
                )
            ),
            LessonBlock.Tip(
                "Sketch your connections and arrows in a light guide color first, the same way you " +
                    "sketch letters before outlining them. Wildstyle still gets a disposable planning " +
                    "stage — it's just a more complex plan."
            ),
        ),
    )

    private val textureAndGrit = Lesson(
        id = "texture-drips-fades-stencils",
        title = "Texture and Grit: Drips, Spray Fade, and Stencils",
        summary = "Using the Drip and Stencil brushes deliberately, plus fading a Spray Can pass for depth.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "The last layer on a real piece is texture — the small imperfections and effects " +
                    "that keep a flat, clean fill from looking like a printed sticker. This app's " +
                    "Drip and Stencil brushes exist specifically for this stage, and both work best " +
                    "used sparingly on top of an already-solid piece, not as a substitute for one."
            ),
            LessonBlock.Heading("Drips Read as Intentional Only in Small Doses"),
            LessonBlock.Paragraph(
                "A drip or two trailing down from the bottom of a fill reads as an intentional, " +
                    "grungy stylistic choice. Drips trailing off of every single letter reads as " +
                    "sloppy work, not style — the same 'less is more' rule that applies to small " +
                    "details in every other course applies here too. Pick one or two spots, usually " +
                    "near the bottom of the piece, and commit to those."
            ),
            LessonBlock.Heading("Spray Fade for Depth Without Extra Colors"),
            LessonBlock.Paragraph(
                "Because the Spray Can brush builds up opacity with repeated passes, you can fade a " +
                    "fill from solid to translucent within a single color just by spraying less at " +
                    "one end of a shape — heavier near the outline, lighter toward the center, for " +
                    "instance. This gives a sense of depth and light without needing to introduce a " +
                    "second or third fill color."
            ),
            LessonBlock.Heading("Stencil for Crisp Geometric Accents"),
            LessonBlock.Paragraph(
                "Where the Spray Can and Drip brushes are deliberately organic and a little " +
                    "unpredictable, the Stencil brush is the opposite on purpose — totally uniform, " +
                    "no pressure or tilt response at all, for the crisp geometric shapes (a star, a " +
                    "clean stripe, a background pattern) that contrast nicely against the piece's " +
                    "looser hand-painted elements."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Drips: one or two, near the bottom of the piece, not on every letter.",
                    "Spray fade: vary spray density within one pass instead of reaching for a second color.",
                    "Stencil: reach for it specifically when you want a crisp, uniform accent shape.",
                    "Texture is the last layer for a reason — add it once everything underneath is already working.",
                )
            ),
            LessonBlock.Tip(
                "Finish a whole piece without any texture pass first, and sit with it for a minute " +
                    "before deciding what (if anything) it actually needs. A surprising number of " +
                    "solid pieces need less texture than the instinct to keep adding suggests."
            ),
        ),
    )

    val course: Course = Course(
        id = "graffiti-lettering-style",
        title = "Graffiti Lettering & Style",
        instructorId = Instructors.kai.id,
        description = "Tag anatomy, the real construction order a piece is built in, and the " +
            "app's dedicated graffiti brush suite — from your first bubble letter to wildstyle " +
            "connections and texture.",
        lessons = listOf(
            letterformFoundation,
            bubbleLetters,
            layerConstruction,
            wildstyleLesson,
            textureAndGrit,
        ),
    )
}
