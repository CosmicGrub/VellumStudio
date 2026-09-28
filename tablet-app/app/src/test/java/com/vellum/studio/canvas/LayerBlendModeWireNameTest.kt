package com.vellum.studio.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the strings [LayerBlendMode] writes into every project's metadata.json.
 *
 * The persisted value used to be the on-screen label, so relabeling, localizing or moving a label to
 * a string resource would have silently reset every saved layer to Normal -- and the next autosave
 * would have written that back, making the loss permanent. [LayerBlendMode.wireName] is now frozen
 * separately from [LayerBlendMode.label] and from the constant's own name, and THIS test is the
 * tripwire: a rename of an enum constant, an edited wire string, a reordered/added/removed mode all
 * fail here, at build time, instead of corrupting projects on someone's tablet.
 *
 * Robolectric only because the enum's constructor references [android.graphics.BlendMode], which
 * needs the real framework classes; nothing here renders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LayerBlendModeWireNameTest {

    /**
     * (enum constant name, frozen wire string), in declaration order. The wire strings are exactly
     * what v0.2.x wrote to disk (the old display labels). Do NOT "fix" a failure here by editing this
     * list to match new code -- that is the corruption this test exists to stop. A genuinely new
     * blend mode APPENDS a row with a new, never-reused wire string.
     */
    private val frozen = listOf(
        "NORMAL" to "Normal",
        "MULTIPLY" to "Multiply",
        "SCREEN" to "Screen",
        "OVERLAY" to "Overlay",
        "DARKEN" to "Darken",
        "LIGHTEN" to "Lighten",
        "COLOR_DODGE" to "Color Dodge",
        "COLOR_BURN" to "Color Burn",
        "HARD_LIGHT" to "Hard Light",
        "SOFT_LIGHT" to "Soft Light",
        "DIFFERENCE" to "Difference",
        "EXCLUSION" to "Exclusion",
        "HUE" to "Hue",
        "SATURATION" to "Saturation",
        "COLOR" to "Color",
        "LUMINOSITY" to "Luminosity",
    )

    @Test
    fun `every blend mode's wire name is pinned to its historical persisted string`() {
        assertEquals(
            "a blend mode was renamed, reordered, added or removed, or a wireName was edited -- saved projects would break",
            frozen,
            LayerBlendMode.entries.map { it.name to it.wireName },
        )
        assertEquals(16, LayerBlendMode.entries.size)
    }

    @Test
    fun `wire names are unique so no two modes can decode to the same layer`() {
        assertEquals(LayerBlendMode.entries.size, LayerBlendMode.entries.map { it.wireName }.toSet().size)
    }

    @Test
    fun `every wire name decodes back to its own mode`() {
        for (mode in LayerBlendMode.entries) {
            assertSame(mode, LayerBlendMode.fromWireName(mode.wireName))
        }
        for ((_, wire) in frozen) {
            assertEquals(wire, LayerBlendMode.fromWireName(wire)?.wireName)
        }
    }

    @Test
    fun `an unknown string is reported as null rather than silently becoming a mode`() {
        assertNull(LayerBlendMode.fromWireName("Vivid Light"))
        assertNull(LayerBlendMode.fromWireName(""))
        // Case and spacing are part of the frozen format.
        assertNull(LayerBlendMode.fromWireName("multiply"))
        assertNull(LayerBlendMode.fromWireName("ColorDodge"))
    }

    @Test
    fun `the enum constant name is not the wire name so renaming a constant cannot change the format`() {
        // "COLOR_DODGE" is what the constant is called in code; it was never what was persisted.
        assertNull(LayerBlendMode.fromWireName("COLOR_DODGE"))
        assertNull(LayerBlendMode.fromWireName("HARD_LIGHT"))
    }
}
