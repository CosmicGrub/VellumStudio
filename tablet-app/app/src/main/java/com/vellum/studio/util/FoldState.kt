package com.vellum.studio.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.map

/**
 * Coarse fold posture derived from androidx.window's [WindowInfoTracker], for screens that want
 * to adapt their layout around a foldable's hinge (specifically the Z Fold5's "propped up like a
 * laptop" tabletop posture) instead of treating every device as a plain rectangle.
 *
 * This is purely a layout signal - it has nothing to do with input routing. Drawing stays
 * stylus-exclusive in every posture (see DrawingCanvasView's class doc); this enum only ever
 * drives *composition* (which panes exist, where), never touches the canvas input path.
 */
enum class FoldPosture {
    /**
     * Fully open with no active fold. Also what [HALF_OPENED_OTHER] devices are treated as by
     * callers - a vertical hinge (book-held-ajar) isn't worth a dedicated layout, so screens
     * should render it exactly like FLAT.
     */
    FLAT,

    /**
     * [FoldingFeature.State.HALF_OPENED] with a [FoldingFeature.Orientation.HORIZONTAL] hinge -
     * the device propped up like a laptop, with the hinge running left-to-right across the
     * screen. The one posture worth a dedicated split layout.
     */
    HALF_OPENED_TABLETOP,

    /**
     * [FoldingFeature.State.HALF_OPENED] with a [FoldingFeature.Orientation.VERTICAL] hinge
     * (book-held-ajar). Not worth a special layout - callers should treat this like [FLAT].
     */
    HALF_OPENED_OTHER,

    /**
     * No [FoldingFeature] reported at all: the Z Fold5's cover/outer screen, a non-foldable-aware
     * display, or a plain non-foldable device. Callers should treat this like [FLAT] too.
     */
    NO_FOLD_FEATURE,
}

/**
 * [FoldPosture] plus the hinge's bounds, populated only in [FoldPosture.HALF_OPENED_TABLETOP].
 * [hingeBounds] is in whatever coordinate space androidx.window reports it in (the Activity
 * window's pixel space) - callers that only need the *size* of the gap (e.g. sizing a Spacer so
 * nothing sits under the crease) can use its width/height directly without worrying about
 * absolute window position.
 *
 * [hingeAngleDegrees] is a second, independent signal: the live 0-180 degree reading from
 * [Sensor.TYPE_HINGE_ANGLE] (180 = fully flat, 0 = fully closed), when that sensor exists on the
 * device and has reported at least one value. It is populated regardless of [posture] -- a caller
 * that only cares about it in [FoldPosture.HALF_OPENED_TABLETOP] should gate on posture itself,
 * same as [hingeBounds]. Null means "no reading yet available" (no such sensor on this device, or
 * no event delivered yet), never a meaningful angle -- callers must treat it as optional and fall
 * back to a fixed layout in that case, exactly like before this field existed.
 */
data class FoldState(
    val posture: FoldPosture,
    val hingeBounds: Rect? = null,
    val hingeAngleDegrees: Float? = null,
)

private val NoFoldState = FoldState(FoldPosture.NO_FOLD_FEATURE)

/**
 * Observes the current [FoldState] for the nearest owning Activity of [LocalContext], via
 * [WindowInfoTracker.windowLayoutInfo] for the discrete [FoldPosture] (unchanged from before) and
 * [Sensor.TYPE_HINGE_ANGLE] for the continuous [FoldState.hingeAngleDegrees] layered on top.
 * Recomposes whenever the hinge posture changes (fold, unfold, rotate into/out of tabletop mode,
 * etc.) or the hinge-angle sensor reports a new value, for as long as this composable is in a
 * started lifecycle.
 *
 * Falls back to [FoldPosture.NO_FOLD_FEATURE] if no owning Activity can be found - shouldn't
 * happen for a screen hosted the normal way (setContent on a ComponentActivity), but this avoids
 * a hard-cast crash in case of an unusual host context instead. [FoldState.hingeAngleDegrees] on
 * that fallback path is always null, matching the "no sensor" case.
 */
@Composable
fun rememberFoldState(): FoldState {
    val activity = LocalContext.current.findActivity() ?: return NoFoldState

    val flow = remember(activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .map { it.toFoldState() }
    }
    val postureState = flow.collectAsStateWithLifecycle(initialValue = NoFoldState)
    val hingeAngle = rememberHingeAngleDegrees(activity)
    return postureState.value.copy(hingeAngleDegrees = hingeAngle)
}

