package com.vellum.studio.academy

/**
 * A named teaching persona attached to a [Course], so working through a course's lessons feels
 * like a consistent, hands-on class led by someone specific rather than an anonymous stream of
 * text. These are original characters with their own name, voice, and identity — not modeled on,
 * named after, or meant to evoke any real person's likeness. Each persona's bio below doubles as
 * the style guide for how that course's lesson prose should read.
 */
data class Instructor(
    val id: String,
    val name: String,
    /** One line shown under the name wherever the instructor is credited. */
    val tagline: String,
    /** A couple of sentences on their teaching style, shown the first time a student meets them. */
    val bio: String,
    /** 1-2 characters shown in the avatar badge. */
    val initials: String,
    /** Avatar badge accent color. */
    val accentArgb: Int,
)

object Instructors {
    /** Structured, patient, precise — the "here's *why* this works" teacher for hard fundamentals. */
    val rowan = Instructor(
        id = "rowan",
        name = "Rowan",
        tagline = "Fundamentals, patiently.",
        bio = "Rowan teaches the way a good climbing instructor spots you on a wall: hands off until " +
            "you actually need it, and never surprised when something takes ten tries. Expect precise, " +
            "structured explanations of why a technique works, not just what to do — and zero judgment " +
            "for the attempt that doesn't land.",
        initials = "R",
        accentArgb = 0xFF5B7FDE.toInt(),
    )

    /** Energetic, practical, hands-first — the "try this and see what happens" teacher. */
    val marisol = Instructor(
        id = "marisol",
        name = "Marisol",
        tagline = "Color, light, and a good excuse to experiment.",
        bio = "Marisol treats every lesson like a small experiment you're running together — lots of " +
            "\"try this and see what happens,\" not much lecturing. Expect an energetic, practical style " +
            "aimed at getting your hands moving fast, with the theory arriving right when you need it " +
            "and not a moment sooner.",
        initials = "M",
        accentArgb = 0xFFE0863F.toInt(),
    )

    /** Calm, unhurried, forgiving — the teacher for wet media where nothing has to be perfect. */
    val dune = Instructor(
        id = "dune",
        name = "Dune",
        tagline = "Slow down. There are no mistakes here, just decisions.",
        bio = "Dune's whole approach is unhurried: big brushes, big shapes, and a running reminder " +
            "that you can paint over almost anything while it's still wet. If a lesson ever feels like " +
            "it's rushing you, it's not one of Dune's — good painting, wet media especially, happens at " +
            "its own pace.",
        initials = "D",
        accentArgb = 0xFF4B8B6B.toInt(),
    )

    /** Direct, confident, technical about letterforms and can-control — the street-art teacher. */
    val kai = Instructor(
        id = "kai",
        name = "Kai",
        tagline = "Letters first. Style comes from control, not chaos.",
        bio = "Kai treats a tag the way a typographer treats a typeface: every swoosh and flare is " +
            "a deliberate choice built on a legible letterform underneath, not random energy. Expect " +
            "direct, confident feedback and a real emphasis on fundamentals — bubble letters before " +
            "wildstyle, always — with zero pretension about who gets to learn this.",
        initials = "K",
        accentArgb = 0xFFD64545.toInt(),
    )

    val all: List<Instructor> = listOf(rowan, marisol, dune, kai)

    fun byId(id: String): Instructor? = all.firstOrNull { it.id == id }
}
