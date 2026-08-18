package com.vellum.studio.canvas

import kotlinx.serialization.Serializable

/** Which physical tool a brush models; [BrushCategory.ERASER] shares the same stamping pipeline with a soft alpha mask. */
@Serializable
enum class BrushCategory { PENCIL, INK, FINELINER, MARKER, HIGHLIGHTER, WATERCOLOR, PASTEL, AIRBRUSH, FLAT_FILL, ERASER, GRAFFITI }

/**
 * A stamping brush. Strokes are built by walking the smoothed input path and stamping a soft
 * radial tip every [spacing] * size along it, modulating size/opacity by pen pressure and tilt.
 *
 * @param hardness 0 = very soft falloff (airbrush-like edge), 1 = crisp hard edge (ink-like edge).
 * @param spacing stamp pitch as a fraction of the current stamp diameter; smaller = smoother, denser stroke.
 * @param minSizeFactor the stamp diameter at zero pressure, as a fraction of [baseSizePx].
 * @param pressureToOpacity how strongly pressure modulates per-stamp opacity (0 = pressure ignored).
 * @param tiltToSize how strongly stylus tilt widens the stamp (nib-like shading), 0..1.
 * @param opacityJitter per-dab random opacity variance (0 = perfectly uniform, higher = grainier/
 *   more textured coverage) — this is what gives pastel/crayon and watercolor their organic,
 *   non-uniform feel instead of a flat airbrush-style wash.
 * @param buildUp when true (airbrush/marker/watercolor/highlighter) overlapping stamps within one
 *   stroke keep accumulating opacity; when false (pencil/ink/pastel/flat-fill) the whole stroke is
 *   capped by [strokeOpacityCap] via an offscreen layer.
 * @param pigmentMixing when true, each dab composites with `BlendMode.MULTIPLY` instead of plain
 *   `SRC_OVER` — physically, a translucent glaze of pigment over existing color multiplies the light
 *   passing through both layers rather than just occluding what's underneath, which is why real
 *   watercolor and soft pastel darken and shift hue where strokes cross instead of one color simply
 *   sitting on top of the other. `MULTIPLY` degrades to ordinary painting on blank/transparent canvas
 *   (the W3C compositing formula's blend term is scaled by destination alpha, so it vanishes to
 *   nothing where there's nothing to mix with yet) — it only kicks in where there's real overlap.
 * @param wetness 0 = commits crisp (the stroke's dab shapes stay exactly as stamped), higher =
 *   genuinely softens/spreads the stroke's edges at commit time via [CanvasEngine.flattenScratchOnto]
 *   (a real, one-time [android.graphics.BlurMaskFilter] blur of the alpha mask, not a jitter
 *   approximation) — this is the actual diffusion/bleed a wet medium like watercolor has that
 *   opacity jitter alone can't reproduce, since jitter varies coverage per-dab but never spreads a
 *   dab's own edge past where it was stamped.
 */
@Serializable
data class Brush(
    val id: String,
    val name: String,
    val category: BrushCategory,
    val baseSizePx: Float,
    val minSizeFactor: Float = 0.25f,
    val hardness: Float = 0.85f,
    val spacing: Float = 0.12f,
    val baseOpacity: Float = 1f,
    val pressureToSize: Float = 0.7f,
    val pressureToOpacity: Float = 0.5f,
    val tiltToSize: Float = 0f,
    val jitter: Float = 0f,
    val opacityJitter: Float = 0f,
    val buildUp: Boolean = false,
    val strokeOpacityCap: Float = 1f,
    val pigmentMixing: Boolean = false,
    val wetness: Float = 0f,
)