/**
 * Registers a [SensorEventListener] on [Sensor.TYPE_HINGE_ANGLE] (a standard public Android
 * Sensor, no special permission required) for the lifetime of the composition, unregistering on
 * dispose. Returns null whenever there's no such sensor on this device, or no reading has arrived
 * yet -- see [FoldState.hingeAngleDegrees]'s doc for how callers should treat that.
 *
 * SENSOR_DELAY_UI is intentionally coarse (not SENSOR_DELAY_GAME/FASTEST): this only drives a
 * layout-weight recompute, not per-frame rendering, so there's no reason to wake the sensor at a
 * rate finer than the UI actually redraws at.
 */
@Composable
private fun rememberHingeAngleDegrees(activity: Activity): Float? {
    val sensorManager = remember(activity) {
        activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val hingeSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    }
    var angle by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(sensorManager, hingeSensor) {
        if (sensorManager == null || hingeSensor == null) {
            angle = null
            return@DisposableEffect onDispose {}
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                angle = event.values.firstOrNull()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, hingeSensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensorManager.unregisterListener(listener) }
    }
    return angle
}

/**
 * Maps a live [FoldState.hingeAngleDegrees] reading to the "primary" pane's weight in a
 * two-pane tabletop split (the other pane gets `1f - result`), so a [HALF_OPENED_TABLETOP]
 * layout's split tracks the physical hinge continuously instead of using a fixed ratio. Shared
 * between [com.vellum.studio.ui.editor.EditorScreen]'s canvas/tools split and Academy's
 * lesson-demo/controls split so both panels grow and shrink the same way as the hinge moves,
 * rather than each screen inventing its own curve.
 *
 * 180 degrees (near-flat, the widest a tabletop posture still gets classified) gives the primary
 * pane the most room; as the hinge folds closed toward 90 degrees the primary pane shrinks and
 * the secondary pane grows to match. Clamped to [90, 180] and the result further clamped to
 * [MIN_PRIMARY_WEIGHT, MAX_PRIMARY_WEIGHT] so neither pane ever collapses to an unusably small
 * sliver at either extreme. Falls back to [DEFAULT_PRIMARY_WEIGHT] when no angle is available
 * (sensor missing or no reading yet) -- e.g. an emulator, or the brief window before the first
 * sensor event arrives.
 */
fun primaryPaneWeightForHingeAngle(angleDegrees: Float?): Float {
    val angle = angleDegrees ?: return DEFAULT_PRIMARY_WEIGHT
    val clamped = angle.coerceIn(90f, 180f)
    val t = (clamped - 90f) / (180f - 90f)
    return (MIN_PRIMARY_WEIGHT + t * (MAX_PRIMARY_WEIGHT - MIN_PRIMARY_WEIGHT))
        .coerceIn(MIN_PRIMARY_WEIGHT, MAX_PRIMARY_WEIGHT)
}

private const val MIN_PRIMARY_WEIGHT = 0.4f
private const val MAX_PRIMARY_WEIGHT = 0.7f
private const val DEFAULT_PRIMARY_WEIGHT = 0.6f

/**
 * True when the current window is narrow enough to treat as a foldable's cover/outer screen (the
 * Z Fold5's ~344dp-wide closed-state display) or any similarly narrow phone-width window, rather
 * than the tablet/foldable-main-screen widths this app otherwise targets. Uses Compose Material's
 * standard 600dp compact-width breakpoint (the same cutoff [androidx.window.core.layout.WindowWidthSizeClass]
 * uses) instead of a device-specific magic number, so this keeps working correctly on any other
 * narrow-cover-screen foldable, not just this one.
 *
 * Deliberately independent of [FoldPosture] -- a plain non-foldable phone is just as "compact" as
 * a folded Z Fold5, and both should get the same simplified layout, whereas [FoldPosture] only
 * has an opinion about foldables that report a [androidx.window.layout.FoldingFeature] at all.
 */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 600

private fun WindowLayoutInfo.toFoldState(): FoldState {
    val folding = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        ?: return NoFoldState
    return when {
        folding.state != FoldingFeature.State.HALF_OPENED -> FoldState(FoldPosture.FLAT)
        folding.orientation == FoldingFeature.Orientation.HORIZONTAL ->
            FoldState(FoldPosture.HALF_OPENED_TABLETOP, folding.bounds)
        else -> FoldState(FoldPosture.HALF_OPENED_OTHER)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
