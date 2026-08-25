package com.vellum.studio.canvas

import android.app.Application
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The plain-JVM half of the [PhotoConverterGoldenMaster] regression fixture -- see that object's
 * own doc for the full picture, and [PhotoConverterGoldenMasterInstrumentedTest] (app/src/androidTest)
 * for the half that actually re-runs [PhotoConverter.convert] on a real device.
 *
 * [PhotoConverter.convert] itself cannot run here: [OpenCVLoader.initLocal] loads an Android-only
 * native `.so` that has no build for this project's dev-host JVM (or, structurally, for ANY plain
 * JVM/Robolectric process -- Robolectric fakes the *framework*, it doesn't run arbitrary
 * third-party AAR native code for a foreign ABI) -- confirmed by [PhotoConverterEligibilityTest]'s
 * own doc comment and by this project's actual dev machine (Windows) being unable to load an
 * Android arm64 `.so` under any circumstance. So this test checks what a JVM process genuinely
 * *can* verify about the fixture without touching OpenCV:
 *  1. Every fixture photo referenced by [PhotoConverterGoldenMaster] is present, decodable, and a
 *     real, reasonably-sized photo (not an empty or corrupted placeholder) -- via Robolectric's
 *     real, pixel-accurate [BitmapFactory], same justification as [RegionAnalyzerTest].
 *  2. The recorded [PhotoConverterGoldenMaster.Fixture.isPaintByNumberEligible] flag is still
 *     exactly what [PhotoConverter.eligibleForPaintByNumber] (itself pure, non-OpenCV logic)
 *     computes from the recorded region count -- so a change to the eligibility THRESHOLD alone
 *     (e.g. [PhotoConverter.MIN_REGIONS_FOR_PAINT_BY_NUMBER]) is caught here even without a device,
 *     even though a change to the tone-quantize parameters that changes the region count itself is
 *     only caught by the instrumented half.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PhotoConverterGoldenMasterFixtureTest {

    @Test fun `every golden-master fixture photo is present, decodable, and non-trivial`() {
        PhotoConverterGoldenMaster.all.forEach { fixture ->
            val stream = javaClass.classLoader!!.getResourceAsStream("photoconverter-golden/${fixture.fileName}")
            assertTrue("fixture resource missing: ${fixture.fileName}", stream != null)

            val bitmap = BitmapFactory.decodeStream(stream)
            assertTrue("failed to decode fixture bitmap: ${fixture.fileName}", bitmap != null)
            assertTrue(
                "fixture ${fixture.fileName} decoded suspiciously small (${bitmap!!.width}x${bitmap.height})",
                bitmap.width >= 200 && bitmap.height >= 200,
            )
        }
    }

    @Test fun `recorded eligibility still matches the pure threshold logic for the recorded region count`() {
        PhotoConverterGoldenMaster.all.forEach { fixture ->
            assertEquals(
                "recorded isPaintByNumberEligible for ${fixture.fileName} no longer matches " +
                    "PhotoConverter.eligibleForPaintByNumber(${fixture.regionCount}) -- either the threshold " +
                    "changed (update this fixture deliberately) or the two have drifted apart by accident.",
                PhotoConverter.eligibleForPaintByNumber(fixture.regionCount),
                fixture.isPaintByNumberEligible,
            )
        }
    }

    @Test fun `fixture set has no duplicate file names and is non-empty`() {
        val names = PhotoConverterGoldenMaster.all.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
        assertTrue(PhotoConverterGoldenMaster.all.isNotEmpty())
    }
}
