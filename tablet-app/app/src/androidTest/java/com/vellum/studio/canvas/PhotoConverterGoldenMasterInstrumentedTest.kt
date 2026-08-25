package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [PhotoConverter.convert] for real, end-to-end, against the small set of checked-in fixture
 * photos in [PhotoConverterGoldenMaster] -- the one half of that golden-master fixture that
 * actually re-executes the real algorithm (live OpenCV native calls, a real decoded [Bitmap]) and
 * so is the one that can catch a genuine regression in [PhotoConverter.Preset]'s tone-quantize
 * parameters. Must run on a real device or emulator (`connectedDebugAndroidTest`); a plain JVM
 * unit test process cannot load OpenCV's Android-only native `.so` binaries at all, which is why
 * [PhotoConverterGoldenMasterFixtureTest] (app/src/test, gates `testDebugUnitTest`) checks a
 * narrower, JVM-provable slice of this same fixture instead of re-running this logic itself.
 *
 * The fixture photos are bundled as this test module's own assets via
 * `android.sourceSets["androidTest"].assets.srcDirs("src/test/resources/photoconverter-golden")`
 * in app/build.gradle.kts -- the exact same bytes [PhotoConverterGoldenMasterFixtureTest] reads as
 * a plain classpath resource, checked into the repo exactly once.
 */
@RunWith(AndroidJUnit4::class)
class PhotoConverterGoldenMasterInstrumentedTest {

    private fun loadFixtureBitmap(fileName: String): Bitmap {
        // Deliberately the INSTRUMENTATION package's own context, not .targetContext (the app
        // under test, com.vellum.studio) -- these fixture photos are bundled as this androidTest
        // module's own assets (see app/build.gradle.kts's androidTest sourceSets block), not the
        // app's. Using .targetContext here fails with a real FileNotFoundException, confirmed by
        // actually hitting it: the app APK's AssetManager has no idea these files exist.
        val context = InstrumentationRegistry.getInstrumentation().context
        context.assets.open(fileName).use { stream ->
            return BitmapFactory.decodeStream(stream) ?: error("Failed to decode fixture asset '$fileName'")
        }
    }

    private fun assertMatchesGoldenMaster(fixture: PhotoConverterGoldenMaster.Fixture) {
        val bitmap = loadFixtureBitmap(fixture.fileName)
        val result = runBlocking { PhotoConverter.convert(bitmap, fixture.preset) }

        assertEquals(
            "regionCount for ${fixture.fileName} (${fixture.preset.label}) drifted from the recorded golden master -- " +
                "if this is a deliberate, reviewed change to PhotoConverter's tone-quantize parameters, update " +
                "PhotoConverterGoldenMaster with a fresh real on-device run; otherwise this is a real regression.",
            fixture.regionCount,
            result.regionCount,
        )
        assertEquals(
            "isPaintByNumberEligible for ${fixture.fileName} (${fixture.preset.label}) drifted from the recorded golden master.",
            fixture.isPaintByNumberEligible,
            result.isPaintByNumberEligible,
        )
    }

    @Test fun theNightWatch_simple_matchesGoldenMaster() {
        assertMatchesGoldenMaster(PhotoConverterGoldenMaster.THE_NIGHT_WATCH_SIMPLE)
    }

    @Test fun anatomyLesson_simple_matchesGoldenMaster() {
        assertMatchesGoldenMaster(PhotoConverterGoldenMaster.ANATOMY_LESSON_SIMPLE)
    }

    @Test fun whistlersMother_simple_matchesGoldenMaster() {
        assertMatchesGoldenMaster(PhotoConverterGoldenMaster.WHISTLERS_MOTHER_SIMPLE)
    }
}
