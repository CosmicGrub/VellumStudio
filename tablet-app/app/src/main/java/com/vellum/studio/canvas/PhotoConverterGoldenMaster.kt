package com.vellum.studio.canvas

/**
 * Checked-in golden-master values for [PhotoConverter]'s real, on-device output against a small,
 * fixed set of real test photos -- the regression fixture that keeps [PhotoConverter] (this
 * project's runtime Kotlin/OpenCV port) and tools/masterart_pipeline/generate.py (the original,
 * offline Python implementation of the same tone-quantize-and-contour technique) honest against
 * each other and against themselves over time. See:
 *  - [PhotoConverterGoldenMasterInstrumentedTest] (app/src/androidTest) -- actually RE-RUNS
 *    [PhotoConverter.convert] against these exact fixture photos on a real device and asserts the
 *    result still matches the numbers below. This is the half that can catch a real regression in
 *    [PhotoConverter]'s tone-quantize parameters, because it's the only half that can actually
 *    execute OpenCV's native code -- [OpenCVLoader.initLocal] loads Android-only native `.so`
 *    binaries that cannot load in a plain JVM/Robolectric test process (confirmed: this fails
 *    exactly the same way on this project's Windows dev host as it would on any other non-Android
 *    JVM). Run it (`connectedDebugAndroidTest`) any time [PhotoConverter.Preset]'s tone-quantize
 *    parameters change.
 *  - [PhotoConverterGoldenMasterFixtureTest] (app/src/test) -- the plain JVM/Robolectric half that
 *    actually gates `testDebugUnitTest`. It can't re-run OpenCV, so it checks what a JVM process
 *    genuinely can: the fixture photos are present, decodable, and reasonably sized, and the
 *    eligibility flag recorded below is still exactly what [PhotoConverter.eligibleForPaintByNumber]
 *    (itself pure, non-OpenCV logic) computes from the recorded region count -- so a change to the
 *    eligibility THRESHOLD, at least, is caught even without a device.
 *
 * These numbers are NOT estimated, guessed, or hand-picked to look good -- they were captured by
 * actually running [PhotoConverter.convert] with [PhotoConverter.Preset.SIMPLE] against each real
 * fixture photo on a real connected device (R52X101MB6W, Galaxy Tab S9 FE) on 2026-08-25, via
 * exactly [PhotoConverterGoldenMasterInstrumentedTest] itself. If a future, deliberate change to
 * [PhotoConverter] legitimately changes these numbers, update them here as part of that change,
 * with a fresh real on-device run backing the new values -- never edit them to make a failing test
 * pass without re-running the real conversion.
 */
object PhotoConverterGoldenMaster {

    data class Fixture(
        /** Asset/resource file name -- same bytes shared by both the JVM and instrumented halves of this fixture; see app/build.gradle.kts's androidTest sourceSets block. */
        val fileName: String,
        val preset: PhotoConverter.Preset,
        val regionCount: Int,
        val isPaintByNumberEligible: Boolean,
    )

    /** A busy, crowded Rembrandt group scene (chiaroscuro, many overlapping figures) -- the "hard case" end of the range. */
    val THE_NIGHT_WATCH_SIMPLE = Fixture(
        fileName = "the_night_watch_source.jpg",
        preset = PhotoConverter.Preset.SIMPLE,
        regionCount = 141,
        isPaintByNumberEligible = true,
    )

    /** A dark, multi-figure anatomical scene with a plain-ish background -- moderate complexity. */
    val ANATOMY_LESSON_SIMPLE = Fixture(
        fileName = "anatomy_lesson_dr_tulp_source.jpg",
        preset = PhotoConverter.Preset.SIMPLE,
        regionCount = 99,
        isPaintByNumberEligible = true,
    )

    /** A clean, iconic, high-contrast single-subject portrait against a plain background -- the "easy case" end of the range. */
    val WHISTLERS_MOTHER_SIMPLE = Fixture(
        fileName = "whistlers_mother_source.jpg",
        preset = PhotoConverter.Preset.SIMPLE,
        regionCount = 35,
        isPaintByNumberEligible = true,
    )

    val all: List<Fixture> = listOf(THE_NIGHT_WATCH_SIMPLE, ANATOMY_LESSON_SIMPLE, WHISTLERS_MOTHER_SIMPLE)
}
