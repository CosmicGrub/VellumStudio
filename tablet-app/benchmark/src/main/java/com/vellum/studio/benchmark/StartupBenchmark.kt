package com.vellum.studio.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold app-startup timing for [TARGET_PACKAGE] (com.vellum.studio's MainActivity, launched via its
 * default LAUNCHER intent -- see [androidx.benchmark.macro.MacrobenchmarkScope.startActivityAndWait]).
 *
 * [StartupMode.COLD] kills the process before each iteration's launch, so every sample is a true
 * cold start (not a warm/hot re-launch of an already-resident process).
 *
 * [CompilationMode.DEFAULT] is spelled out explicitly here (it's already the parameter's own
 * default) rather than picking [CompilationMode.Full] or [CompilationMode.None]: it reflects
 * whatever ahead-of-time compilation state the device already has for this package, which is the
 * closest approximation to what a real user sees after installing normally -- this project has no
 * checked-in Baseline Profile (see developer.android.com/topic/performance/baselineprofiles) to
 * pre-seed a specific compiled state, so [CompilationMode.DEFAULT] and [CompilationMode.None] are
 * expected to measure very similarly today; DEFAULT is still the right one to declare, since it's
 * what would automatically start reflecting a real Baseline Profile's benefit the day this project
 * ever adds one, with zero change needed here.
 *
 * See tablet-app/benchmark-baseline.md for the actual measured numbers this test produced, and the
 * exact device + date they came from.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
    ) {
        pressHome()
        startActivityAndWait()
    }

    companion object {
        private const val TARGET_PACKAGE = "com.vellum.studio"
        private const val ITERATIONS = 10
    }
}
