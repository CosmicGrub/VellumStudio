package com.vellum.studio.academy

object CourseWatercolor {
    val course: Course = Course(
        id = "watercolor",
        title = "Watercolor Technique",
        instructorId = Instructors.dune.id,
        description = "Get real, practical mileage out of Vellum Studio's Watercolor brush — how its " +
            "pigment mixing and layering actually behave, and how to pair it with other brushes for a " +
            "finished look.",
        lessons = listOf(
            Lesson(
                id = "digital-watercolor-behavior",
                title = "How Digital Watercolor Behaves Differently From a Photo Reference",
                summary = "What the app's Watercolor brush actually simulates — genuine pigment mixing and " +
                    "buildable opacity — and what it doesn't try to be.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "If you've seen videos of real watercolor paint blooming and pooling on wet paper, " +
                            "it's worth resetting your expectations before you pick up the app's Watercolor " +
                            "brush. It isn't trying to simulate wet paper, water pooling, or fluid physics — " +
                            "it's doing something a little different, and once you understand what that is, you " +
                            "can use it very deliberately instead of fighting it."
                    ),
                    LessonBlock.Heading("What It's Simulating, and What It Isn't"),
                    LessonBlock.Paragraph(
                        "The Watercolor brush is built to feel translucent and soft-edged, the way a loaded " +
                            "brush of watery paint feels on paper — and as of this brush's most recent pass, " +
                            "that softness is a genuine effect, not just a jittered edge: the moment a stroke " +
                            "commits, its edges actually spread and soften a little, the way real pigment " +
                            "bleeds into damp paper. What it still isn't doing is calculating how actual water " +
                            "would flow, pool, or dry over time — there's no simulated puddle creeping across " +
                            "the canvas, no drying-time effects, and the spread is a fixed amount tied to the " +
                            "brush, not something that reacts to how 'wet' the paper already is. Think of it as " +
                            "'a brush with real edge diffusion built in' rather than 'a fluid simulator.'"
                    ),
                    LessonBlock.Heading("Pigment Mixing, In Plain Terms"),
                    LessonBlock.Paragraph(
                        "Here's the part that genuinely matters for how you paint: pigment mixing is turned on " +
                            "for this brush, meaning when one translucent stroke overlaps another, the app " +
                            "blends them toward a new color where they cross — the same way glazing works with " +
                            "real watercolor. Lay a translucent blue stroke, then a translucent yellow stroke " +
                            "crossing over part of it, and the overlap will read as a mixed green, while the " +
                            "parts that don't overlap stay their original color. This isn't a filter or an " +
                            "effect you turn on — it's just what happens naturally whenever two watercolor " +
                            "strokes cross."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "The brush stays translucent — what's underneath a stroke still shows through it, at least partly.",
                            "Overlapping two different colors genuinely mixes them in the overlap area, not just layers them.",
                            "Painting over the same spot again builds up opacity and richness, the way repeated glazes do in real watercolor.",
                            "Each stroke's edges genuinely soften and spread a little the instant it commits — real diffusion, not a texture trick.",
                            "It still isn't simulating wet paper drying over time, water pooling, or paper wetness varying by area — the spread amount is fixed, not reactive."
                        )
                    ),
                    LessonBlock.Tip(
                        "Do a five-minute test swatch before starting a real piece: lay down a few overlapping " +
                            "translucent strokes in different colors and just watch what happens where they " +
                            "cross. Seeing the mixing behavior with your own eyes, on a throwaway scrap, will " +
                            "teach you more in five minutes than any written explanation."
                    ),
                    LessonBlock.Tip(
                        "Because opacity builds with repeated passes, going slow and layering up is almost " +
                            "always more forgiving than trying to lay down the exact right color and value in " +
                            "one confident stroke. We'll get into that layering approach properly in the next " +
                            "lesson."
                    )
                )
            ),
            Lesson(
                id = "layering-washes",
                title = "Layering Washes",
                summary = "Building color gradually in light translucent layers instead of trying to nail " +
                    "the final color in a single pass.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "One of the biggest mindset shifts when you start using the Watercolor brush is giving " +
                            "up on getting it right in one stroke. That's not a failure of skill — it's just not " +
                            "how this brush (or real watercolor, for that matter) is meant to be used. The " +
                            "actual technique is patience: light layers, built up gradually."
                    ),
                    LessonBlock.Heading("Start Lighter Than Feels Right"),
                    LessonBlock.Paragraph(
                        "When you're new to this, the instinct is to pick the color and value you want the " +
                            "final result to look like, and lay it down at full strength immediately. Resist " +
                            "that. Start with your color at a lower opacity or a lighter touch than you think " +
                            "you need — it will look pale and underwhelming at first, and that's exactly " +
                            "correct. You build toward the final richness with additional passes, not by " +
                            "nailing it upfront."
                    ),
                    LessonBlock.Heading("Let the Layer Underneath Show Through"),
                    LessonBlock.Paragraph(
                        "Because the brush is translucent, a light first wash doesn't get hidden by what you " +
                            "paint next — it shows through and contributes to the final color, giving your " +
                            "piece a sense of depth that's hard to get any other way. A shadow painted as a " +
                            "second translucent pass over a base color will look like it belongs to that " +
                            "surface, rather than sitting on top of it like a sticker. This 'letting the base " +
                            "read through' effect is most of what makes layered watercolor work look rich " +
                            "instead of flat."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Lay down a light, thin base layer first and treat that pass as settled before moving on.",
                            "Add a second translucent pass only where you want more depth or a shift in color, not over the whole shape again.",
                            "Check the piece from a distance (zoom out) after each layer before deciding to add another.",
                            "Reserve your darkest, richest passes for last, and only in the smallest areas that need them.",
                            "If an area looks too pale, add another light layer rather than jumping straight to full opacity."
                        )
                    ),
                    LessonBlock.Tip(
                        "If a section of your painting isn't matching what's in your head, don't panic and slap " +
                            "on a heavy layer to fix it fast. Add one more light pass and step back. Watercolor " +
                            "work almost always looks a little uncertain in the middle stages and comes together " +
                            "in the last few layers — that's completely normal, not a sign you're doing it wrong."
                    ),
                    LessonBlock.Tip(
                        "Try painting the same simple shape — an apple, a leaf, a circle — three times: once " +
                            "trying to nail the color in one pass, once with three light layers, and once with " +
                            "six. Comparing the results side by side is the fastest way to feel why layering " +
                            "gives you more control."
                    )
                )
            ),
            Lesson(
                id = "soft-vs-defined-edges",
                title = "Edges: Soft vs Defined",
                summary = "Using the Watercolor brush's soft edge for atmosphere and switching to a " +
                    "Fineliner or Ink Pen for crisp details, so the piece reads as intentional.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "Look closely at watercolor illustrations you admire and you'll usually notice " +
                            "something: not every edge in the piece is soft and blurry. The soft, blended areas " +
                            "sit right next to crisp, confident lines — and that contrast is doing a lot of the " +
                            "work to make the piece look finished rather than smudgy."
                    ),
                    LessonBlock.Heading("When the Watercolor Brush's Soft Edge Works For You"),
                    LessonBlock.Paragraph(
                        "The Watercolor brush's naturally gentle, feathered edge is genuinely useful — for " +
                            "skies, shadows, foliage, skin tones, anything that should feel soft or atmospheric " +
                            "with no hard boundary. Lean into that instead of fighting it in these areas. Trying " +
                            "to force the Watercolor brush to make a crisp, ruler-straight edge usually just " +
                            "fights the tool and gives you a mediocre version of something another brush already " +
                            "does well."
                    ),
                    LessonBlock.Heading("Bringing In a Crisp Brush for the Details"),
                    LessonBlock.Paragraph(
                        "For the details that need to read clearly — an eye, a leaf vein, the edge of a " +
                            "building, a signature line — switch to a Fineliner or Ink Pen. These brushes give " +
                            "you the sharp, defined edge that Watercolor deliberately doesn't, and working on " +
                            "top of a soft watercolor wash with a crisp line is exactly what separates a piece " +
                            "that looks intentional from one that just looks unfinished. This combination — " +
                            "soft washes underneath, sharp linework on top — is a genuinely standard technique, " +
                            "not a shortcut or a cheat."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Watercolor brush: skies, backgrounds, broad shadow shapes, skin tones, anything meant to feel soft or blended.",
                            "Fineliner: fine details, small precise marks, texture like hair strands or grass.",
                            "Ink Pen: bolder defining lines, outlines you want to read clearly even at a glance.",
                            "Try adding crisp linework only after your watercolor layers are mostly settled, so you know where detail is actually needed."
                        )
                    ),
                    LessonBlock.Tip(
                        "If a piece feels muddy or unfinished even after several watercolor layers, the fix is " +
                            "often not more washes — it's a handful of small, confident Fineliner marks in the " +
                            "focal area. A few sharp details next to soft color goes a long way."
                    ),
                    LessonBlock.Tip(
                        "Don't feel obligated to add crisp edges everywhere. Some of the best watercolor pieces " +
                            "have just one or two small areas of sharp detail and leave everything else soft — " +
                            "that contrast is more effective than covering the whole piece in linework."
                    )
                )
            ),
            Lesson(
                id = "color-mixing-on-page",
                title = "Color Mixing on the (Digital) Page",
                summary = "A hands-on exercise in overlapping two translucent watercolor strokes to mix a " +
                    "new color live on the canvas instead of pre-mixing in the color picker.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "Here's a genuinely fun one. Instead of opening the color picker and dragging sliders " +
                            "around until you land on the color you want, you can just put two colors down and " +
                            "let them mix on the page in front of you. It feels a little bit like a magic trick " +
                            "the first time you see it happen, and it's a great way to get a feel for how the " +
                            "Watercolor brush's pigment mixing actually works."
                    ),
                    LessonBlock.Heading("Try It: Blue Meets Yellow"),
                    LessonBlock.Paragraph(
                        "Grab a fresh blank layer. Pick a blue and lay down one loose translucent stroke of it " +
                            "— it doesn't need to be neat, just a swipe. Now pick a yellow and lay a second " +
                            "stroke that overlaps part of the blue one. Watch what happens in the overlap: it " +
                            "shifts toward green, while the parts of each stroke that don't overlap stay blue " +
                            "and yellow. You just mixed a color on the canvas instead of in the picker."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Pick two colors you wouldn't normally think to combine and try the overlap on a blank layer — there's no wrong answer here.",
                            "Blue over yellow gives you green; try red over yellow, or blue over red, and see what you get.",
                            "Vary how much the two strokes overlap — a small sliver gives a subtle mixed edge, a big overlap gives a large mixed area.",
                            "Try laying one color as a base, then dragging a second translucent stroke slowly across it to see the mix build gradually.",
                            "Keep this scrap layer around and treat it like a cheat sheet of colors you've discovered, worth revisiting later."
                        )
                    ),
                    LessonBlock.Tip(
                        "This is a great five-minute warm-up before starting any watercolor piece — it gets " +
                            "your hand moving, reminds you how the brush behaves today (pressure and brush " +
                            "settings can shift the feel slightly), and sometimes hands you a color combo " +
                            "you'll actually use in the piece you're about to paint."
                    ),
                    LessonBlock.Tip(
                        "There's no failure state in this exercise. Muddy, unexpected, or 'wrong' colors are " +
                            "just more information about how these two hues behave together — treat every " +
                            "overlap as data, not a mistake, and you'll start developing a real intuition for " +
                            "color mixing faster than any chart could teach you."
                    )
                )
            )
        )
    )
}
