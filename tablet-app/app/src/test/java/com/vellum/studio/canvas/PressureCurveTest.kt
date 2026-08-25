package com.vellum.studio.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [applyPressureCurve]'s gamma math, plus the specific regression this whole test-module effort is
 * named for: [PressureCurvePreset.SOFT] and [PressureCurvePreset.FIRM]'s gamma values shipped
 * inverted earlier in this project (SOFT behaved FIRM and vice versa). The very first test below
 * asserts SOFT's gamma < LINEAR's gamma < FIRM's gamma -- exactly the check that would have caught
 * that regression before it ever reached a device.
 */
class PressureCurveTest {

    @Test fun `SOFT gamma is less than LINEAR gamma is less than FIRM gamma`() {
        // gamma < 1 bows the curve UP (x^gamma > x for x in (0,1)), reaching full effect with a
        // lighter touch -- "Soft". gamma > 1 bows it DOWN, needing real force -- "Firm". LINEAR's
        // gamma == 1 sits at the no-op midpoint. This is exactly the check that would have failed
        // against the previously-swapped values.
        assertTrue(
            "SOFT.gamma (${PressureCurvePreset.SOFT.gamma}) must be < LINEAR.gamma (${PressureCurvePreset.LINEAR.gamma})",
            PressureCurvePreset.SOFT.gamma < PressureCurvePreset.LINEAR.gamma,
        )
        assertTrue(
            "LINEAR.gamma (${PressureCurvePreset.LINEAR.gamma}) must be < FIRM.gamma (${PressureCurvePreset.FIRM.gamma})",
            PressureCurvePreset.LINEAR.gamma < PressureCurvePreset.FIRM.gamma,
        )
    }

    @Test fun `preset gammas stay reachable within the custom slider range`() {
        assertTrue(PressureCurvePreset.SOFT.gamma >= PressureCurveRange.MIN_GAMMA)
        assertTrue(PressureCurvePreset.FIRM.gamma <= PressureCurveRange.MAX_GAMMA)
    }

    @Test fun `gamma of 1 is an exact passthrough`() {
        for (p in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)) {
            assertEquals(p, applyPressureCurve(p, 1f), 0f)
        }
    }

    @Test fun `non-positive or NaN gamma is a safe passthrough`() {
        assertEquals(0.5f, applyPressureCurve(0.5f, 0f), 0f)
        assertEquals(0.5f, applyPressureCurve(0.5f, -2f), 0f)
        assertEquals(0.5f, applyPressureCurve(0.5f, Float.NaN), 0f)
    }

    @Test fun `SOFT bows the curve up -- a light touch reads as more than half effect`() {
        val result = applyPressureCurve(0.5f, PressureCurvePreset.SOFT.gamma)
        assertTrue("expected $result > 0.5", result > 0.5f)
    }

    @Test fun `FIRM bows the curve down -- half pressure reads as less than half effect`() {
        val result = applyPressureCurve(0.5f, PressureCurvePreset.FIRM.gamma)
        assertTrue("expected $result < 0.5", result < 0.5f)
    }

    @Test fun `matches x pow gamma for an arbitrary custom gamma`() {
        assertEquals(0.0625f, applyPressureCurve(0.25f, 2f), 1e-6f)
        assertEquals(0.125f, applyPressureCurve(0.5f, 3f), 1e-6f)
    }

    @Test fun `raw pressure is clamped to 0 to 1 before the curve is applied`() {
        assertEquals(0f, applyPressureCurve(-0.5f, 2f), 0f)
        assertEquals(1f, applyPressureCurve(1.5f, 2f), 0f)
    }

    @Test fun `curve output stays within 0 to 1`() {
        for (gamma in listOf(0.3f, 0.55f, 1.8f, 3f)) {
            for (p in listOf(0f, 0.3f, 0.6f, 1f)) {
                val result = applyPressureCurve(p, gamma)
                assertTrue("gamma=$gamma p=$p result=$result out of range", result in 0f..1f)
            }
        }
    }
}
