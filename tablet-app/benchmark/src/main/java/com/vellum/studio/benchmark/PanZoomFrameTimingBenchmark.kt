package com.vellum.studio.benchmark

import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Canvas pan/zoom frame timing -- the formal, checked-in successor to the debug HUD that lives
 * only on device/galaxy-z-fold5 (never merged to main): DrawingCanvasView.recordFrameTimeSample()/
 * drawDebugFrameOverlay(), gated behind SettingsRepository.debugFrameOverlayEnabled, samples
 * onDraw()-to-onDraw() gaps into a 120-frame ring buffer and renders last/avg/dropped-120Hz-frame
 * counts as an on-screen HUD for a human tester holding a real S Pen to read. That's genuinely
 * useful for a human watching the glass live, but it self-reports from inside the process being
 * measured and produces nothing durable -- no file, no history, nothing CI or a future session can
 * diff against. This test measures the same underlying thing (real per-frame timing during canvas
 * pan/zoom) via Macrobenchmark's [FrameTimingMetric] instead, which reads the system's own actual
 * frame-timing trace data from a separate process, and reports reproducible p50/p90/p95/p99
 * frameDurationCpuMs numbers straight into tablet-app/benchmark-baseline.md.
 *
 * [setupBlock] drives the exact same "New Canvas" -> "Create" flow a real user follows to reach a
 * fresh Editor session (see ui/gallery/GalleryScreen.kt's NewCanvasDialog: the name field already
 * defaults to "Untitled" and the first size preset is pre-selected, so no text entry or explicit
 * chip tap is required) -- deliberately UNmeasured, since only the pan/zoom gestures in the
 * measured block below should count toward frame timing.
 *
 * Gestures are driven via [androidx.test.uiautomator.UiObject2]'s real synthesized multi-touch
 * gestures (swipe / pinchOpen / pinchClose), which Android delivers as genuine MotionEvents with
 * MotionEvent.TOOL_TYPE_FINGER -- the same finger-touch path DrawingCanvasView.onTouchEvent()
 * already branches on for its own pan/zoom handling (handleFingerDown/handleFingerMove), so this
 * exercises the real touch-routing code, not a shortcut around it. UiAutomator is required here
 * rather than a Compose-test API specifically because Macrobenchmark tests always run
 * out-of-process from the app under test -- there is no in-process Compose test tree to reach into.
 *
 * The canvas is located by contentDescription ("Drawing canvas", set on the DrawingCanvasView
 * instance in EditorScreen.kt) rather than by its fully-qualified class name: a class-name lookup
 * would work too, but would silently break if that class ever gets renamed/moved, and the
 * contentDescription is a real, independently-useful accessibility fix in its own right (this View
 * previously had none at all).
 *
 * The "New Canvas" FAB is located by contentDescription too, not [By.text] -- found out why the
 * hard way: a live `uiautomator dump` against GalleryScreen showed this
 * [androidx.compose.material3.ExtendedFloatingActionButton]'s icon+text slots never merged into any
 * accessible label at all (NAF="true", empty text AND empty content-desc, despite "New Canvas"
 * being clearly visible on screen) -- a real, pre-existing accessibility gap fixed alongside this
 * benchmark by adding an explicit `Modifier.semantics(mergeDescendants = true) { contentDescription
 * = "New Canvas" }` in GalleryScreen.kt. The dialog's "Create" [androidx.compose.material3.TextButton]
 * did NOT have this problem (confirmed via the same live dump) -- its text surfaces normally, so
 * [By.text] is used for it as-is.
 *
 * KNOWN SIDE EFFECT, kept honest rather than hidden: since there is no other reliable, minimally-
 * invasive way to reach a live DrawingCanvasView from a cold Gallery launch, each iteration's
 * setupBlock creates one real new "Untitled" project through the actual Gallery UI -- confirmed via
 * `adb shell pm list packages` immediately after a real run that these do NOT persist between
 * separate benchmark invocations: `connectedBenchmarkAndroidTest`'s own task lifecycle uninstalls
 * com.vellum.studio (wiping its data along with it) once the whole test session ends, the same way
 * it installs it fresh at the start. Within a single run's [ITERATIONS] iterations, though, they do
 * pile up one after another before that final uninstall -- harmless, but real, which is why
 * [ITERATIONS] is kept modest here rather than the higher count a full CI-grade run would typically
 * use.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class PanZoomFrameTimingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun panAndZoomCanvas() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.desc(NEW_CANVAS_DESC)), TIMEOUT_MS)
            device.findObject(By.desc(NEW_CANVAS_DESC)).click()
            device.wait(Until.hasObject(By.text("Create")), TIMEOUT_MS)
            device.findObject(By.text("Create")).click()
            device.wait(Until.hasObject(By.desc(CANVAS_DESC)), TIMEOUT_MS)
        },
    ) {
        val canvas = device.findObject(By.desc(CANVAS_DESC))
        // Same reasoning as the official fling/scroll examples: keep gestures away from the very
        // edge of the element so they can't be swallowed by an unrelated system-gesture-nav area.
        canvas.setGestureMargin(device.displayWidth / 5)

        // Pan: a single tracked finger already pans (DrawingCanvasView.handleFingerMove's count==1
        // branch translates canvasMatrix regardless of whether a second finger ever joins).
        repeat(3) {
            canvas.swipe(Direction.LEFT, 0.6f, GESTURE_SPEED)
            canvas.swipe(Direction.RIGHT, 0.6f, GESTURE_SPEED)
        }

        // Zoom + rotate: a real two-finger pinch (handleFingerMove's count>=2 branch).
        repeat(2) {
            canvas.pinchOpen(0.7f, GESTURE_SPEED)
            canvas.pinchClose(0.7f, GESTURE_SPEED)
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.vellum.studio"
        private const val NEW_CANVAS_DESC = "New Canvas"
        private const val CANVAS_DESC = "Drawing canvas"
        private const val ITERATIONS = 5
        private const val TIMEOUT_MS = 10_000L
        private const val GESTURE_SPEED = 1500 // pixels/second
    }
}
