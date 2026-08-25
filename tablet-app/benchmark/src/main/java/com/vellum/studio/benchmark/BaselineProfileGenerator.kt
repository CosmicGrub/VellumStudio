package com.vellum.studio.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a real, on-device Baseline Profile (developer.android.com/topic/performance/
 * baselineprofiles/overview) for [TARGET_PACKAGE]'s critical user journey: cold start into
 * Gallery, then opening an already-EXISTING project into the Editor -- the exact journey this
 * project's task brief named, and arguably the single most-repeated real path through this app
 * (every session after the first one starts here).
 *
 * ## Why this lives in the existing `:benchmark` module rather than a new `:baselineprofile` one
 *
 * Google's Android-Studio-wizard default creates a SEPARATE `com.android.test` module plus the
 * `androidx.baselineprofile` Gradle plugin, which auto-manages its own `nonMinifiedRelease` /
 * `benchmarkRelease` build types on `:app`. Verified live (2026-08-25) against
 * developer.android.com/topic/performance/baselineprofiles/{overview,create-baselineprofile,
 * configure-baselineprofiles} rather than trusted from memory -- this tooling has genuinely moved
 * across AGP/library versions, exactly as this task warned.
 *
 * That plugin's whole point is to auto-generate a build type shaped like "release, but not
 * minified, so profile method/class names still match source" -- and this project ALREADY built
 * exactly that by hand last pass: `:app`'s "benchmark" build type (app/build.gradle.kts) is
 * initWith(release) + isMinifyEnabled=false + isShrinkResources=false + isDebuggable=false, i.e.
 * precisely Google's own documented "nonMinifiedRelease" shape, deliberately chosen (see that
 * build type's own comment) to dodge `:benchmark:checkTestedAppObfuscationBenchmark` forcing R8
 * onto androidx.test/benchmark-macro's own dependency graph. Introducing the
 * `androidx.baselineprofile` plugin on top would make AGP try to manage its OWN generated build
 * type(s) for this exact same purpose, colliding with (or duplicating) a build type this project
 * already has a documented, hard-won reason to keep hand-rolled -- for a single-app, single-CUJ
 * project at this scale, that's disproportionate complexity for zero behavioral gain.
 *
 * Per developer.android.com/topic/performance/baselineprofiles/manually-create-measure (verified
 * live 2026-08-25): "manual placement" of a `src/main/baseline-prof.txt` file is still fully valid
 * and current -- AGP compiles/embeds it into any non-debuggable build type's APK
 * (`assets/dexopt/baseline.prof`) with NO Gradle plugin involved at all, using exactly the same
 * human-readable ART-profile-rule syntax [BaselineProfileRule.collect] already emits. That doc
 * itself recommends generating the *contents* of that file via Macrobenchmark's
 * [BaselineProfileRule] specifically (over the fully-manual `adb shell cmd package compile` /
 * `pm dump-profiles` workflow it also documents as a fallback) "to reduce manual effort and
 * increase general scalability" -- which is exactly what this test class does. The only piece
 * this project deliberately does NOT automate is the one-time copy of the generated
 * `...-baseline-prof.txt` output into `app/src/main/baseline-prof.txt`; see that file's own
 * header comment for the exact regeneration command.
 *
 * ## Why project seeding is a plain `@Before`, not part of the measured journey
 *
 * [BaselineProfileRule.collect]'s `profileBlock` has no separate "setup" vs. "measure" split the
 * way [androidx.benchmark.macro.junit4.MacrobenchmarkRule.measureRepeated]'s `setupBlock` does --
 * whatever runs inside `profileBlock` becomes part of the collected profile. Creating a fresh
 * "Untitled" canvas through the real Gallery UI (the same flow [PanZoomFrameTimingBenchmark] uses)
 * would work as a one-time "if empty" branch inside `profileBlock` too, but that folds the
 * "create a new canvas" code path into a profile that's supposed to represent "open an EXISTING
 * one" -- muddying exactly the journey this test was asked to isolate. Seeding via a plain
 * `@Before` (which JUnit runs once before the `@Test` method starts -- not once per internal
 * [BaselineProfileRule] iteration) keeps that creation flow's classes out of the collected profile
 * entirely, at the cost of one extra `am start` shell round-trip per test-class run. The app's
 * on-disk project data is NOT wiped between `profileBlock` iterations (only the process is
 * killed/relaunched each time, same as [androidx.benchmark.macro.StartupMode.COLD] does for
 * [StartupBenchmark]) -- confirmed directly by re-reading the actual project files still present
 * mid-run -- so the one project seeded here survives for every iteration [BaselineProfileRule]
 * runs afterward.
 *
 * `am start` (a plain shell command, run via [UiDevice.executeShellCommand]) is used instead of
 * `Context.startActivity()` deliberately: this module's manifest instrumentation target is itself
 * (`android.experimental.self-instrumenting`, see benchmark/build.gradle.kts's own comment on why),
 * so `InstrumentationRegistry.getInstrumentation().targetContext` here is THIS module's own
 * context (`com.vellum.studio.benchmark`), not [TARGET_PACKAGE]'s -- launching a different
 * installed package's activity via `Context.startActivity()` from there would need an Android 11+
 * package-visibility `<queries>` declaration this deliberately-minimal test-only manifest has no
 * reason to carry. A shell-level `am start` sidesteps that entirely, and is the same mechanism
 * [androidx.benchmark.macro.MacrobenchmarkScope.startActivityAndWait] itself uses under the hood.
 *
 * The "Back" [androidx.compose.material3.IconButton] (contentDescription "Back", EditorScreen.kt)
 * is used to return to Gallery after seeding, rather than [UiDevice.pressBack] -- it's the exact
 * control a real user taps, and it's already proven reliable (EditorScreen.kt's own
 * `BackHandler`/TopAppBar navigationIcon both route through the same `onBack()` -> `popBackStack()`
 * call either way, but tapping the real on-screen control avoids depending on system
 * back-dispatch behavior at all).
 *
 * See app/src/main/baseline-prof.txt for the resulting checked-in profile, and
 * tablet-app/benchmark-baseline.md for the before/after [StartupBenchmark] numbers this produced.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Before
    fun ensureExistingProjectExists() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        device.executeShellCommand("am start -n $TARGET_PACKAGE/$MAIN_ACTIVITY")
        device.wait(Until.hasObject(By.desc(NEW_CANVAS_DESC)), TIMEOUT_MS)
        if (device.hasObject(By.text(EMPTY_STATE_TEXT))) {
            device.findObject(By.desc(NEW_CANVAS_DESC)).click()
            device.wait(Until.hasObject(By.text("Create")), TIMEOUT_MS)
            device.findObject(By.text("Create")).click()
            device.wait(Until.hasObject(By.desc(CANVAS_DESC)), TIMEOUT_MS)
            device.findObject(By.desc(BACK_DESC)).click()
            device.wait(Until.hasObject(By.text(PROJECT_NAME)), TIMEOUT_MS)
        }
        device.pressHome()
    }

    @Test
    fun coldStartIntoExistingProject() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.text(PROJECT_NAME)), TIMEOUT_MS)
        device.findObject(By.text(PROJECT_NAME)).click()
        device.wait(Until.hasObject(By.desc(CANVAS_DESC)), TIMEOUT_MS)
    }

    companion object {
        private const val TARGET_PACKAGE = "com.vellum.studio"
        private const val MAIN_ACTIVITY = "com.vellum.studio.MainActivity"
        private const val NEW_CANVAS_DESC = "New Canvas"
        private const val CANVAS_DESC = "Drawing canvas"
        private const val BACK_DESC = "Back"
        private const val PROJECT_NAME = "Untitled"
        private const val EMPTY_STATE_TEXT = "No canvases yet"
        private const val TIMEOUT_MS = 10_000L
    }
}
