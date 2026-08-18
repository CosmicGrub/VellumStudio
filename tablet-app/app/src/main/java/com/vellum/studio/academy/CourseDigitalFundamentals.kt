package com.vellum.studio.academy

object CourseDigitalFundamentals {
    val course: Course = Course(
        id = "digital-fundamentals",
        title = "Digital Drawing Fundamentals",
        instructorId = Instructors.marisol.id,
        description = "The stylus-specific, practical skills that make Vellum Studio feel natural in your " +
            "hand — pressure control, navigation, layers, undo, and picking the right brush for the job.",
        lessons = listOf(
            Lesson(
                id = "pressure-sensitivity",
                title = "Getting Comfortable With Pressure Sensitivity",
                summary = "A simple one-line exercise to build a feel for how much the S Pen's pressure " +
                    "range actually changes your stroke.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "Your S Pen isn't just a stick that makes marks — it's constantly reporting exactly " +
                            "how hard you're pressing, and most brushes in the app use that information to " +
                            "change the size and richness of your stroke as you draw. That's an enormous amount " +
                            "of expressive control sitting right in your hand, and it's worth spending a few " +
                            "focused minutes actually feeling it out."
                    ),
                    LessonBlock.Heading("What Pressure Actually Controls"),
                    LessonBlock.Paragraph(
                        "Depending on the brush, pressure typically affects stroke width (press harder, get a " +
                            "thicker line), opacity (press harder, get a more solid, opaque mark), or both at " +
                            "once. A light touch with a Pencil brush gives you a faint, thin line similar to a " +
                            "soft graphite sketch mark; pressing down harder gives you a bold, thick line. This " +
                            "is exactly how a real pencil or brush behaves in your hand, which is part of why it " +
                            "can feel intuitive fast — even if it takes a little practice to trust."
                    ),
                    LessonBlock.Heading("The One Exercise Worth Doing"),
                    LessonBlock.Paragraph(
                        "Pick any pressure-sensitive brush — Pencil is a good default — and draw one single " +
                            "continuous line across the screen, starting as light and thin as you can manage and " +
                            "gradually pressing harder until it ends thick and bold. Don't lift the pen. The " +
                            "goal isn't a pretty line; it's building a physical feel for your S Pen's full " +
                            "range, from barely-touching to firmly-pressed, in one motion."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Pick a pressure-sensitive brush (Pencil is ideal for this — its response is easy to read).",
                            "Start the stroke with the lightest touch you can manage without lifting off the screen.",
                            "Gradually increase pressure across the length of the line, ending as hard as feels natural.",
                            "Repeat it 5 to 10 times, trying to make the transition smoother and more controlled each time.",
                            "Try the same exercise with a different brush (Marker or Airbrush) to feel how the pressure response differs."
                        )
                    ),
                    LessonBlock.Tip(
                        "If your line looks jumpy or inconsistent at first, that's completely normal — your " +
                            "hand is learning a new physical vocabulary. Consistent pressure control is a motor " +
                            "skill, not a knowledge one, and like any motor skill it comes from repetition, not " +
                            "from understanding it intellectually."
                    ),
                    LessonBlock.Tip(
                        "Keep this exercise in your back pocket as a genuine warm-up, not just a one-time " +
                            "lesson. Thirty seconds of light-to-heavy lines before a drawing session loosens up " +
                            "your hand and re-calibrates your feel for the pen, the same way a musician runs " +
                            "scales before playing."
                    )
                )
            ),
            Lesson(
                id = "stylus-only-palm-rejection",
                title = "Why the App Is Stylus-Only for Drawing (and What Your Fingers Do Instead)",
                summary = "The S Pen draws, your fingers navigate the canvas, and touching the screen with " +
                    "your hand while the pen is down is deliberately ignored so you can rest your hand " +
                    "naturally while you draw.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "If you've ever tried to draw on a touchscreen and accidentally smeared a line with " +
                            "the side of your hand, you'll immediately appreciate why Vellum Studio splits jobs " +
                            "the way it does: the S Pen draws, and your fingers do everything else. This isn't a " +
                            "limitation — it's a deliberate design choice that makes the app feel more like " +
                            "drawing on paper, not less."
                    ),
                    LessonBlock.Heading("The Pen Draws, Fingers Navigate"),
                    LessonBlock.Paragraph(
                        "Only the S Pen makes marks on the canvas. Touch the screen with one finger and, " +
                            "instead of drawing, you pan the canvas around. Touch with two fingers and pinch or " +
                            "twist, and you zoom and rotate the canvas instead. None of these finger gestures " +
                            "ever leave a mark — they're purely for moving around your work, the same way you'd " +
                            "slide a piece of paper around on a desk or turn it to a better angle while sketching."
                    ),
                    LessonBlock.Heading("Why Your Hand Can Rest on the Screen"),
                    LessonBlock.Paragraph(
                        "Here's the detail that actually matters day to day: while the pen tip is down and " +
                            "drawing, any finger touching the screen at the same time is deliberately ignored, a " +
                            "feature usually called palm rejection. That means you can rest the side of your " +
                            "hand on the screen exactly the way you would rest it on a sheet of paper while " +
                            "writing or sketching — without it registering as a stray touch, panning the canvas " +
                            "out from under you, or leaving an accidental mark."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "S Pen touching the canvas: draws a stroke with your current brush.",
                            "One finger touching the canvas: pans (slides) the canvas around.",
                            "Two fingers touching the canvas: pinch to zoom, twist to rotate.",
                            "Finger touching the screen while the pen is actively drawing: ignored on purpose, so your resting hand doesn't interfere."
                        )
                    ),
                    LessonBlock.Tip(
                        "If you're used to a finger-drawing app, it can feel strange at first that finger " +
                            "touches don't draw at all here. Give it a session or two — most people find that " +
                            "once their hand can rest naturally on the screen the way it would on paper, drawing " +
                            "actually feels more comfortable, not less."
                    ),
                    LessonBlock.Tip(
                        "Get in the habit of using two fingers to rotate the canvas to a comfortable angle " +
                            "before a tricky stroke, the way you'd turn a physical sheet of paper. Fighting an " +
                            "awkward wrist angle instead of just rotating the canvas is a surprisingly common " +
                            "source of shaky lines."
                    )
                )
            ),
            Lesson(
                id = "working-with-layers",
                title = "Working With Layers",
                summary = "Why splitting a drawing across a sketch layer, a line art layer, and a color " +
                    "layer underneath makes coloring far less stressful, and is the standard workflow this " +
                    "app is built around.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "If you've only ever drawn on a single sheet of paper, the idea of layers can feel like " +
                            "an extra thing to learn before you're even allowed to start drawing. It's worth the " +
                            "five minutes, though — layers solve a very specific, very common frustration, and " +
                            "once it clicks, you won't want to go back."
                    ),
                    LessonBlock.Heading("A Simple Three-Layer Setup"),
                    LessonBlock.Paragraph(
                        "A solid starting habit: put your rough sketch on one layer, your cleaned-up line art " +
                            "on a layer above it, and your color on a layer underneath the line art. With this " +
                            "setup, you can turn the sketch layer's visibility off once you're done referencing " +
                            "it, and — this is the important part — you can color freely on that bottom layer " +
                            "without ever risking smudging or covering up the line art sitting above it."
                    ),
                    LessonBlock.Heading("Why This Is the Standard Professional Workflow"),
                    LessonBlock.Paragraph(
                        "This isn't just a beginner training-wheels trick — it's genuinely how professional " +
                            "illustrators work, because it separates two tasks (drawing clean lines, and filling " +
                            "in color) that are much harder to do well at the same time than one after another. " +
                            "It's also exactly why the app's Coloring Book feature locks the line-art layer on " +
                            "top: that lock is protecting your lines from accidental color strokes, using the " +
                            "same principle you're learning here, just automated for you."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Sketch layer: rough, loose exploration — expect it to look messy, that's fine, it's not the final drawing.",
                            "Line art layer: your cleaned-up, confident linework, sitting above the sketch.",
                            "Color layer: placed below the line art, so color strokes never cover or smudge your lines.",
                            "Turn off the sketch layer's visibility once your line art is done — no need to delete it, just hide it.",
                            "Add a shading layer above color (and below line art) if you want highlights and shadows to stay editable separately."
                        )
                    ),
                    LessonBlock.Tip(
                        "If a color stroke ever does creep outside your lines, that's a sign to zoom in more " +
                            "before coloring tight areas, not a sign that layers aren't working. Precision at " +
                            "the edges of a shape is still worth some care, even with the safety net layers " +
                            "give you."
                    ),
                    LessonBlock.Tip(
                        "Name your layers as you create them (sketch, lines, color, shading) rather than " +
                            "leaving them as Layer 1, Layer 2, Layer 3. It takes five seconds now and saves real " +
                            "confusion later, especially once a piece has six or seven layers in it."
                    )
                )
            ),
            Lesson(
                id = "undo-generously-save-often",
                title = "Undo Generously, Save Often",
                summary = "Permission to lean on undo constantly while learning, plus how the app's undo " +
                    "history and autosave work so you never feel afraid to try something.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "New to drawing, you'll probably feel a little guilty every time you hit undo, like " +
                            "you're supposed to get it right the first time. You're not, and you don't have to. " +
                            "Undo isn't a crutch or a sign you're behind — it's one of the most-used buttons for " +
                            "every artist, beginner or professional, working digitally."
                    ),
                    LessonBlock.Heading("Undo Is Not Cheating"),
                    LessonBlock.Paragraph(
                        "Every artist you admire has hit undo hundreds of times on pieces you'll never see the " +
                            "earlier versions of. Digital drawing's entire advantage over a permanent medium " +
                            "like ink on paper is that you can try something, immediately see if it works, and " +
                            "back out instantly if it doesn't. Use that advantage constantly and without guilt — " +
                            "it's not a workaround, it's the actual point of working digitally."
                    ),
                    LessonBlock.Heading("How Undo History and Autosave Work Here"),
                    LessonBlock.Paragraph(
                        "The app keeps a bounded undo history, meaning you can step backward through your " +
                            "recent actions, but that history isn't infinite — very old actions eventually roll " +
                            "off as you keep working. Your work is also autosaved periodically as you go, so a " +
                            "crash or an accidental app close shouldn't cost you your whole session. Neither of " +
                            "these replaces good habits, though: they're a safety net, not a substitute for " +
                            "being thoughtful before a big, hard-to-reverse change."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Use undo freely while sketching or experimenting — it costs you nothing and it's what the tool is for.",
                            "Remember undo history is bounded, not infinite — very early actions from a long session may eventually roll off.",
                            "Autosave protects you from crashes and accidental closes, but isn't a substitute for occasionally saving a version you're happy with.",
                            "Before trying something risky (a big color change, a drastic edit), duplicate the layer first as a safety net.",
                            "If an experiment doesn't work out, just hide or delete the duplicate and go back to the original — no harm done."
                        )
                    ),
                    LessonBlock.Tip(
                        "Make duplicating a layer before a risky move a reflex, the same way you'd save a " +
                            "document before making a big edit. It takes one tap and means you can be genuinely " +
                            "bold with an experiment, because the safe version is still sitting right there " +
                            "underneath."
                    ),
                    LessonBlock.Tip(
                        "If you notice yourself hesitating to try something because you're worried about " +
                            "'ruining' the piece, that's usually a sign to duplicate the layer and just go for " +
                            "it. The fear of ruining it is almost always bigger than the actual risk, especially " +
                            "with undo and layer duplication both available to you."
                    )
                )
            ),
            Lesson(
                id = "choosing-the-right-brush",
                title = "Picking the Right Brush for the Job",
                summary = "A plain-language field guide to the app's brush set, matching each one to the " +
                    "real-world tool it simulates and when to reach for it.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "Staring at a long list of brush names with no idea what most of them actually do is " +
                            "its own special kind of overwhelming. Here's the good news: each one is modeled on " +
                            "a real physical tool, so if you've ever held a pencil, a marker, or a paintbrush, " +
                            "you already have a head start on what each of these feels like and when to use it."
                    ),
                    LessonBlock.Heading("The Brush Field Guide"),
                    LessonBlock.Paragraph(
                        "Run through this list once, then don't worry about memorizing it — you'll naturally " +
                            "remember the ones you actually use. When in doubt while working on a piece, come " +
                            "back and skim it again."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Pencil — simulates a graphite pencil: soft, slightly textured, pressure-sensitive line. Your default for sketching, rough layout, and anything meant to feel loose and exploratory.",
                            "Ink Pen — simulates a fine felt-tip or technical pen: bold, confident, mostly consistent width. Reach for it for clean line art and outlines you want to read clearly.",
                            "Fineliner — simulates a very thin technical drafting pen: crisp and precise. Use it for small details, fine linework, and tight areas where the Ink Pen feels too heavy.",
                            "Marker — simulates a broad felt marker: flat, mostly solid coverage with a slightly translucent build-up. Good for blocking in flat color areas or bold graphic shapes fast.",
                            "Highlighter — simulates a highlighter pen: very translucent, doesn't cover what's underneath. Useful for quick markup, light color washes, or emphasis strokes over existing work.",
                            "Watercolor — simulates a loaded watercolor brush: soft edges, translucent, genuinely mixes colors where strokes overlap. Reach for it for soft blended color, atmosphere, and painterly work (see the Watercolor Technique course for a deep dive).",
                            "Pastel — simulates a soft pastel stick: chalky, textured, blends softly at the edges. Good for soft shading, textured backgrounds, and warm, tactile color work.",
                            "Airbrush — simulates a spray airbrush: very soft, diffuse gradient with no hard edge at all. Ideal for smooth shading, soft glows, and gentle gradients like skies or soft lighting.",
                            "Flat Brush — simulates a flat-edged paintbrush: gives a stroke with visible edges and some texture depending on angle. Handy for painterly blocking-in and strokes that show some brush character.",
                            "Eraser — removes strokes rather than adding them, and is pressure-sensitive too — light pressure can soften or partially erase, heavy pressure removes fully, depending on the brush you're erasing."
                        )
                    ),
                    LessonBlock.Heading("The Eraser Family"),
                    LessonBlock.Paragraph(
                        "Erasing isn't one job — fixing a stray line, softening a hard edge, and clearing a " +
                            "whole area all want different tools in real life, so the eraser gets the same " +
                            "range of physical variety the drawing brushes do instead of just one generic " +
                            "'undo the ink' setting."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Precision — small and crisp, for cleaning up one stray line or a tight detail without touching anything around it.",
                            "Soft — a wide, graduated edge for gently softening or fading a line rather than punching a hard-edged hole in it.",
                            "Hard Block — big and crisp-edged, like a vinyl eraser block — built to clear a lot of area fast, full strength.",
                            "Kneaded — textured and gradual, like a real kneaded eraser lifting graphite a little at a time. One pass only partially clears; go over an area again to lift more.",
                            "Fade — very soft and wide with no hard edge at all, for gently fading or vignetting toward an edge over several passes rather than a clean single wipe."
                        )
                    ),
                    LessonBlock.Tip(
                        "If you're not sure which brush to use for something, ask yourself what real-world " +
                            "tool you'd reach for to get that effect on paper — a pencil sketch, a marker fill, " +
                            "an ink outline — and pick the digital brush modeled on it. That instinct will be " +
                            "right more often than you'd expect, even before you've learned each brush's quirks."
                    ),
                    LessonBlock.Tip(
                        "Try the exact same simple drawing — say, a single apple — once with each drawing " +
                            "brush, and keep them all side by side. It's a fast, very concrete way to build an " +
                            "intuition for what each brush actually feels like, instead of just knowing what " +
                            "it's technically supposed to do. Then draw one more and practice on it with each " +
                            "eraser in turn — the differences are just as real."
                    )
                )
            )
        )
    )
}
