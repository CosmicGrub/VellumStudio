package com.vellum.studio.canvas

import kotlin.math.pow

/**
 * Pure math for reshaping raw hardware pressure (0..1, straight off a [android.view.MotionEvent])
 * before it reaches the brush pipeline. Applied entirely at the INPUT layer in
 * [DrawingCanvasView] -- every call site there that currently reads `event.getPressure()` /
 * `event.getHistoricalPressure()` runs the raw value through [applyPressureCurve] before building
 * an [InputSample], so [StrokeRenderer] only ever sees an already-curved pressure value and never
 * needs to know this concept exists.
 *
 * [SOFT] bows the curve so pressure reaches its full effect with a lighter touch; [FIRM] bows it
 * the other way so it takes real force to get there; [LINEAR] passes pressure through unchanged.
 * [CUSTOM] is not a fixed curve at all -- it's what the UI sets when the user drags the continuous
 * gamma slider to a value that doesn't match any preset; the actual gamma to use always lives in
 * `SettingsRepository.pressureCurveGamma`, never in this enum's own [gamma] field for that case.
 */
enum class PressureCurvePreset(val label: String, val gamma: Float) {
    SOFT("Soft", 1.8f),
    LINEAR("Linear", 1f),
    FIRM("Firm", 0.55f),

    /** Placeholder only -- a CUSTOM curve's real gamma is whatever the slider was last set to,
     * read from `SettingsRepository.pressureCurveGamma`, not from this field. */
    CUSTOM("Custom", 1f),
}

/** Slider bounds for the continuous "custom" gamma control -- wide enough to comfortably reach
 * (and go a bit past) both [PressureCurvePreset.SOFT] and [PressureCurvePreset.FIRM]. */
object PressureCurveRange {
    const val MIN_GAMMA = 0.3f
    const val MAX_GAMMA = 3f
}

/**
 * Raises [rawPressure] to [gamma]. `gamma == 1` (or anything non-positive/NaN, defensively) is a
 * no-op passthrough so a corrupt or uninitialized setting can never divide-by-zero or NaN-poison a
 * stroke's pressure -- it just draws as if the curve were off.
 */
fun applyPressureCurve(rawPressure: Float, gamma: Float): Float {
    val clampedPressure = rawPressure.coerceIn(0f, 1f)
    if (gamma.isNaN() || gamma <= 0f || gamma == 1f) return clampedPressure
    return clampedPressure.pow(gamma).coerceIn(0f, 1f)
}
