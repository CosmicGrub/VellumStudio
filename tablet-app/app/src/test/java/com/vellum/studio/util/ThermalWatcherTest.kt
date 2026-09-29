package com.vellum.studio.util

import android.app.Application
import android.content.Context
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression coverage for [ThermalWatcher]: before this item, nothing in the app read
 * [PowerManager]'s thermal status at all, so a field session that ran hot left zero record of it --
 * see the "Trace sections and session vitals" roadmap item's `doneWhen` ("thermal transitions are
 * logged"). Deterministic without a real device: Robolectric's `ShadowPowerManager` fires the exact
 * same [PowerManager.OnThermalStatusChangedListener] callback the real HAL would (confirmed against
 * the shadow's own `setCurrentThermalStatus` -> listener dispatch), which is the on-device equivalent
 * of `adb shell cmd thermalservice override-status <n>` the roadmap item names.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThermalWatcherTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private val powerManager get() = app.getSystemService(Context.POWER_SERVICE) as PowerManager

    @Test fun `each thermal transition is logged with a short status label`() {
        val lines = mutableListOf<String>()
        val watcher = ThermalWatcher(app, log = { lines += it })
        watcher.start()

        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_MODERATE)
        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)

        assertEquals(
            listOf("thermal status -> MODERATE", "thermal status -> SEVERE"),
            lines,
        )
    }

    @Test fun `stop unregisters the listener so later transitions are silent`() {
        val lines = mutableListOf<String>()
        val watcher = ThermalWatcher(app, log = { lines += it })
        watcher.start()
        watcher.stop()

        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_CRITICAL)

        assertTrue("no line should be logged after stop()", lines.isEmpty())
    }

    @Test fun `stop before start is a safe no-op`() {
        val watcher = ThermalWatcher(app, log = {})
        watcher.stop() // must not throw
    }

    @Test fun `start is idempotent -- a second call does not register a second listener`() {
        val lines = mutableListOf<String>()
        val watcher = ThermalWatcher(app, log = { lines += it })
        watcher.start()
        watcher.start()

        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_LIGHT)

        assertEquals("a doubled-up registration would log this transition twice", 1, lines.size)
    }

    @Test fun `default wiring logs through DiagnosticLog under the Thermal tag`() {
        DiagnosticLog.clear(app)
        val watcher = ThermalWatcher(app) // production default: logs via DiagnosticLog
        watcher.start()

        shadowOf(powerManager).setCurrentThermalStatus(PowerManager.THERMAL_STATUS_SEVERE)

        val logged = DiagnosticLog.lastLines(app, 5)
        assertTrue(
            "expected a [Thermal] line mentioning SEVERE, got: $logged",
            logged.any { it.contains("[Thermal]") && it.contains("SEVERE") },
        )
    }

    @Test fun `label formatting covers every known status plus an unknown fallback`() {
        assertEquals("NONE", ThermalWatcher.thermalStatusLabel(PowerManager.THERMAL_STATUS_NONE))
        assertEquals("LIGHT", ThermalWatcher.thermalStatusLabel(PowerManager.THERMAL_STATUS_LIGHT))
        assertEquals("MODERATE", ThermalWatcher.thermalStatusLabel(PowerManager.THERMAL_STATUS_MODERATE))
        assertEquals("SEVERE", ThermalWatcher.thermalStatusLabel(PowerManager.THERMAL_STATUS_SEVERE))
        assertEquals("CRITICAL", ThermalWatcher.thermalStatusLabel(PowerManager.THERMAL_STATUS_CRITICAL))
        assertEquals("EMERGENCY", ThermalWatcher.thermalStatusLabel(PowerManager.THERMAL_STATUS_EMERGENCY))
        assertEquals("SHUTDOWN", ThermalWatcher.thermalStatusLabel(PowerManager.THERMAL_STATUS_SHUTDOWN))
        assertEquals("UNKNOWN(99)", ThermalWatcher.thermalStatusLabel(99))
    }
}
