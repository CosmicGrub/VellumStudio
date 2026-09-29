package com.vellum.studio.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Writes the OS's own record of why this app's PREVIOUS processes died into [DiagnosticLog].
 *
 * Why this exists: DiagnosticLog's uncaught-exception handler only ever sees JVM throwables. The
 * deaths this project actually chases ("devices vanishing", the app just gone when the tablet is
 * picked back up) are mostly ones no in-process code can observe -- a low-memory kill by lmkd, an ANR
 * kill, a native crash in libopencv_java4 / libxeno_native, the user swiping the app away. Android
 * keeps a short per-app history of exactly those ([ActivityManager.getHistoricalProcessExitReasons],
 * API 30+); reading it at the NEXT start turns "no record" into "LOW_MEMORY, rss 1.9 GB, importance
 * 100" or "ANR" plus the head of the ANR trace. Entirely local, no crash service, nothing on the
 * canvas hot path.
 *
 * minSdk is 29, so everything that touches the API-30 types sits behind [Build.VERSION_CODES.R]
 * and is only ever reached through [logRecentExits]'s SDK check; on API 29 that call is a no-op.
 *
 * Dedupe: the OS returns the same last few entries on every launch, so the newest timestamp already
 * written is remembered in SharedPreferences and only strictly newer exits are logged -- launching the
 * app ten times after one crash yields one ExitInfo line, not ten. The very first run on a build that
 * has this feature logs whatever history exists (up to [MAX_ENTRIES]), which is the useful behavior:
 * the deaths that happened BEFORE this build was installed are precisely what it was added to explain.
 */
object ProcessExitLog {
    private const val TAG = "ExitInfo"
    private const val PREFS = "process_exit_log"
    private const val KEY_LAST_TS = "last_logged_timestamp"

    /** How far back to ask the OS for. The system keeps a handful per app anyway; this bounds the
     * worst-case size of one start's burst of log lines. */
    const val MAX_ENTRIES = 5

    /** Head of an ANR / native-crash trace worth keeping: enough for the main thread's stack or the
     * signal and backtrace frames, without letting one entry eat the 300 KB rolling log. */
    private const val TRACE_HEAD_BYTES = 4 * 1024
    private const val NATIVE_SCAN_BYTES = 8 * 1024
    private const val NATIVE_TEXT_CAP = 2 * 1024

    /**
     * A plain snapshot of one [ApplicationExitInfo], so formatting/dedupe are ordinary testable
     * functions with no API-30 type in their signatures (and so nothing here loads a class that
     * does not exist on API 29). [trace] is already reduced to loggable text.
     */
    data class ExitRecord(
        val timestamp: Long,
        val reasonCode: Int,
        val status: Int,
        val importance: Int,
        val pssKb: Long,
        val rssKb: Long,
        val description: String?,
        val trace: String?,
    )

    /**
     * Reads the recent exit history and logs each entry newer than the last one already logged.
     * Safe to call from any thread and never throws (diagnostics must not be able to break app
     * start); does blocking I/O for the trace streams, so call it off the main thread. Returns the
     * number of entries logged, for tests.
     */
    fun logRecentExits(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return runCatching { logNew(context.applicationContext, readRecent(context.applicationContext)) }
            .getOrElse {
                DiagnosticLog.log(context, TAG, "Could not read process exit history: ${it.javaClass.simpleName}: ${it.message}")
                0
            }
    }

