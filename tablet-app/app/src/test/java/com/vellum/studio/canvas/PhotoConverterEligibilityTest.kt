package com.vellum.studio.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PhotoConverter.convert] itself needs a live OpenCV native call chain plus a real
 * [android.graphics.Bitmap], neither available to a plain JVM unit test without a lot of
 * scaffolding -- but the paint-by-number ELIGIBILITY decision itself is a one-line threshold over
 * an already-known `Int` region count, cleanly separable from that entanglement (see
 * [PhotoConverter.eligibleForPaintByNumber]), so that threshold is what this tests directly.
 */
class PhotoConverterEligibilityTest {

    @Test fun `fewer than 3 regions is not eligible`() {
        assertFalse(PhotoConverter.eligibleForPaintByNumber(0))
        assertFalse(PhotoConverter.eligibleForPaintByNumber(1))
        assertFalse(PhotoConverter.eligibleForPaintByNumber(2))
    }

    @Test fun `3 or more regions is eligible`() {
        assertTrue(PhotoConverter.eligibleForPaintByNumber(3))
        assertTrue(PhotoConverter.eligibleForPaintByNumber(4))
        assertTrue(PhotoConverter.eligibleForPaintByNumber(50))
    }
}