object BrushPresets {
    val Pencil = Brush(
        id = "pencil",
        name = "Pencil",
        category = BrushCategory.PENCIL,
        baseSizePx = 10f,
        minSizeFactor = 0.35f,
        hardness = 0.55f,
        spacing = 0.08f,
        baseOpacity = 0.92f,
        pressureToSize = 0.5f,
        pressureToOpacity = 0.6f,
        // Real graphite lays flatter and shades broader the more you tilt it — the classic
        // pencil-shading technique. Highest tilt response of the hard-tip brushes on purpose.
        tiltToSize = 0.35f,
        jitter = 0.35f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val InkPen = Brush(
        id = "ink_pen",
        name = "Ink Pen",
        category = BrushCategory.INK,
        baseSizePx = 8f,
        minSizeFactor = 0.2f,
        hardness = 1f,
        spacing = 0.1f,
        baseOpacity = 1f,
        pressureToSize = 0.8f,
        pressureToOpacity = 0.1f,
        tiltToSize = 0.15f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val Fineliner = Brush(
        id = "fineliner",
        name = "Fineliner",
        category = BrushCategory.FINELINER,
        baseSizePx = 4f,
        minSizeFactor = 0.7f,
        hardness = 1f,
        spacing = 0.08f,
        baseOpacity = 1f,
        // A rigid plastic/metal fineliner tip barely deforms — deliberately low pressure and tilt
        // response so the line stays a near-consistent width, which is exactly why people reach for
        // one over a brush pen when they want controlled, even detail work.
        pressureToSize = 0.25f,
        pressureToOpacity = 0.05f,
        tiltToSize = 0.05f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val FeltMarker = Brush(
        id = "felt_marker",
        name = "Marker",
        category = BrushCategory.MARKER,
        baseSizePx = 26f,
        minSizeFactor = 0.85f,
        hardness = 0.9f,
        spacing = 0.06f,
        baseOpacity = 0.55f,
        pressureToSize = 0.2f,
        pressureToOpacity = 0.25f,
        // A felt/chisel tip genuinely widens when you roll it onto its flat, the way a real
        // calligraphy or brush marker does.
        tiltToSize = 0.25f,
        buildUp = true,
        // strokeOpacityCap is intentionally left at the default (1f): it's only ever consumed by
        // CanvasEngine.flattenScratchOnto, which only runs for non-buildUp brushes. A buildUp brush
        // stamps straight onto the layer with no per-pixel opacity ceiling, so setting this to
        // anything but 1f here would be dead configuration that misleadingly implies a cap exists.
    )

    val Highlighter = Brush(
        id = "highlighter",
        name = "Highlighter",
        category = BrushCategory.HIGHLIGHTER,
        baseSizePx = 46f,
        minSizeFactor = 0.95f,
        hardness = 1f,
        spacing = 0.1f,
        baseOpacity = 0.35f,
        pressureToSize = 0.05f,
        pressureToOpacity = 0.1f,
        // Highlighters are the most dramatically chisel-shaped tip of any tool here — the width
        // swing from tilting one is bigger and more obvious than any other brush.
        tiltToSize = 0.45f,
        buildUp = true,
        strokeOpacityCap = 1f,
    )

    val Watercolor = Brush(
        id = "watercolor",
        name = "Watercolor",
        category = BrushCategory.WATERCOLOR,
        baseSizePx = 70f,
        minSizeFactor = 0.6f,
        hardness = 0.15f,
        spacing = 0.2f,
        baseOpacity = 0.28f,
        pressureToSize = 0.5f,
        pressureToOpacity = 0.6f,
        // A loaded round brush spreads noticeably wider laid over at an angle.
        tiltToSize = 0.2f,
        jitter = 0.15f,
        opacityJitter = 0.2f,
        buildUp = true,
        strokeOpacityCap = 1f,
        pigmentMixing = true,
        // The real diffusion/bleed a wet medium has -- softens the committed stroke's edges via
        // an actual blur at commit time, not just a jitter approximation. Highest of any brush,
        // since watercolor is the wettest medium modeled here.
        wetness = 0.5f,
    )

    val Pastel = Brush(
        id = "pastel",
        name = "Pastel",
        category = BrushCategory.PASTEL,
        baseSizePx = 34f,
        minSizeFactor = 0.7f,
        hardness = 0.45f,
        spacing = 0.1f,
        baseOpacity = 0.7f,
        pressureToSize = 0.3f,
        pressureToOpacity = 0.35f,
        // Tilting a pastel/crayon stick onto its side to lay down a broad swath is the whole
        // technique — give it the strongest tilt response after the highlighter's chisel tip.
        tiltToSize = 0.4f,
        jitter = 0.5f,
        opacityJitter = 0.35f,
        buildUp = false,
        strokeOpacityCap = 0.95f,
        pigmentMixing = true,
        // A dry medium, not a wet one -- pastel pigment does spread slightly when a stroke is
        // laid down (the powder isn't perfectly crisp-edged the way ink is), but nowhere near
        // watercolor's real bleed. A small fraction of Watercolor's wetness on purpose.
        wetness = 0.12f,
    )

    val SoftAirbrush = Brush(
        id = "airbrush",
        name = "Airbrush",
        category = BrushCategory.AIRBRUSH,
        baseSizePx = 90f,
        minSizeFactor = 0.5f,
        hardness = 0.05f,
        spacing = 0.18f,
        baseOpacity = 0.18f,
        pressureToSize = 0.6f,
        pressureToOpacity = 0.8f,
        // An airbrush never touches the surface — tilt has no physical effect on a spray cone
        // pointed straight at the page, so this deliberately stays at 0 unlike every tip-contact tool.
        tiltToSize = 0f,
        buildUp = true,
        strokeOpacityCap = 1f,
    )

    val FlatFill = Brush(
        id = "flat_fill",
        name = "Flat Brush",
        category = BrushCategory.FLAT_FILL,
        baseSizePx = 64f,
        minSizeFactor = 0.92f,
        hardness = 1f,
        spacing = 0.08f,
        baseOpacity = 1f,
        pressureToSize = 0.1f,
        pressureToOpacity = 0.05f,
        tiltToSize = 0.15f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val FlatEraser = Brush(
        id = "eraser",
        name = "Eraser",
        category = BrushCategory.ERASER,
        baseSizePx = 40f,
        minSizeFactor = 0.4f,
        hardness = 0.6f,
        spacing = 0.1f,
        baseOpacity = 1f,
        pressureToSize = 0.6f,
        pressureToOpacity = 0.3f,
        tiltToSize = 0.2f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    // The eraser lineup mirrors the drawing brushes' own variety in physical character - hardness,
    // size, tilt response, and how fully one stroke commits - the same properties that give the
    // drawing tools their distinct feel. All stay buildUp = false (routed through the shared
    // scratch + DST_OUT-at-flatten mechanism, see StrokeRenderer/CanvasEngine.flattenScratchOnto)
    // for a consistent, already-correct erasing pipeline across every variant.

    val PrecisionEraser = Brush(
        id = "eraser_precision",
        name = "Precision",
        category = BrushCategory.ERASER,
        baseSizePx = 14f,
        minSizeFactor = 0.3f,
        // Small and crisp on purpose - this is the "fix one line" eraser, not the "clear an area"
        // one, so it deliberately has almost no tilt-driven widening.
        hardness = 0.9f,
        spacing = 0.08f,
        baseOpacity = 1f,
        pressureToSize = 0.6f,
        pressureToOpacity = 0.2f,
        tiltToSize = 0.1f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val SoftEraser = Brush(
        id = "eraser_soft",
        name = "Soft",
        category = BrushCategory.ERASER,
        baseSizePx = 50f,
        minSizeFactor = 0.5f,
        // Very low hardness = a wide, graduated falloff - for gently lifting/softening an edge
        // rather than punching a hard-edged hole in it.
        hardness = 0.2f,
        spacing = 0.12f,
        baseOpacity = 1f,
        pressureToSize = 0.5f,
        pressureToOpacity = 0.4f,
        tiltToSize = 0.15f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val HardEraser = Brush(
        id = "eraser_hard",
        name = "Hard Block",
        category = BrushCategory.ERASER,
        baseSizePx = 70f,
        minSizeFactor = 0.85f,
        // A big vinyl-block eraser: crisp edge, barely narrows at low pressure, chisels wider on
        // tilt the way a wide flat block does when you lay it over - built to clear a lot, fast.
        hardness = 1f,
        spacing = 0.06f,
        baseOpacity = 1f,
        pressureToSize = 0.15f,
        pressureToOpacity = 0.1f,
        tiltToSize = 0.2f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val KneadedEraser = Brush(
        id = "eraser_kneaded",
        name = "Kneaded",
        category = BrushCategory.ERASER,
        baseSizePx = 36f,
        minSizeFactor = 0.6f,
        hardness = 0.5f,
        spacing = 0.11f,
        baseOpacity = 1f,
        pressureToSize = 0.35f,
        pressureToOpacity = 0.45f,
        tiltToSize = 0.3f,
        // A real kneaded eraser lifts graphite gradually and unevenly, not in one flat wipe -
        // jitter/opacityJitter give it that grainy, textured touch, and the capped strokeOpacityCap
        // means a single stroke only partially lifts what's underneath. Going over the same spot
        // again lifts more, exactly like working a kneaded eraser over a shaded area in passes.
        jitter = 0.4f,
        opacityJitter = 0.4f,
        buildUp = false,
        strokeOpacityCap = 0.55f,
    )

    val FadeEraser = Brush(
        id = "eraser_fade",
        name = "Fade",
        category = BrushCategory.ERASER,
        baseSizePx = 90f,
        minSizeFactor = 0.5f,
        // No physical tip touches the page here any more than an airbrush's spray does, so - like
        // SoftAirbrush - tilt has no effect. Built for gently fading/vignetting an edge over several
        // passes, never for a clean single-stroke wipe (strokeOpacityCap keeps it well short of that).
        hardness = 0.05f,
        spacing = 0.18f,
        baseOpacity = 1f,
        pressureToSize = 0.6f,
        pressureToOpacity = 0.8f,
        tiltToSize = 0f,
        buildUp = false,
        strokeOpacityCap = 0.35f,
    )

    // Graffiti suite: not confined to a dedicated "graffiti mode" -- these are just five more
    // entries in the same shared brush lineup, usable in any project like every other preset.
    // Modeled on the actual toolkit street-art technique layers on top of, in the order a piece
    // is usually built: sketch -> outline (Fat Cap) -> fill (Spray Can) -> lettering angle/style
    // (Wildstyle Chisel) -> texture accents (Drip, Stencil).

    val SprayCan = Brush(
        id = "graffiti_spray",
        name = "Spray Can",
        category = BrushCategory.GRAFFITI,
        baseSizePx = 60f,
        minSizeFactor = 0.7f,
        // Soft-ish but grainy, not smooth like the Airbrush -- a real rattle-can's cone has
        // visible speckle/texture at the edge, not a clean gradient falloff.
        hardness = 0.35f,
        spacing = 0.14f,
        baseOpacity = 0.5f,
        pressureToSize = 0.4f,
        pressureToOpacity = 0.5f,
        // Aerosol spray direction, not a tip touching the surface -- no tilt response, same
        // reasoning as SoftAirbrush/FadeEraser.
        tiltToSize = 0f,
        jitter = 0.25f,
        opacityJitter = 0.4f,
        buildUp = true,
        strokeOpacityCap = 1f,
    )

    val FatCapOutline = Brush(
        id = "graffiti_fatcap",
        name = "Fat Cap",
        category = BrushCategory.GRAFFITI,
        baseSizePx = 34f,
        minSizeFactor = 0.9f,
        // A fat-cap nozzle lays a bold, consistent line for outlines/blockouts -- deliberately
        // low pressure response so the outline stays a steady, confident width throughout,
        // the same design reasoning as Fineliner's controlled-line intent.
        hardness = 0.95f,
        spacing = 0.07f,
        baseOpacity = 1f,
        pressureToSize = 0.15f,
        pressureToOpacity = 0.05f,
        tiltToSize = 0.1f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val WildstyleChisel = Brush(
        id = "graffiti_wildstyle",
        name = "Wildstyle Chisel",
        category = BrushCategory.GRAFFITI,
        baseSizePx = 30f,
        minSizeFactor = 0.6f,
        hardness = 0.85f,
        spacing = 0.08f,
        baseOpacity = 1f,
        pressureToSize = 0.3f,
        pressureToOpacity = 0.1f,
        // The whole point of a chisel tip for lettering: rolling the angle changes the stroke
        // width dramatically, the same mechanic that gives calligraphy its thick/thin swing --
        // highest tilt response of any non-eraser brush, on purpose, for exactly that letterform
        // control.
        tiltToSize = 0.5f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val Drip = Brush(
        id = "graffiti_drip",
        name = "Drip",
        category = BrushCategory.GRAFFITI,
        baseSizePx = 22f,
        minSizeFactor = 0.5f,
        hardness = 0.5f,
        spacing = 0.22f,
        baseOpacity = 0.85f,
        pressureToSize = 0.5f,
        pressureToOpacity = 0.3f,
        tiltToSize = 0.1f,
        // High jitter/opacityJitter is what actually gives this brush its uneven, pooling-paint
        // character -- there's no gravity/fluid simulation in this engine (same honesty as the
        // Watercolor brush's own lesson content), so "drip" here means irregular, organic-feeling
        // coverage you drag downward yourself, not an automatic trickle.
        jitter = 0.45f,
        opacityJitter = 0.5f,
        buildUp = true,
        strokeOpacityCap = 1f,
    )

    val Stencil = Brush(
        id = "graffiti_stencil",
        name = "Stencil",
        category = BrushCategory.GRAFFITI,
        baseSizePx = 50f,
        minSizeFactor = 0.95f,
        // Totally uniform on purpose -- a real stencil doesn't respond to how hard you spray
        // through it, just the cutout's crisp edge. Zero pressure/tilt response, unlike every
        // other brush in the app.
        hardness = 1f,
        spacing = 0.06f,
        baseOpacity = 1f,
        pressureToSize = 0f,
        pressureToOpacity = 0f,
        tiltToSize = 0f,
        buildUp = false,
        strokeOpacityCap = 1f,
    )

    val all = listOf(
        Pencil, InkPen, Fineliner, FeltMarker, Highlighter, Watercolor, Pastel, SoftAirbrush, FlatFill,
        FlatEraser, PrecisionEraser, SoftEraser, HardEraser, KneadedEraser, FadeEraser,
        SprayCan, FatCapOutline, WildstyleChisel, Drip, Stencil,
    )

    fun byId(id: String): Brush = all.firstOrNull { it.id == id } ?: Pencil
}
