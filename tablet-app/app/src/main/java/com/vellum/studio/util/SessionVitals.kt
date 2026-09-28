package com.vellum.studio.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToLong

/**
 * One best-effort snapshot of the handful of vitals [SessionVitals] polls roughly every 60s --
 * battery level/temperature, Java heap used, and (where the ART build exposes it cheaply) the
 * cumulative GC count. Every field is nullable ON PURPOSE: a sticky battery broadcast that hasn't
 * landed yet, or a `Debug.getRuntimeStat` key a given ART build doesn't populate, must degrade that
 * ONE field to "unknown" rather than losing the whole reading (or the whole sampler).
 */
data class VitalsReading(
    val batteryPercent: Int? = null,
    val batteryTemperatureC: Float? = null,
    val javaHeapUsedBytes: Long? = null,
    val gcCount: Long? = null,
)

/** Reads a real [VitalsReading] from the platform. A separate object from [SessionVitals] itself
 * purely so the sampler's scheduling/aggregation/formatting stays testable with a fabricated
 * `readVitals` lambda instead of a live device -- see [SessionVitalsTest]. */
object SessionVitalsReader {
    fun read(context: Context): VitalsReading {
        // registerReceiver(null, filter) with a null receiver is the documented way to read a sticky
        // broadcast's LAST value synchronously without actually registering a listener -- no
        // permission, no lingering registration to leak or unregister.
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else null
        val tenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val batteryTemperatureC = if (tenthsC != Int.MIN_VALUE) tenthsC / 10f else null

        val runtime = Runtime.getRuntime()
        val javaHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory()

        // "art.gc.gc-count" is a real, documented Debug.getRuntimeStat key (API 23+); wrapped since a
        // future/ART build is free to stop populating it, and this must degrade to "unknown", not crash.
        val gcCount = runCatching { Debug.getRuntimeStat("art.gc.gc-count").toLong() }.getOrNull()

        return VitalsReading(batteryPercent, batteryTemperatureC, javaHeapUsedBytes, gcCount)
    }
}

/**
 * Accumulates [VitalsReading]s across one editor session and formats the single end-of-session
 * summary line the roadmap item asks for. Pulled out of [SessionVitals] as its own state machine,
 * with NO Android or coroutine dependency at all, so every aggregation/formatting rule is a plain
 * unit test against fabricated readings and a fake clock (see [SessionVitalsTest]) -- exactly the
 * "make what is testable deterministic" guidance this item ships under.
 */
internal class VitalsAggregator {
    private var startedAtMs = 0L
    private var count = 0
    private var batteryFirst: Int? = null
    private var batteryLast: Int? = null
    private var tempMinC: Float? = null
    private var tempMaxC: Float? = null
    private var heapPeakBytes: Long? = null
    private var gcFirst: Long? = null
    private var gcLast: Long? = null

    fun reset(nowMs: Long) {
        startedAtMs = nowMs
        count = 0
        batteryFirst = null; batteryLast = null
        tempMinC = null; tempMaxC = null
        heapPeakBytes = null
        gcFirst = null; gcLast = null
    }

    fun note(reading: VitalsReading) {
        count++
        reading.batteryPercent?.let { b ->
            if (batteryFirst == null) batteryFirst = b
            batteryLast = b
        }
        reading.batteryTemperatureC?.let { t ->
            tempMinC = minOf(tempMinC ?: t, t)
            tempMaxC = maxOf(tempMaxC ?: t, t)
        }
        reading.javaHeapUsedBytes?.let { h -> heapPeakBytes = maxOf(heapPeakBytes ?: h, h) }
        reading.gcCount?.let { g ->
            if (gcFirst == null) gcFirst = g
            gcLast = g
        }
    }

    /** The one summary line, or null if [note] was never called (nothing to summarize). Format is
     * pinned exactly by [SessionVitalsTest] -- keep the two in sync. */
    fun summaryLine(nowMs: Long): String? {
        if (count == 0) return null
        val durationMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        val parts = mutableListOf(durationLabel(durationMs))

        val bFirst = batteryFirst
        val bLast = batteryLast
        if (bFirst != null && bLast != null) {
            parts += "battery ${bFirst}%->${bLast}% (${signed(bLast - bFirst)}%)"
        }
        val tMin = tempMinC
        val tMax = tempMaxC
        if (tMin != null && tMax != null) {
            parts += if (tMin == tMax) "temp ${oneDecimal(tMin)}C" else "temp ${oneDecimal(tMin)}-${oneDecimal(tMax)}C"
        }
        heapPeakBytes?.let { parts += "heap peak ${mb(it)}MB" }
        val gFirst = gcFirst
        val gLast = gcLast
        if (gFirst != null && gLast != null) parts += "gcCount +${gLast - gFirst}"

        return "Session ${parts.joinToString(" ")}"
    }

    private fun durationLabel(durationMs: Long): String {
        val totalSec = durationMs / 1000L
        val m = totalSec / 60
        val s = totalSec % 60
        return "${m}m${s}s:"
    }

    private fun signed(v: Int): String = if (v >= 0) "+$v" else v.toString()
    private fun oneDecimal(v: Float): String = String.format(Locale.US, "%.1f", (v * 10).roundToLong() / 10.0)
    private fun mb(bytes: Long): Long = bytes / (1024 * 1024)
}

/**
 * Drives [VitalsAggregator] on a real ~60s cadence for one open editor session, and logs the summary
 * to [DiagnosticLog] on request (see [logSessionSummary]) -- wired to fire on ON_STOP, mirroring
 * [com.vellum.studio.model.EditorAutosaver]'s own ON_STOP flush ("the last moment the app is
 * guaranteed to run code before it might be killed"). Deliberately NOT reset by
 * [logSessionSummary] itself: the app can come back to the foreground after an ON_STOP without the
 * editor ever closing (a quick app-switch), and that's still the SAME session, not a new one -- so a
 * second ON_STOP later just reports the fuller picture, same as re-reading an odometer.
 *
 * [start]/[stop] follow the same idempotent, symmetric contract as [ThermalWatcher]'s.
 */
class SessionVitals(
    private val scope: CoroutineScope,
    private val readVitals: () -> VitalsReading,
    private val log: (String) -> Unit,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val aggregator = VitalsAggregator()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        aggregator.reset(nowMs())
        aggregator.note(readVitals()) // a baseline reading now, not just after the first interval
        job = scope.launch {
            while (true) {
                delay(intervalMs)
                aggregator.note(readVitals())
            }
        }
    }

    /** Logs the one summary line for the session so far (see the class doc for why this does not
     * reset the aggregation). No-op if [start] was never called. */
    fun logSessionSummary() {
        aggregator.summaryLine(nowMs())?.let(log)
    }

    /** Stops the sampling coroutine; call when the editor screen actually leaves. */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 60_000L
    }
}
