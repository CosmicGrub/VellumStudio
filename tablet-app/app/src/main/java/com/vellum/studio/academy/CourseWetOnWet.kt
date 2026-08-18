package com.vellum.studio.academy

import android.graphics.Path

/**
 * Wet-on-wet ("alla prima") landscape painting: build a whole simple landscape in one sitting by
 * blending colors while they're still wet on the canvas, instead of waiting for layers to dry.
 * Taught by Dune (see Instructor.kt) — original persona, original demo content. The *technique*
 * this course teaches is real and long predates any one teacher of it; nothing here is modeled on
 * or attributed to any real person's specific paintings, name, or likeness.
 */
object CourseWetOnWet {

    private val introduceWetOnWet = Lesson(
        id = "meet-wet-on-wet",
        title = "Meet Wet-on-Wet",
        summary = "The core idea behind painting a whole landscape in one sitting: blend while it's still wet, don't wait for layers to dry.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Most painting technique is about patience between layers — lay down color, let it dry, " +
                    "add the next thing on top. Wet-on-wet flips that around entirely. You load your brush " +
                    "and pull color directly into color that's still sitting there, wet, and let them meet " +
                    "and blend right on the canvas. Nothing needs to dry first. That one change is what " +
                    "makes it possible to start and finish a whole landscape in a single sitting."
            ),
            LessonBlock.Heading("Why Blend Instead of Layer"),
            LessonBlock.Paragraph(
                "When two wet colors meet, they don't just sit side by side — they genuinely melt into " +
                    "each other at the seam, the way real sky fades into a real horizon instead of switching " +
                    "colors abruptly. That soft transition is the whole appeal. You're not painting a hard " +
                    "edge and softening it after; you're placing colors so the soft edge happens naturally, " +
                    "in the moment, because they're both still wet."
            ),
            LessonBlock.Heading("There Are No Mistakes Here, Just Decisions"),
            LessonBlock.Paragraph(
                "This matters more than it sounds like it should: because everything stays workable while " +
                    "it's wet, almost nothing you do is final. Put a mountain in the wrong place? Paint over " +
                    "it — the sky color is still right there, still wet, ready to take the correction. A " +
                    "shape you don't like isn't a mistake to feel bad about, it's just a decision you get to " +
                    "make again, immediately, with no cleanup required. Slow down and let yourself actually " +
                    "believe that while you work through this course — it changes how loose and confident " +
                    "your hand can afford to be."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Wet-on-wet = blending directly into still-wet color, no drying time between passes.",
                    "Soft transitions (sky into horizon, water into shore) come from the technique itself, not from a blur tool.",
                    "Nothing is precious or final while it's wet — painting over an area is the normal way to fix it, not a failure.",
                    "The whole point is working big and loose first, then getting more specific only at the very end.",
                )
            ),
            LessonBlock.Tip(
                "In Vellum Studio, the Watercolor brush is the closest match for this feel today — it's the " +
                    "one brush that both stays soft-edged and genuinely mixes with whatever it overlaps, " +
                    "which is exactly the wet-on-wet behavior this whole course leans on."
            ),
        )
    )

    private val limitedPalette = Lesson(
        id = "a-palette-you-actually-need",
        title = "A Palette You Actually Need",
        summary = "Why fewer colors, chosen deliberately, make a landscape easier to paint and easier to look at.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "New painters almost always reach for more colors than they need. It feels safer — more " +
                    "options, more chances to get the 'right' color. In practice it does the opposite: too " +
                    "many colors fight each other, and a landscape ends up looking busy instead of calm. A " +
                    "small, deliberate palette does more work than a big one, almost every time."
            ),
            LessonBlock.Heading("Start With Five Colors, Not Fifteen"),
            LessonBlock.Paragraph(
                "For a simple sky-mountain-water landscape, you genuinely don't need more than: a sky blue, " +
                    "a warm white, a dark neutral (for shadow shapes and mountains), a muted green, and one " +
                    "accent color you actually like. Every other color you need — a mountain's shadowed side, " +
                    "a warmer patch of sky near the horizon — comes from mixing those five while they're wet, " +
                    "not from opening the picker and finding a sixth."
            ),
            LessonBlock.Heading("Let Mixing Happen On the Canvas, Not In the Picker"),
            LessonBlock.Paragraph(
                "This is the same instinct as picking a limited palette in the first place: pulling a little " +
                    "warm white into wet blue, right on the canvas, gives you a softer sky color that still " +
                    "visibly belongs to the blue it came from. Pre-mixing that exact color in the color picker " +
                    "and painting it as a flat, separate shape loses that connection — it reads as a sticker, " +
                    "not a natural gradient. Whenever you can mix in place instead of in the picker, do that."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Sky blue, warm white, a dark neutral, a muted green, one accent color — that's a complete starter palette.",
                    "Every 'in-between' color should come from blending two palette colors wet, not from picking a sixth color.",
                    "A small palette naturally keeps a landscape feeling unified, because every color in it is related to every other color.",
                    "If a piece feels chaotic, the fix is often removing a color, not adding one.",
                )
            ),
            LessonBlock.Tip(
                "Before starting today's landscape, set up a small custom palette in the color picker with " +
                    "just your five chosen colors. Having a limited, physical set of swatches to choose from " +
                    "— instead of the whole color wheel — makes the decision-making genuinely faster."
            ),
        )
    )

    // Flagship demo: a simple sky/mountain/water landscape built in a handful of broad, confident
    // strokes, mirroring the "big shapes first" approach the sky-mountain-water lesson teaches.
    // watercolor is the brush of choice here specifically because it's this app's one
    // pigment-mixing, soft-edged brush — see the tip on introduceWetOnWet.
    private val landscapeDemo = run {
        val sky = 0xFF7FA8D9.toInt()
        val skyWarm = 0xFFC9B896.toInt()
        val mountainDark = 0xFF4B5A63.toInt()
        val water = 0xFF5F87AE.toInt()

        val skyPath = Path().apply { moveTo(0.06f, 0.16f); lineTo(0.94f, 0.16f) }
        val horizonWarmPath = Path().apply { moveTo(0.10f, 0.44f); lineTo(0.90f, 0.44f) }

        val mountainPath = Path().apply {
            moveTo(0.10f, 0.50f)
            lineTo(0.32f, 0.24f)
            lineTo(0.46f, 0.38f)
            lineTo(0.62f, 0.20f)
            lineTo(0.90f, 0.50f)
        }
        val mountainFillPath = Path().apply {
            // A back-and-forth zigzag under the ridge line, so a wide brush stroked along it reads
            // as a filled silhouette rather than a single thin outline.
            moveTo(0.14f, 0.48f)
            lineTo(0.30f, 0.30f)
            lineTo(0.40f, 0.44f)
            lineTo(0.50f, 0.28f)
            lineTo(0.60f, 0.42f)
            lineTo(0.72f, 0.26f)
            lineTo(0.86f, 0.48f)
        }

        val waterPath = Path().apply { moveTo(0.08f, 0.58f); lineTo(0.92f, 0.58f) }
        val reflectionPath = Path().apply {
            // A softened, compressed echo of the mountain ridge, upside down, right under the horizon.
            moveTo(0.14f, 0.62f)
            lineTo(0.30f, 0.70f)
            lineTo(0.40f, 0.64f)
            lineTo(0.50f, 0.72f)
            lineTo(0.60f, 0.65f)
            lineTo(0.72f, 0.71f)
            lineTo(0.86f, 0.62f)
        }

        LessonDemo(
            stages = listOf(
                DemoStage(
                    caption = "Big shapes first. One broad, confident stroke of sky blue across the whole top — don't be careful, be loose.",
                    strokes = listOf(DemoStroke(path = skyPath, brushId = "watercolor", colorArgb = sky, sizeMultiplier = 7f)),
                ),
                DemoStage(
                    caption = "Pull a warmer tone in near the horizon while the sky's still wet — that's the blend doing the work, not a hard edge.",
                    strokes = listOf(DemoStroke(path = horizonWarmPath, brushId = "watercolor", colorArgb = skyWarm, sizeMultiplier = 5f)),
                ),
                DemoStage(
                    caption = "The mountain is just a ridge line — a couple of confident peaks, nothing fussy.",
                    strokes = listOf(DemoStroke(path = mountainPath, brushId = "fineliner", colorArgb = mountainDark, sizeMultiplier = 1.2f)),
                ),
                DemoStage(
                    caption = "Fill the whole shape solid. If it goes slightly outside the line, that's not a mistake — the sky is still wet and forgiving.",
                    strokes = listOf(DemoStroke(path = mountainFillPath, brushId = "watercolor", colorArgb = mountainDark, sizeMultiplier = 4.5f)),
                ),
                DemoStage(
                    caption = "Water is a mirror with less confidence: same blue, one broad stroke.",
                    strokes = listOf(DemoStroke(path = waterPath, brushId = "watercolor", colorArgb = water, sizeMultiplier = 6f)),
                ),
                DemoStage(
                    caption = "Echo the mountain's shape upside down and softened — that's the reflection. It doesn't need to match exactly.",
                    strokes = listOf(DemoStroke(path = reflectionPath, brushId = "watercolor", colorArgb = mountainDark, sizeMultiplier = 2.5f)),
                ),
            ),
        )
    }

    private val skyMountainWater = Lesson(
        id = "sky-mountain-water",
        title = "Big Shapes First: Sky, Mountain, Water",
        summary = "The whole landscape in three big, confident passes — the hands-on core of this course.",
        demo = landscapeDemo,
        blocks = listOf(
            LessonBlock.Paragraph(
                "This is the lesson to actually paint along with, not just read. Everything else in this " +
                    "course is refinement — this is the whole landscape, built in three big decisions: sky, " +
                    "mountain, water. Watch the demo below once all the way through, then step through it a " +
                    "second time on your own canvas, matching each stage before moving to the next."
            ),
            LessonBlock.Heading("Order Matters: Back to Front, Big to Small"),
            LessonBlock.Paragraph(
                "Sky first, always — it's the backdrop everything else sits in front of, and painting it " +
                    "first means the mountain and water can blend into its still-wet edges instead of the " +
                    "other way around. Mountain second, because it's a mid-sized shape that needs the sky " +
                    "settled behind it but doesn't need any of the small details yet. Water last, because " +
                    "it's mostly just a color and a soft reflection of what you already painted above it."
            ),
            LessonBlock.Heading("The Reflection Is Not a Copy"),
            LessonBlock.Paragraph(
                "A beginner's instinct is to paint the water's reflection as a precise, mirrored duplicate " +
                    "of the mountain. Real reflections are softer, simpler, and a little compressed — water " +
                    "movement blurs detail that solid ground holds onto. Paint the reflection looser and " +
                    "quicker than the mountain itself, with fewer strokes, and it will read as correct even " +
                    "though it isn't a precise copy. Looser is more accurate here, not less."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Sky, then mountain, then water — always in that order, back to front.",
                    "Each new shape should touch the still-wet edge of what's behind it, so they blend a little at the seam.",
                    "A mountain silhouette is one ridge line plus a solid fill — it doesn't need internal detail yet.",
                    "The reflection should take you a third of the time the mountain did — loose and quick, not a careful copy.",
                )
            ),
            LessonBlock.Tip(
                "If you paint the mountain and don't like its shape, don't undo and start the sky over. " +
                    "Just paint the correction directly into the still-wet mountain color — that's the entire " +
                    "point of working wet-on-wet, and it's faster than starting over."
            ),
            LessonBlock.Tip(
                "Zoom out and look at the whole canvas after each of the three big passes, not just while " +
                    "you're up close painting. Landscapes that look a little rough up close often look " +
                    "completely convincing from a normal viewing distance — that's expected, not a warning sign."
            ),
        )
    )

    private val happyLittleDetails = Lesson(
        id = "happy-little-details",
        title = "Small Details, Added Last and Sparingly",
        summary = "A few confident marks — a tree line, a rock, a bird — go a long way once the big shapes are settled.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Once your sky, mountain, and water are down, the temptation is to keep adding — more trees, " +
                    "more texture, more everything. Resist most of that urge. A landscape built on three " +
                    "strong big shapes usually needs only a handful of small marks to feel complete, and " +
                    "over-adding detail is one of the fastest ways to make a confident piece look fussy."
            ),
            LessonBlock.Heading("Trees Are Suggestions, Not Portraits"),
            LessonBlock.Paragraph(
                "A tree line along the base of a mountain doesn't need individually painted leaves — a few " +
                    "small, varied dabs of dark green in a rough cluster reads as foliage from any normal " +
                    "viewing distance. Let your brush's texture do the work instead of trying to paint every " +
                    "leaf by hand. If it looks like a convincing suggestion of a tree up close, it will look " +
                    "like an actual tree once you zoom back out."
            ),
            LessonBlock.Heading("One Small Detail Earns Its Place; Three Compete"),
            LessonBlock.Paragraph(
                "A single small accent — a lone bird, a rock breaking the water's surface, a warm-lit window " +
                    "in a distant cabin — draws the eye and gives a landscape a focal point. Add two or three " +
                    "more small details competing for that same attention, and none of them read clearly " +
                    "anymore. Pick one small thing to be the detail that matters, and leave the rest of the " +
                    "piece as the big, calm shapes you already built."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Add small details last, only after the big sky/mountain/water shapes are fully settled.",
                    "A cluster of a few varied dabs reads as foliage — you don't need to paint individual leaves.",
                    "One small focal detail is usually enough; two or three competing details weaken all of them.",
                    "If you're unsure whether to add another detail, don't — a calmer piece is rarely the wrong call.",
                )
            ),
            LessonBlock.Tip(
                "Step away from the canvas — literally lock the tablet, look away for a minute — before " +
                    "deciding whether a piece needs more detail. Almost everyone adds less once they've had " +
                    "thirty seconds away from staring closely at their own work."
            ),
        )
    )

    private val knowingWhenToStop = Lesson(
        id = "knowing-when-to-stop",
        title = "Knowing When to Stop",
        summary = "The most underrated skill in wet-on-wet painting: finishing before you've painted past the good part.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Every painting has a point where it's genuinely done, and it's earlier than you'll expect " +
                    "the first several times you try this. Because wet-on-wet makes it so easy to keep " +
                    "adjusting — nothing's ever fully locked in while it's wet — the real skill isn't knowing " +
                    "how to paint. It's knowing when your hand should stop moving."
            ),
            LessonBlock.Heading("The Piece Usually Peaks Before You Think It Does"),
            LessonBlock.Paragraph(
                "There's a specific, recognizable moment in most landscapes like this one: the big shapes are " +
                    "settled, one or two small details have earned their place, and the whole thing reads as " +
                    "calm and intentional. That's the peak. Everything painted after that point is usually " +
                    "either invisible at normal viewing distance or, worse, actively muddies something that " +
                    "was already working."
            ),
            LessonBlock.Paragraph(
                "This isn't about rushing or settling for less than your best. It's the opposite — trusting " +
                    "that the loose, confident big shapes you built are already good, and that the biggest " +
                    "risk left in the painting is you, second-guessing something that didn't need fixing. " +
                    "Every course in this app will ask you to keep working at something. This is the one " +
                    "lesson that's allowed to just say: put the stylus down. You're done."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Big shapes settled + one small focal detail is usually a complete, finished piece.",
                    "If you're adding a mark and can't say what problem it's solving, that's a sign to stop.",
                    "Muddiness in a wet-on-wet piece almost always comes from working past the finishing point, not from an early mistake.",
                    "'Good enough and calm' beats 'technically more detailed but muddy' nearly every time in this style.",
                )
            ),
            LessonBlock.Tip(
                "Genuinely useful trick: before your next stroke, ask 'does this piece need it, or do I just " +
                    "feel like I should still be working?' Those are different questions, and only the first " +
                    "one should decide whether you keep going."
            ),
        )
    )

    val course: Course = Course(
        id = "wet-on-wet-landscapes",
        title = "Wet-on-Wet Landscapes",
        instructorId = Instructors.dune.id,
        description = "Build a whole simple landscape in one sitting by blending colors while they're " +
            "still wet — big shapes first, a limited palette, and the confidence to paint over anything " +
            "that isn't working.",
        lessons = listOf(
            introduceWetOnWet,
            limitedPalette,
            skyMountainWater,
            happyLittleDetails,
            knowingWhenToStop,
        ),
    )
}
