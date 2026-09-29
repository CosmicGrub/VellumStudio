package com.vellum.studio.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for the session-vitals sampler this roadmap item adds: before this, a
 * 60-minute field session left zero record of battery drain, temperature, heap growth or GC
 * pressure, so a report of "it got laggy near the end" was unattributable after the fact.
 *
 * Split the same way [SessionVitals] itself is: [VitalsAggregator]'s accumulation and formatting are
 * pure functions over an injected clock and fabricated readings (no coroutines, no Android, no real
 * time -- every assertion below is exact), while [SessionVitals]'s own scheduling is checked with
 * real short delays against a fake `readVitals`/`log`, the same style [EditorAutosaverTest]'s debounce
 * tests already use for a real coroutine timer.
 */
class SessionVitalsTest {

    // ------------------------------------------------------------------ VitalsAggregator: pure

    @Test fun `no reading at all yields no summary`() {
        val agg = VitalsAggregator()
        agg.reset(0L)
        assertNull(agg.summaryLine(5_000L))
    }

    @Test fun `summary line format is pinned across three synthetic readings`() {
        val agg = VitalsAggregator()
        agg.reset(0L)
        agg.note(VitalsReading(batteryPercent = 82, batteryTemperatureC = 31.2f, javaHeapUsedBytes = 100L * 1024 * 1024, gcCount = 10))
        agg.note(VitalsReading(batteryPercent = 80, batteryTemperatureC = 34.8f, javaHeapUsedBytes = 128L * 1024 * 1024, gcCount = 40))
        agg.note(VitalsReading(batteryPercent = 79, batteryTemperatureC = 33.0f, javaHeapUsedBytes = 90L * 1024 * 1024, gcCount = 55))

        val line = agg.summaryLine(754_000L) // 12m34s after reset(0L)

        assertEquals(
            "Session 12m34s: battery 82%->79% (-3%) temp 31.2-34.8C heap peak 128MB gcCount +45",
            line,
        )
    }

    @Test fun `a single reading with equal min and max temperature does not print a bogus range`() {
        val agg = VitalsAggregator()
        agg.reset(0L)
        agg.note(VitalsReading(batteryPercent = 50, batteryTemperatureC = 30.0f, javaHeapUsedBytes = 10L * 1024 * 1024, gcCount = 1))

        assertEquals("Session 0m0s: battery 50%->50% (+0%) temp 30.0C heap peak 10MB gcCount +0", agg.summaryLine(500L))
    }

    @Test fun `missing fields degrade gracefully instead of crashing or fabricating a value`() {
        val agg = VitalsAggregator()
        agg.reset(0L)
        agg.note(VitalsReading(javaHeapUsedBytes = 50L * 1024 * 1024)) // battery/temp/gc all unavailable

        assertEquals("Session 1m0s: heap peak 50MB", agg.summaryLine(60_000L))
    }

    @Test fun `duration renders minutes and seconds and never goes negative on a clock that did not advance`() {
        val agg = VitalsAggregator()
        agg.reset(10_000L)
        agg.note(VitalsReading(batteryPercent = 90))
        assertEquals("Session 0m0s: battery 90%->90% (+0%)", agg.summaryLine(9_000L)) // clock somehow went backwards
    }

    // ------------------------------------------------------------------ SessionVitals: scheduling

    @Test
    fun `start takes an immediate baseline reading then samples on the configured interval`() = runBlocking {
        val readCount = AtomicInteger(0)
        val vitals = SessionVitals(
            scope = CoroutineScope(Dispatchers.Default),
            readVitals = { readCount.incrementAndGet(); VitalsReading(batteryPercent = 50) },
            log = {},
            intervalMs = 30,
        )
        try {
            vitals.start()
            assertEquals("the baseline reading happens synchronously inside start()", 1, readCount.get())
            delay(160) // ~5 intervals at 30ms
            assertTrue("expected several sampled readings by now (was ${readCount.get()})", readCount.get() >= 3)
        } finally {
            vitals.stop()
        }
    }

    @Test
    fun `stop halts sampling -- no further readVitals calls land afterwards`() = runBlocking {
        val readCount = AtomicInteger(0)
        val vitals = SessionVitals(
            scope = CoroutineScope(Dispatchers.Default),
            readVitals = { readCount.incrementAndGet(); VitalsReading() },
            log = {},
            intervalMs = 20,
        )
        vitals.start()
        delay(90)
        vitals.stop()
        val countAtStop = readCount.get()
        delay(120)
        assertEquals("no reading should land after stop()", countAtStop, readCount.get())
    }

    @Test
    fun `logSessionSummary emits one line per call without stopping the sampler`() = runBlocking {
        val lines = mutableListOf<String>()
        val vitals = SessionVitals(
            scope = CoroutineScope(Dispatchers.Default),
            readVitals = { VitalsReading(batteryPercent = 77) },
            log = { lines += it },
            intervalMs = 20,
        )
        try {
            vitals.start()
            delay(50)
            vitals.logSessionSummary() // e.g. the first ON_STOP
            assertEquals(1, lines.size)
            assertTrue(lines[0].startsWith("Session "))

            delay(50)
            vitals.logSessionSummary() // e.g. resumed, then ON_STOP again -- same session, fuller picture
            assertEquals("a second ON_STOP logs a second line, not a crash or a skip", 2, lines.size)
        } finally {
            vitals.stop()
        }
    }

    @Test
    fun `logSessionSummary before start (or before any reading) is a silent no-op`() {
        val lines = mutableListOf<String>()
        val vitals = SessionVitals(
            scope = CoroutineScope(Dispatchers.Default),
            readVitals = { VitalsReading() },
            log = { lines += it },
        )
        vitals.logSessionSummary()
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `start is idempotent -- calling it twice does not double the sampling rate`() = runBlocking {
        val readCount = AtomicInteger(0)
        val vitals = SessionVitals(
            scope = CoroutineScope(Dispatchers.Default),
            readVitals = { readCount.incrementAndGet(); VitalsReading() },
            log = {},
            intervalMs = 30,
        )
        try {
            vitals.start()
            vitals.start() // must not spawn a second sampling job
            assertEquals("only the one baseline reading from the first start()", 1, readCount.get())
            delay(70)
            assertTrue(readCount.get() < 5) // a doubled-up sampler would already be well past this
        } finally {
            vitals.stop()
        }
    }
}
