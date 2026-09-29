package com.vellum.studio.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream

/**
 * [ProcessExitLog]: the OS's record of why earlier processes died ends up in [DiagnosticLog], once
 * each, and only where the API exists. The end-to-end tests go through Robolectric's real
 * ActivityManager shadow ([ActivityManager.getHistoricalProcessExitReasons]) rather than hand-built
 * records, so they cover the actual read path, not just the formatter.
 *
 * Plain [Application] (not VellumApp): VellumApp.onCreate itself calls [ProcessExitLog.logRecentExits],
 * which would race these tests' own calls over the same log and high-water mark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ProcessExitLogTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private var realSdkInt = Build.VERSION.SDK_INT

    @Before
    fun setUp() {
        realSdkInt = Build.VERSION.SDK_INT
        DiagnosticLog.clear(app)
    }

    @After
    fun restoreSdk() {
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", realSdkInt)
    }

    private fun exitLines(): List<String> =
        DiagnosticLog.lastLines(app, 200).filter { "[ExitInfo]" in it }

    private fun addExit(reason: Int, timestamp: Long, status: Int = 0, description: String? = null, trace: String? = null) {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val builder = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setPid(1000 + timestamp.toInt())
            .setProcessName(app.packageName)
            .setReason(reason)
            .setStatus(status)
            .setImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
            .setPss(1234)
            .setRss(5678)
            .setTimestamp(timestamp)
        if (description != null) builder.setDescription(description)
        if (trace != null) builder.setTraceInputStream(ByteArrayInputStream(trace.toByteArray()))
        shadowOf(am).addApplicationExitInfo(builder.build())
    }

    @Test
    fun exitReasonsAreLoggedThroughTheRealActivityManagerReadPath() {
        addExit(ApplicationExitInfo.REASON_LOW_MEMORY, timestamp = 100, description = "lmkd kill")
        addExit(ApplicationExitInfo.REASON_ANR, timestamp = 200, trace = "\"main\" prio=5 tid=1 Blocked\n  at com.vellum.Foo.bar(Foo.kt:1)")

        assertEquals(2, ProcessExitLog.logRecentExits(app))

        val lines = DiagnosticLog.lastLines(app, 200).joinToString("\n")
        assertTrue("LOW_MEMORY entry missing:\n$lines", "reason=LOW_MEMORY" in lines)
        assertTrue("description missing:\n$lines", "description=lmkd kill" in lines)
        assertTrue("ANR entry missing:\n$lines", "reason=ANR" in lines)
        assertTrue("ANR trace head missing:\n$lines", "Foo.kt:1" in lines)
        assertTrue("importance should be named, not a bare number:\n$lines", "importance=FOREGROUND(100)" in lines)
        assertTrue("rss missing:\n$lines", "rss=5678KB" in lines)
        // Oldest first, so the log reads chronologically.
        assertTrue(lines.indexOf("reason=LOW_MEMORY") < lines.indexOf("reason=ANR"))
    }

    @Test
    fun relaunchDoesNotRepeatEntriesAlreadyLoggedButDoesLogNewerOnes() {
        addExit(ApplicationExitInfo.REASON_CRASH, timestamp = 100)
        assertEquals(1, ProcessExitLog.logRecentExits(app))

        // The OS hands back the same history on every start; nothing new must be written.
        assertEquals(0, ProcessExitLog.logRecentExits(app))
        assertEquals(1, exitLines().size)

        addExit(ApplicationExitInfo.REASON_CRASH_NATIVE, timestamp = 300)
        assertEquals(1, ProcessExitLog.logRecentExits(app))
        val lines = exitLines()
        assertEquals(2, lines.size)
        assertTrue(lines.last().contains("reason=CRASH_NATIVE"))
    }

    @Test
    fun nothingIsReadOrLoggedBelowApi30() {
        addExit(ApplicationExitInfo.REASON_LOW_MEMORY, timestamp = 100)
        // minSdk is 29: on such a device the API does not exist, so the call must be a silent no-op
        // (touching it would be a NoSuchMethodError at app start).
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 29)

        assertEquals(0, ProcessExitLog.logRecentExits(app))
        assertTrue(exitLines().isEmpty())
    }

    @Test
    fun unseenKeepsOnlyStrictlyNewerAndSortsOldestFirst() {
        fun rec(ts: Long) = ProcessExitLog.ExitRecord(ts, ApplicationExitInfo.REASON_OTHER, 0, 400, 0, 0, null, null)
        val result = ProcessExitLog.unseen(listOf(rec(30), rec(10), rec(20)), lastLoggedTimestamp = 10)
        assertEquals(listOf(20L, 30L), result.map { it.timestamp })
    }

    @Test
    fun formatNamesTheReasonAndIndentsTheTrace() {
        val text = ProcessExitLog.format(
            ProcessExitLog.ExitRecord(
                timestamp = 42, reasonCode = ApplicationExitInfo.REASON_ANR, status = 6,
                importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED,
                pssKb = 10, rssKb = 20, description = "  user request timed out ", trace = "line one\nline two",
            ),
        )
        assertTrue(text, text.startsWith("Previous process exited: reason=ANR status=6 importance=CACHED(400) pss=10KB rss=20KB at=42"))
        assertTrue(text, "description=user request timed out" in text)
        assertTrue(text, "    line one\n    line two" in text)
        // An unmapped code must still say something useful rather than throw.
        assertTrue("REASON_99" in ProcessExitLog.reasonName(99))
    }

    @Test
    fun nativeTombstoneBytesAreReducedToTheirPrintableStrings() {
        val bytes = byteArrayOf(0, 1, 2) + "signal 11 (SIGSEGV)".toByteArray() + byteArrayOf(0, 9, 0) +
            "ab".toByteArray() + byteArrayOf(0) + "libopencv_java4.so".toByteArray() + byteArrayOf(0x7F, 0x00)
        val out = ProcessExitLog.printableRuns(bytes)
        assertTrue(out, "signal 11 (SIGSEGV)" in out)
        assertTrue(out, "libopencv_java4.so" in out)
        assertFalse("runs shorter than the minimum are noise: $out", "ab\n" in out)
        assertTrue(ProcessExitLog.printableRuns(ByteArray(10_000) { 'x'.code.toByte() }).length <= 2 * 1024)
    }
}
