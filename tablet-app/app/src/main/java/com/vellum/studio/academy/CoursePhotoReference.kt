package com.vellum.studio.academy

/**
 * The newest tools in Vellum Studio, taught plainly: turning a photo from your own library into a
 * coloring page (line art, a full-color reference, and sometimes a paint-by-number activity), and
 * pulling up an on-device pose skeleton over a reference photo to guide figure drawing. Both tools
 * are genuinely new capabilities added in this expansion (see canvas/PhotoConverter.kt and
 * canvas/PoseOverlay.kt), so this course teaches their real, current mechanics — not marketing
 * copy — the same way every other Academy course teaches this app's actual buttons and workflows.
 * Taught by Rowan (see Instructor.kt) — reused rather than inventing a new persona because Rowan's
 * "here's precisely why this works" style is the right fit for explaining on-device tools honestly,
 * including where they don't work, and Rowan already teaches this app's other figure-drawing course.
 */
object CoursePhotoReference {

    private val turningPhotosIntoPages = Lesson(
        id = "turning-a-photo-into-a-coloring-page",
        title = "Turning a Photo Into a Coloring Page",
        summary = "The actual steps: pick a photo, choose a style, and get back a line-art page plus " +
            "a full-color reference — done entirely on your tablet.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "Every coloring page bundled with this app started as a piece of art someone else made. " +
                    "This tool lets you make one starting from a photo you took yourself — a pet, a plant on " +
                    "your windowsill, a vacation photo, anything. Open Coloring Book and tap the small photo " +
                    "icon in the top bar. Pick any photo from your library, give it a name, and choose a " +
                    "style. That's the whole flow."
            ),
            LessonBlock.Heading("What Actually Happens When You Convert"),
            LessonBlock.Paragraph(
                "Behind that one tap, the app runs real, honest image analysis entirely on your tablet — no " +
                    "photo ever leaves the device, and there's no internet connection or account involved. It " +
                    "smooths the photo, sorts it into a handful of tone bands from lightest to darkest, and " +
                    "traces the boundary of each band into a clean outline. That's why the result looks like " +
                    "actual line art instead of a blurry photo filter: it's genuinely re-deriving shapes from " +
                    "your photo's light and dark areas, not just outlining edges."
            ),
            LessonBlock.Heading("You Get Two Things, Not Just One"),
            LessonBlock.Paragraph(
                "Conversion always produces a pair: a blank line-art page (what you'll actually color) and a " +
                    "full-color reference image (what the photo looked like, for you to match or ignore). Both " +
                    "land together in the My Photos category. Tap a converted page's eye icon any time to pull " +
                    "the reference back up and compare your coloring against it — the same reference viewer " +
                    "the bundled Masterworks pages already use."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Coloring Book → photo icon (top bar) → pick a photo from your library.",
                    "Give it a name, pick Simple or Detailed, tap Convert. This runs fully on-device — no network call, no cost, no account.",
                    "Conversion can take up to about 20 seconds depending on the photo — that's real, CPU-bound analysis, not a canned delay.",
                    "The result saves into the My Photos category automatically, as a real card alongside every bundled page.",
                    "Tap the eye icon on a converted card any time afterward to see the full-color reference again.",
                )
            ),
            LessonBlock.Tip(
                "Simple and Detailed aren't just 'less/more detail' — Simple also smooths and simplifies more " +
                    "aggressively before tracing outlines, which is what makes it noticeably more likely to " +
                    "produce a clean, fully-closed page. Start every new photo on Simple; only try Detailed if " +
                    "Simple's result feels too plain and you're willing to risk messier outlines for it."
            ),
        )
    )

    private val whyPaintByNumberSometimes = Lesson(
        id = "why-paint-by-number-sometimes",
        title = "Why Some Photos Become Paint by Number and Others Don't",
        summary = "The honest, practical reason paint-by-number sometimes isn't offered for a photo you " +
            "convert — and how to pick photos that get it.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "After converting a photo, the app tells you plainly whether Paint by Number is available " +
                    "for that specific page or not. This isn't random, and it isn't a quality judgment on your " +
                    "photo — it comes down to one concrete, checkable fact about the outlines the conversion " +
                    "produced, and it's worth understanding so you can pick photos that work well for it."
            ),
            LessonBlock.Heading("Paint by Number Needs Closed Shapes"),
            LessonBlock.Paragraph(
                "Paint by Number works by flood-filling a tapped region until it hits a wall of outline in " +
                    "every direction — the same way a bucket-fill tool stops at a boundary anywhere else in " +
                    "this app. That only works if the traced outlines actually form complete, closed shapes. " +
                    "If an outline has even a small gap, the 'wall' isn't complete, and a fill can leak straight " +
                    "through it into the rest of the page instead of staying contained. The app checks this for " +
                    "real after converting — it counts the actual closed regions it found — rather than " +
                    "guessing, so what it tells you is ground truth about that specific page, not an estimate."
            ),
            LessonBlock.Heading("What Makes a Photo Close Up Nicely"),
            LessonBlock.Paragraph(
                "Photos with one clear subject, strong lighting, and a plain or simple background tend to " +
                    "convert into fully closed shapes reliably — think a pet against a wall, a single flower, a " +
                    "portrait with even light. Busy, cluttered, or low-contrast photos — a crowded scene, dim " +
                    "indoor lighting, lots of overlapping small objects — are more likely to produce outlines " +
                    "with gaps, because the tone differences the app relies on to find edges are subtler or " +
                    "get lost in visual noise."
            ),
            LessonBlock.Paragraph(
                "If a photo doesn't come out paint-by-number eligible, that page isn't wasted — it's still a " +
                    "completely real coloring or tracing page, and its full-color reference is still there to " +
                    "work from. It just means bucket fill isn't guaranteed to stay contained on it, so free " +
                    "coloring with brushes (where you're in control of where the color goes) is the better fit " +
                    "for that particular page."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Paint by Number needs the traced outlines to form fully closed shapes — no gaps for a fill to leak through.",
                    "A single clear subject, strong lighting, and a plain background are the biggest factors in getting closed shapes.",
                    "Busy, cluttered, or dim/low-contrast photos are more likely to end up as line art/tracing pages only — and that's a fine, expected outcome, not a failure.",
                    "The Simple style closes shapes more reliably than Detailed, precisely because it simplifies more before tracing.",
                    "Whatever the result, you always get a usable line-art page plus its full-color reference — eligibility only changes whether bucket fill is safe to use on it.",
                )
            ),
            LessonBlock.Tip(
                "If you really want a specific busy photo to work for Paint by Number, try cropping it tighter " +
                    "around just the subject before importing, or picking a moment in a similar scene with " +
                    "flatter, more even lighting. Simplifying the source photo yourself is often more effective " +
                    "than switching styles."
            ),
        )
    )

    private val poseReferenceGuide = Lesson(
        id = "pose-reference-guide",
        title = "The Pose Reference Guide for Figure Drawing",
        summary = "Import a photo of a person as a reference layer, then pull up an on-device skeleton " +
            "overlay to check gesture and proportion while you sketch.",
        blocks = listOf(
            LessonBlock.Paragraph(
                "The Anatomy Basics course teaches you to construct a figure from simple forms — the Loomis " +
                    "head method, gesture lines, tapered-cylinder limbs. This tool is a second, complementary " +
                    "way to check that construction against reality: " +
                    "point it at a real reference photo of a person, and it draws a simplified stick-figure " +
                    "skeleton — shoulders, hips, elbows, knees, the core landmarks — right over that photo, so " +
                    "you can compare your sketch's proportions and pose against real ones."
            ),
            LessonBlock.Heading("Setting It Up"),
            LessonBlock.Paragraph(
                "In the Editor, open the Layers panel and tap the photo icon at the top — that's 'Import " +
                    "reference image,' and it's the same import used any time you want a reference photo " +
                    "sitting in your project. Pick a photo that shows a person clearly. That photo becomes its " +
                    "own layer named Reference. Only a layer imported this specific way grows a small figure " +
                    "icon on its row in the Layers panel — an ordinary drawing layer never does, since pose " +
                    "detection against a layer you've been painting on wouldn't mean anything."
            ),
            LessonBlock.Heading("Reading the Skeleton"),
            LessonBlock.Paragraph(
                "Tap that figure icon and the app analyzes the photo on-device — you'll see a brief loading " +
                    "spinner while it works, then a skeleton of connected lines appears drawn over the " +
                    "reference layer. This is purely a visual guide, the same idea as a photographer's rule-of-" +
                    "thirds grid: it never touches or edits any pixel in your project, so it costs you nothing " +
                    "to leave it on. Sketch on a layer above the reference the way you normally would, using " +
                    "the skeleton's joint positions and limb angles to check that your figure's gesture and " +
                    "proportions genuinely match the photo instead of just eyeballing it. Tap the figure icon " +
                    "again any time to hide it, and again to bring it back without re-analyzing."
            ),
            LessonBlock.BulletList(
                listOf(
                    "Layers panel → photo icon → pick a photo of a person → it becomes a Reference layer.",
                    "Tap the figure icon on that layer's row to run on-device pose detection and show the skeleton overlay.",
                    "The skeleton is a guide only — it's drawn over the layer, never merged into it or your actual strokes.",
                    "Tap the figure icon again to hide the overlay, and again to bring back the same result instantly.",
                    "If the app says no clear pose was detected, that photo didn't work — try a different one rather than retrying the same photo.",
                )
            ),
            LessonBlock.Tip(
                "This works best on a photo where the person is clearly visible, reasonably well-lit, and not " +
                    "heavily obstructed by other people or objects — a clean reference photo of one person " +
                    "doing a clear pose, not a crowded or dim group shot. Sports photos, dance photos, and " +
                    "simple standing portraits all tend to work well."
            ),
            LessonBlock.Tip(
                "Treat the skeleton as a checking tool, not a tracing template. Sketch your own construction " +
                    "first, the way Anatomy Basics teaches, then bring up the overlay to see where your " +
                    "proportions drifted from the real photo — that comparison teaches your eye far more than " +
                    "drawing directly on top of the skeleton would."
            ),
        )
    )

    val course: Course = Course(
        id = "photo-reference-tools",
        title = "Drawing From Your Own Photos",
        instructorId = Instructors.rowan.id,
        description = "The two newest tools in Vellum Studio, explained plainly: converting your own " +
            "photos into coloring pages — with or without Paint by Number — and using an on-device pose " +
            "skeleton overlay to guide figure drawing from a reference photo.",
        lessons = listOf(
            turningPhotosIntoPages,
            whyPaintByNumberSometimes,
            poseReferenceGuide,
        ),
    )
}
