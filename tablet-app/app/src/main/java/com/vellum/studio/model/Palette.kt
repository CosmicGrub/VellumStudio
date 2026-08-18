package com.vellum.studio.model

import kotlinx.serialization.Serializable

/** A named set of swatches. Built-in palettes are curated and read-only; custom ones are user-owned. */
@Serializable
data class Palette(
    val id: String,
    val name: String,
    val colors: List<Int> = emptyList(),
    val isBuiltIn: Boolean = false,
)

/** Curated starter palettes, always available, never persisted or user-editable. */
object BuiltInPalettes {
    val Classic = Palette(
        "builtin_classic", "Classic",
        listOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
            0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(),
            0xFF6D4C41.toInt(), 0xFF757575.toInt(), 0xFFD81B60.toInt(), 0xFF00ACC1.toInt(),
        ),
        isBuiltIn = true,
    )

    val Pastels = Palette(
        "builtin_pastels", "Pastels",
        listOf(
            0xFFFFD1DC.toInt(), 0xFFFFE4B5.toInt(), 0xFFFFF9B0.toInt(), 0xFFC8F7C5.toInt(),
            0xFFB5EAEA.toInt(), 0xFFB5B9FF.toInt(), 0xFFE0BBE4.toInt(), 0xFFFFC9DE.toInt(),
            0xFFD4A5A5.toInt(), 0xFFC9E4DE.toInt(),
        ),
        isBuiltIn = true,
    )

    val EarthTones = Palette(
        "builtin_earth", "Earth Tones",
        listOf(
            0xFF7B5E43.toInt(), 0xFFA47551.toInt(), 0xFFC9A66B.toInt(), 0xFF6B8E23.toInt(),
            0xFF556B2F.toInt(), 0xFF8B4513.toInt(), 0xFFD2B48C.toInt(), 0xFF4A5D23.toInt(),
            0xFF9C7A5B.toInt(), 0xFF3E2C1C.toInt(),
        ),
        isBuiltIn = true,
    )

    val JewelTones = Palette(
        "builtin_jewel", "Jewel Tones",
        listOf(
            0xFF046307.toInt(), 0xFF0F52BA.toInt(), 0xFF9B111E.toInt(), 0xFF6A0DAD.toInt(),
            0xFF50C878.toInt(), 0xFFE0115F.toInt(), 0xFFFFC200.toInt(), 0xFF002D62.toInt(),
            0xFF4B0082.toInt(), 0xFF8A0303.toInt(),
        ),
        isBuiltIn = true,
    )

    val Grayscale = Palette(
        "builtin_grayscale", "Grayscale",
        listOf(
            0xFF000000.toInt(), 0xFF1A1A1A.toInt(), 0xFF333333.toInt(), 0xFF4D4D4D.toInt(),
            0xFF666666.toInt(), 0xFF808080.toInt(), 0xFF999999.toInt(), 0xFFB3B3B3.toInt(),
            0xFFCCCCCC.toInt(), 0xFFE6E6E6.toInt(), 0xFFFFFFFF.toInt(),
        ),
        isBuiltIn = true,
    )

    val SkinTones = Palette(
        "builtin_skin", "Skin Tones",
        listOf(
            0xFFFFDFC4.toInt(), 0xFFF0C8A0.toInt(), 0xFFE1A87D.toInt(), 0xFFC68863.toInt(),
            0xFFA5694A.toInt(), 0xFF8D5524.toInt(), 0xFF6B4226.toInt(), 0xFF4A2C17.toInt(),
        ),
        isBuiltIn = true,
    )

    val Vivid = Palette(
        "builtin_vivid", "Vivid",
        listOf(
            0xFFFF1744.toInt(), 0xFFFF6D00.toInt(), 0xFFFFEA00.toInt(), 0xFF00E676.toInt(),
            0xFF00E5FF.toInt(), 0xFF2979FF.toInt(), 0xFFD500F9.toInt(), 0xFFF50057.toInt(),
            0xFF76FF03.toInt(), 0xFFFF3D00.toInt(),
        ),
        isBuiltIn = true,
    )

    val all = listOf(Classic, Pastels, EarthTones, JewelTones, Grayscale, SkinTones, Vivid)
}
