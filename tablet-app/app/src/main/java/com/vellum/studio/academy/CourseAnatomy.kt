package com.vellum.studio.academy

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

object CourseAnatomy {
    val course: Course = Course(
        id = "anatomy",
        title = "Anatomy Basics",
        instructorId = Instructors.rowan.id,
        description = "The friendly, approachable basics for sketching people with confidence — simple " +
            "construction tricks for heads, faces, and full figures, kept practical rather than an " +
            "exhaustive medical-illustration deep dive.",
        lessons = listOf(
            Lesson(
                id = "loomis-head-method",
                title = "The Loomis Head Method",
                summary = "A simple three-step construction — sphere, flat side, cross-lines — that makes " +
                    "starting a head far less intimidating than drawing a face freehand.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "Try to draw a face from nothing and your brain usually freezes — where do the eyes " +
                            "even go? Professional illustrators ran into the same wall, which is why an artist " +
                            "named Andrew Loomis worked out a shortcut decades ago that people still use today. " +
                            "Instead of drawing a face, you build a simple 3D form first, and that form tells " +
                            "you exactly where everything belongs."
                    ),
                    LessonBlock.Heading("Start With a Ball"),
                    LessonBlock.Paragraph(
                        "Draw a circle. That's it — that's step one, and yes, it's supposed to feel almost too " +
                            "simple. This circle stands in for the rounded top and back of the skull. Don't " +
                            "worry about getting it perfectly round; a slightly wobbly circle works just as " +
                            "well as a precise one, because you're about to add lines that fix everything else."
                    ),
                    LessonBlock.Heading("Add the Flat Side and the Cross-Lines"),
                    LessonBlock.Paragraph(
                        "Real heads aren't spheres — the face sits on a flatter plane toward the front, like " +
                            "someone sliced a small section off the front of a ball. Sketch a curved line down " +
                            "and across that front section to mark it off, then draw two guide lines on it: one " +
                            "running straight down the middle (the centerline, which tells you where the nose " +
                            "and mouth sit left-to-right) and one running across roughly through the middle " +
                            "(the eye line, which tells you where the eyes sit top-to-bottom). A simple wedge " +
                            "shape tapering down from the bottom of the ball gives you the jaw and chin."
                    ),
                    LessonBlock.Diagram(
                        caption = "The Loomis head: a simple ball, a flat front plane, and two guide lines " +
                            "that tell you where the face goes.",
                        draw = { canvas: Canvas, size: Int ->
                            val linePaint = Paint().apply {
                                isAntiAlias = true
                                style = Paint.Style.STROKE
                                color = Color.rgb(60, 60, 70)
                                strokeWidth = size * 0.006f
                            }
                            val textPaint = Paint().apply {
                                isAntiAlias = true
                                color = Color.rgb(90, 90, 100)
                                textSize = size * 0.03f
                            }
                            val cx = size * 0.5f
                            val cy = size * 0.4f
                            val r = size * 0.26f

                            canvas.drawCircle(cx, cy, r, linePaint)

                            val centerPath = Path().apply {
                                moveTo(cx, cy - r)
                                quadTo(cx + r * 0.15f, cy, cx, cy + r)
                            }
                            canvas.drawPath(centerPath, linePaint)

                            val eyeY = cy + r * 0.05f
                            val eyePath = Path().apply {
                                moveTo(cx - r, eyeY)
                                quadTo(cx, eyeY + r * 0.08f, cx + r, eyeY)
                            }
                            canvas.drawPath(eyePath, linePaint)

                            val chinY = cy + r * 1.55f
                            val jawPath = Path().apply {
                                moveTo(cx - r * 0.78f, cy + r * 0.35f)
                                lineTo(cx, chinY)
                                lineTo(cx + r * 0.78f, cy + r * 0.35f)
                            }
                            canvas.drawPath(jawPath, linePaint)

                            // Only the two guide lines the lesson text actually introduces
                            // (centerline, eye line) plus the jaw wedge are drawn here - an earlier
                            // draft also drew and labeled an unexplained third "brow line" that
                            // didn't match the "two guide lines" the paragraph and bullet list commit to.
                            canvas.drawText("eye line", cx + r + size * 0.02f, eyeY, textPaint)
                            canvas.drawText("centerline", cx + size * 0.02f, cy - r * 0.55f, textPaint)
                            canvas.drawText("jaw / chin", cx - size * 0.06f, chinY + size * 0.045f, textPaint)
                        }
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Draw a circle for the cranium — don't erase and redraw it, an imperfect circle is fine.",
                            "Curve a line down the front of the circle to mark the flat plane where the face sits.",
                            "Add a centerline down that flat plane to mark left-right symmetry.",
                            "Add an eye line roughly through the middle of the ball to mark top-to-bottom placement.",
                            "Taper a simple wedge down from the sides of the circle to rough in the jaw and chin.",
                            "Only after all the guide lines are down do you start placing actual features."
                        )
                    ),
                    LessonBlock.Tip(
                        "If your circle looks lopsided, that's fine — real heads aren't perfectly symmetrical " +
                            "either. What matters is that the cross-lines you add next are placed with " +
                            "intention, not that the starting ball is flawless."
                    ),
                    LessonBlock.Tip(
                        "Keep a handful of these construction heads in your sketchbook at different angles — " +
                            "tilted up, tilted down, turned to the side. You're not trying to make finished " +
                            "drawings, you're training your hand to find the form quickly."
                    )
                )
            ),
            Lesson(
                id = "placing-facial-features",
                title = "Placing Features on the Head",
                summary = "Rules of thumb for roughly where eyes, ears, nose, and mouth sit on the Loomis " +
                    "head, treated as a helpful starting point rather than a fixed rule.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "Once you've got your simple head construction down, the next question is always the " +
                            "same: okay, but where exactly do the features go? Artists have worked out some " +
                            "rough guidelines over the years — not because every face fits them exactly, but " +
                            "because they give you a sensible starting point instead of guessing blind."
                    ),
                    LessonBlock.Heading("The Eye Line Is (Roughly) the Middle"),
                    LessonBlock.Paragraph(
                        "Here's the one that surprises most beginners: the eyes sit at approximately the " +
                            "vertical midpoint of the head — not up near the top like most people instinctively " +
                            "draw them. If you measure from the very top of the skull to the bottom of the " +
                            "chin, the eye line lands close to halfway. This is exactly what the eye line you " +
                            "sketched in the Loomis head is marking."
                    ),
                    LessonBlock.Heading("Spacing and the Rest of the Features"),
                    LessonBlock.Paragraph(
                        "From there, a few more rules of thumb fill in the rest of the face. The width of one " +
                            "eye is roughly the same as the gap between the two eyes, so there's an invisible " +
                            "'third eye' worth of space in the middle. The ears typically line up between the " +
                            "eye line and the bottom of the nose, which is a handy way to check ear height and " +
                            "size at the same time. The bottom of the nose is usually about halfway between the " +
                            "eye line and the chin, and the mouth tends to fall roughly a third of the way down " +
                            "from the nose to the chin."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Eyes: roughly at the vertical midpoint of the head, about one eye-width apart from each other.",
                            "Ears: top to bottom, typically span from the eye line down to the bottom of the nose.",
                            "Nose: the bottom of it usually falls about halfway between the eye line and the chin.",
                            "Mouth: roughly a third of the way from the bottom of the nose down to the chin."
                        )
                    ),
                    LessonBlock.Tip(
                        "Treat every number here as a starting guess, not a ruler you hold real people to. " +
                            "Faces vary enormously — that variation is exactly what makes someone recognizable. " +
                            "Use these guidelines to get a reasonable first placement down fast, then adjust by " +
                            "actually looking (at a photo, a mirror, or a real person) and trusting what you see " +
                            "over the rule."
                    ),
                    LessonBlock.Tip(
                        "A quick way to practice: draw five Loomis heads in a row and place features using " +
                            "only these ratios, no reference. Then compare them to a real photo of a face at a " +
                            "similar angle. You'll immediately see where your eye naturally wants to deviate " +
                            "from the guideline — that's useful information about your own habits."
                    )
                )
            ),
            Lesson(
                id = "figure-proportions-basics",
                title = "Basic Figure Proportions",
                summary = "Using head-height as a measuring stick — roughly 7 to 8 heads tall — to fix the " +
                    "most common beginner mistake: giant heads and stubby legs.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "If you've ever drawn a full figure and it came out looking like a kid's proportions on " +
                            "an adult body — head too big, legs too short — you're in very good company. It's " +
                            "the single most common beginner mistake, and there's a simple trick that fixes it " +
                            "almost immediately: use the head as your ruler."
                    ),
                    LessonBlock.Heading("Heads as a Measuring Stick"),
                    LessonBlock.Paragraph(
                        "Take the height of the head you just drew — top of the skull to the bottom of the " +
                            "chin — and use that same length to measure down the rest of the body. Most adult " +
                            "figures work out to somewhere around 7 to 8 head-heights tall (illustrators and " +
                            "comic artists often stretch toward 8 for a more heroic, elongated look; 7 to 7.5 " +
                            "reads as more average and grounded). You don't need to be exact — the point is " +
                            "having a consistent unit to check yourself against, instead of eyeballing the whole " +
                            "body at once."
                    ),
                    LessonBlock.Heading("Where the Midpoint Falls"),
                    LessonBlock.Paragraph(
                        "Here's the detail that trips people up most: the halfway point of the body is not the " +
                            "waist, it's the hips — right around the groin. If you're working with a 7.5-head " +
                            "figure, that midpoint lands close to 3.75 heads down. Mark that point first and " +
                            "you've got an anchor for the rest of the figure: legs take up roughly the bottom " +
                            "half, and the torso, ribcage, and head fill the top half."
                    ),
                    LessonBlock.Diagram(
                        caption = "A simple 7.5-head-tall measuring stick — the hips land almost exactly at the halfway mark.",
                        draw = { canvas: Canvas, size: Int ->
                            val linePaint = Paint().apply {
                                isAntiAlias = true
                                style = Paint.Style.STROKE
                                color = Color.rgb(60, 60, 70)
                                strokeWidth = size * 0.005f
                            }
                            val hipPaint = Paint().apply {
                                isAntiAlias = true
                                style = Paint.Style.STROKE
                                color = Color.rgb(180, 90, 60)
                                strokeWidth = size * 0.008f
                            }
                            val textPaint = Paint().apply {
                                isAntiAlias = true
                                color = Color.rgb(90, 90, 100)
                                textSize = size * 0.026f
                            }
                            val top = size * 0.06f
                            val bottom = size * 0.96f
                            val totalHeight = bottom - top
                            val headUnit = totalHeight / 7.5f
                            val cx = size * 0.5f
                            val headR = headUnit * 0.5f

                            canvas.drawCircle(cx, top + headR, headR, linePaint)
                            canvas.drawLine(cx, top + headUnit, cx, bottom, linePaint)

                            for (i in 1..7) {
                                val y = top + headUnit * i
                                canvas.drawLine(cx - size * 0.05f, y, cx + size * 0.05f, y, linePaint)
                                canvas.drawText(i.toString(), cx + size * 0.06f, y, textPaint)
                            }

                            val hipY = top + headUnit * 3.75f
                            canvas.drawLine(cx - size * 0.14f, hipY, cx + size * 0.14f, hipY, hipPaint)
                            canvas.drawText("hips — roughly the midpoint", cx + size * 0.16f, hipY, textPaint)
                        }
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Draw the head first, and treat its height as one unit.",
                            "Mark about 1 head-height down for the chest area, roughly where the armpits sit.",
                            "Mark around 3.5 to 4 heads down for the hips — the body's approximate midpoint.",
                            "Mark around 4 heads down for where fingertips typically reach with arms relaxed at the sides.",
                            "Continue marking down to about 7 to 7.5 heads for the soles of the feet.",
                            "Legs alone usually take up nearly half the total height — this is the part beginners shrink by instinct."
                        )
                    ),
                    LessonBlock.Tip(
                        "If your figures keep coming out with short legs, it's almost always because the legs " +
                            "were an afterthought tacked onto a torso that already used up most of the page. " +
                            "Mark your head-unit ticks on the page before you draw a single body outline, the " +
                            "same way you'd mark measurements before cutting wood."
                    ),
                    LessonBlock.Tip(
                        "These ratios are for a general adult figure. Children have noticeably bigger heads " +
                            "relative to their bodies (more like 4 to 6 heads tall depending on age), and " +
                            "individual adults vary too. Use 7 to 7.5 as your default starting ruler, not a rule " +
                            "that overrides what you're actually trying to draw."
                    )
                )
            ),
            Lesson(
                id = "gesture-poses-figure",
                title = "Simple Gesture Poses",
                summary = "Capturing a pose's energy with a single line of action before blocking in volume " +
                    "with simple circles and tapered cylinders.",
                blocks = listOf(
                    LessonBlock.Paragraph(
                        "If you've worked through gesture drawing in the Foundations course, you've already " +
                            "practiced the most important skill for figure drawing: capturing energy and motion " +
                            "before worrying about accuracy. Now we apply that same idea to a full figure, using " +
                            "a couple of simple building blocks instead of jumping straight to a detailed outline."
                    ),
                    LessonBlock.Heading("The Line of Action"),
                    LessonBlock.Paragraph(
                        "Before you draw a single body part, draw one flowing line through where the spine " +
                            "would go — from the base of the skull down through the torso to somewhere around " +
                            "the hips. This is the line of action, and its whole job is to capture the pose's " +
                            "energy in one confident mark: a tired slouch curves one way, a triumphant leap " +
                            "curves another. Everything you draw afterward should feel like it's responding to " +
                            "this line, not fighting it."
                    ),
                    LessonBlock.Heading("Stick Figure With Volume"),
                    LessonBlock.Paragraph(
                        "Once the line of action is down, block in the figure using simple shapes: circles for " +
                            "the joints (shoulders, elbows, wrists, hips, knees, ankles) and simple tapered " +
                            "cylinders — thicker at one end, narrower at the other — for the limbs connecting " +
                            "them. Think of it as a stick figure that's been given just enough volume to suggest " +
                            "a real 3D body. This step is fast and rough on purpose; it lets you check the whole " +
                            "pose works before you invest time in details that might get erased anyway."
                    ),
                    LessonBlock.BulletList(
                        listOf(
                            "Draw one line of action first — a single sweeping curve, not a straight stick.",
                            "Drop in a circle for the ribcage area and a smaller circle for the pelvis, tilted to match the pose.",
                            "Add circles at each major joint: shoulders, elbows, wrists, hips, knees, ankles.",
                            "Connect the joints with tapered cylinders instead of straight lines — thicker near the body, narrower toward the hands and feet.",
                            "Check the whole figure against the line of action before adding any outline or detail.",
                            "Only refine into a cleaner outline once the gesture and proportions both feel right."
                        )
                    ),
                    LessonBlock.Tip(
                        "Set a timer for 30 to 60 seconds per pose when you're practicing this. The time " +
                            "pressure is doing you a favor — it stops you from sneaking into detail work before " +
                            "the gesture is actually solid, which is exactly the habit this exercise is meant to " +
                            "build."
                    ),
                    LessonBlock.Tip(
                        "It's completely normal for these to look like rough scaffolding, not art — because " +
                            "that's exactly what they are. The goal of a gesture pose is never to look finished. " +
                            "It's a fast, disposable sketch whose only job is to get the pose's energy right " +
                            "before you commit to anything permanent."
                    )
                )
            )
        )
    )
}
