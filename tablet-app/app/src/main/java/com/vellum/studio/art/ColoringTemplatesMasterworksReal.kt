package com.vellum.studio.art

import android.graphics.Paint
import android.graphics.RectF
import com.vellum.studio.VellumApp
import com.vellum.studio.util.AssetBitmapCache

/**
 * Real masterwork coloring templates. Unlike ColoringTemplatesMasterworks.kt's hand-authored
 * *interpretations*, these are accurate line art derived from real public-domain painting scans
 * (all confirmed PD/PD-Art on Wikimedia Commons) via tools/masterart_pipeline -- see its README
 * for the source list and the image-processing technique used per work.
 *
 * Each `draw` closure just decodes the bundled line-art PNG (via AssetBitmapCache) and
 * letterbox-fits it into the square project canvas. That's the *entire* integration surface:
 * ColoringTemplate's shape (`draw: (Canvas, Int) -> Unit`) is exactly what every other call site
 * already consumes generically -- ColoringBookScreen's gallery thumbnailing,
 * ProjectRepository.createFromTemplate's rasterize-to-a-locked-layer flow, paint-by-number's
 * region detector (works on any sufficiently-opaque, closed alpha linework, procedural or not),
 * and printing -- so none of those needed to change.
 */
object ColoringTemplatesMasterworksReal {
    val templates: List<ColoringTemplate> = listOf(
        realTemplate("masterwork-real-starry-night", "The Starry Night (Van Gogh)", "starry_night_lineart.png"),
        realTemplate("masterwork-real-mona-lisa", "Mona Lisa (da Vinci)", "mona_lisa_lineart.png"),
        realTemplate("masterwork-real-girl-pearl-earring", "Girl with a Pearl Earring (Vermeer)", "girl_pearl_earring_lineart.png"),
        realTemplate("masterwork-real-great-wave", "The Great Wave off Kanagawa (Hokusai)", "great_wave_lineart.png"),
        realTemplate("masterwork-real-milkmaid", "The Milkmaid (Vermeer)", "the_milkmaid_lineart.png"),
        realTemplate("masterwork-real-cafe-terrace", "Cafe Terrace at Night (Van Gogh)", "cafe_terrace_at_night_lineart.png"),
        realTemplate("masterwork-real-wanderer-fog", "Wanderer above the Sea of Fog (Friedrich)", "wanderer_sea_of_fog_lineart.png"),
        realTemplate("masterwork-real-night-watch", "The Night Watch (Rembrandt)", "the_night_watch_lineart.png"),
        realTemplate("masterwork-real-whistlers-mother", "Whistler's Mother", "whistlers_mother_lineart.png"),
        realTemplate("masterwork-real-anatomy-lesson", "The Anatomy Lesson of Dr Tulp (Rembrandt)", "anatomy_lesson_dr_tulp_lineart.png"),
        realTemplate("masterwork-real-girl-red-hat", "Girl with a Red Hat (Vermeer)", "girl_with_red_hat_lineart.png"),
    )

    private fun realTemplate(id: String, name: String, assetFileName: String): ColoringTemplate {
        val referenceFileName = assetFileName.replace("_lineart.png", "_reference.jpg")
        return ColoringTemplate(
            id = id,
            name = name,
            category = "Masterworks",
            referenceAssetPath = "masterworks/$referenceFileName",
            draw = { canvas, size ->
                val bitmap = AssetBitmapCache.get(VellumApp.instance, "masterworks/$assetFileName")
                val s = size.toFloat()
                val margin = s * 0.05f
                val available = s - margin * 2f
                val scale = minOf(available / bitmap.width, available / bitmap.height)
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                val left = (s - drawW) / 2f
                val top = (s - drawH) / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
            },
        )
    }
}