    /**
     * Logs the records in [records] whose timestamp is newer than the persisted high-water mark,
     * oldest first (so the log reads chronologically), then advances the mark. Split from the OS read
     * so the dedupe is testable with hand-built records.
     */
    internal fun logNew(context: Context, records: List<ExitRecord>): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fresh = unseen(records, prefs.getLong(KEY_LAST_TS, 0L))
        if (fresh.isEmpty()) return 0
        fresh.forEach { DiagnosticLog.log(context, TAG, format(it)) }
        // commit(), not apply(): this runs at process start on a background thread, and the mark must be
        // durable before a crash a moment later could re-log the same entries on the next launch.
        prefs.edit().putLong(KEY_LAST_TS, fresh.maxOf { it.timestamp }).commit()
        return fresh.size
    }

    /** Records strictly newer than [lastLoggedTimestamp], oldest first. */
    internal fun unseen(records: List<ExitRecord>, lastLoggedTimestamp: Long): List<ExitRecord> =
        records.filter { it.timestamp > lastLoggedTimestamp }.sortedBy { it.timestamp }

    /** One log entry: a single summary line, then the trace head (if any) indented beneath it. */
    internal fun format(r: ExitRecord): String {
        val summary = StringBuilder()
            .append("Previous process exited: reason=").append(reasonName(r.reasonCode))
            .append(" status=").append(r.status)
            .append(" importance=").append(importanceName(r.importance))
            .append(" pss=").append(r.pssKb).append("KB rss=").append(r.rssKb).append("KB")
            .append(" at=").append(r.timestamp)
        if (!r.description.isNullOrBlank()) summary.append(" description=").append(r.description.trim())
        val trace = r.trace?.trim().orEmpty()
        if (trace.isNotEmpty()) {
            summary.append("\n  trace (head):\n").append(trace.lines().joinToString("\n") { "    $it" })
        }
        return summary.toString()
    }

    internal fun reasonName(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        // API 31+ / 33+ constants are compile-time inlined ints, so naming them costs nothing on 30.
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "REASON_$code"
    }

    /** The importance the process had when it died: 100 (FOREGROUND) killed is a very different
     * story from 400 (CACHED) killed, so it is spelled out rather than left as a bare number. */
    internal fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND(100)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE(125)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE(200)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE(230)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE(300)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED(400)"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE(1000)"
        else -> importance.toString()
    }

    /**
     * Native-crash traces from API 31+ are a protobuf tombstone, not text, so dumping them raw would
     * be binary garbage. The protobuf still carries the interesting strings verbatim (signal name,
     * abort message, backtrace function and library names), so pull out just the runs of printable
     * ASCII at least [minRun] long -- `strings(1)` semantics -- capped to [NATIVE_TEXT_CAP] chars.
     */
    internal fun printableRuns(bytes: ByteArray, minRun: Int = 6, cap: Int = NATIVE_TEXT_CAP): String {
        val out = StringBuilder()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= minRun && out.length < cap) out.append(run).append('\n')
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) run.append(c.toChar()) else flush()
        }
        flush()
        return if (out.length > cap) out.substring(0, cap) else out.toString()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readRecent(context: Context): List<ExitRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        // pid 0 = "any pid", packageName null = this app; the last arg caps how many come back.
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_ENTRIES)
        return infos.map { info ->
            ExitRecord(
                timestamp = info.timestamp,
                reasonCode = info.reason,
                status = info.status,
                importance = info.importance,
                pssKb = info.pss,
                rssKb = info.rss,
                description = info.description,
                trace = readTrace(info),
            )
        }
    }

    /** ANR traces are text; native crashes are (on 31+) protobuf; every other reason has none. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): String? {
        if (info.reason != ApplicationExitInfo.REASON_ANR && info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return null
        return runCatching {
            info.traceInputStream?.use { stream ->
                if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    String(stream.readNBytesCompat(TRACE_HEAD_BYTES), Charsets.UTF_8)
                } else {
                    printableRuns(stream.readNBytesCompat(NATIVE_SCAN_BYTES))
                }
            }
        }.getOrNull()
    }

    /** InputStream.readNBytes(int) is API 33; this reads up to [max] bytes on any level. */
    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = ByteArray(max)
        var total = 0
        while (total < max) {
            val n = read(buf, total, max - total)
            if (n < 0) break
            total += n
        }
        return buf.copyOf(total)
    }
}
