package com.vellum.studio.util

import android.content.Context
import android.os.PowerManager

/**
 * Logs every device thermal-status transition to [DiagnosticLog] while an editor session is open --
 * see the "Trace sections and session vitals" roadmap item. The Tab S9 FE/Z Fold5 both expose a real
 * Thermal HAL 2.0 (`dumpsys thermalservice` on the reference device: Thermal Status 0, AP 43.8C,
 * SKIN 38.2C), so [PowerManager.addThermalStatusListener] (API 29, exactly this app's minSdk -- no
 * version guard needed) is a real, cheap signal, not a speculative one: without it, throttling during
 * a long field session leaves zero record, and jank the user reports afterwards is unattributable.
 *
 * Deliberately its own tiny class rather than folded into [SessionVitals]: a thermal transition is an
 * EVENT (log it the instant it happens) while [SessionVitals] is a periodic SAMPLE (poll every 60s) --
 * conflating the two would mean either delaying a real transition by up to 60s to fit the sampler's
 * cadence, or giving the sampler its own event-callback plumbing for just this one field. Two small,
 * independently testable classes is simpler than one that does both jobs.
 *
 * [start]/[stop] are idempotent and symmetric (calling either twice, or [stop] before [start], is a
 * safe no-op) so the editor screen's lifecycle wiring (see EditorScreen's autosaver wiring for the
 * same pattern) doesn't need to track whether it already did this.
 */
class ThermalWatcher(private val context: Context, private val log: (String) -> Unit = defaultLog(context)) {
    private var powerManager: PowerManager? = null
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (listener != null) return
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            log("thermal status -> ${thermalStatusLabel(status)}")
        }
        // Fires once immediately with the CURRENT status (documented platform behavior), so a
        // session that starts already throttled (e.g. the tablet was left in a hot car) gets that
        // on record too, not just the next transition away from it.
        pm.addThermalStatusListener(l)
        powerManager = pm
        listener = l
    }

    fun stop() {
        val pm = powerManager ?: return
        val l = listener ?: return
        pm.removeThermalStatusListener(l)
        powerManager = null
        listener = null
    }

    companion object {
        /** Pulled out to a pure function purely so a unit test can pin every label without needing a
         * live [PowerManager] -- [PowerManager.THERMAL_STATUS_*] are plain Int constants. */
        internal fun thermalStatusLabel(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN($status)"
        }

        private fun defaultLog(context: Context): (String) -> Unit =
            { message -> DiagnosticLog.log(context, "Thermal", message) }
    }
}
