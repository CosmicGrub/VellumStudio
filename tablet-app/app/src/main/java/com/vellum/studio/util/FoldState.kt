package com.vellum.studio.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 */
data class FoldState(
    val posture: FoldPosture,
    val hingeBounds: Rect? = null,
)

private val NoFoldState = FoldState(FoldPosture.NO_FOLD_FEATURE)

/**
 * Observes the current [FoldState] for the nearest owning Activity of [LocalContext], via
 * [WindowInfoTracker.windowLayoutInfo]. Recomposes whenever the hinge posture changes (fold,
 * unfold, rotate into/out of tabletop mode, etc.) for as long as this composable is in a started
 * lifecycle.
 *
 * Falls back to [FoldPosture.NO_FOLD_FEATURE] if no owning Activity can be found - shouldn't
 * happen for a screen hosted the normal way (setContent on a ComponentActivity), but this avoids
 * a hard-cast crash in case of an unusual host context instead.
 */
@Composable
fun rememberFoldState(): FoldState {
    val activity = LocalContext.current.findActivity() ?: return NoFoldState

    val flow = remember(activity) {
        WindowInfoTracker.getOrCreate(activity)
            .windowLayoutInfo(activity)
            .map { it.toFoldState() }
    }
    return flow.collectAsStateWithLifecycle(initialValue = NoFoldState).value
}

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
