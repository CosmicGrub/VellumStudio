package com.vellum.studio.model

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vellum.studio.canvas.PressureCurvePreset

/**
 * Small, app-wide settings store backed by [android.content.SharedPreferences] — a plain
 * key/value store is the right tool here (unlike the JSON-file repositories elsewhere in
 * `model/`) since this holds a handful of independent flags, not a structured document.
 * Compose-observable so a toggle in SettingsScreen and a read in EditorScreen both see the same
 * live value without needing a manual refresh.
 */
class SettingsRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vellum_settings", Context.MODE_PRIVATE)

    // A plain `by mutableStateOf(...)` delegated property can't also carry a custom setter (no
    // backing `field` exists once delegation handles get/set) -- so this is a regular property
    // with explicit accessors wrapping a private MutableState instead, giving both Compose
    // observability and SharedPreferences persistence on write.
    private val gpuCompositorState = mutableStateOf(prefs.getBoolean(KEY_GPU_COMPOSITOR, false))

    /**
     * Experimental GPU-accelerated layer compositor (see
     * [com.vellum.studio.canvas.gl.LayerCompositorGLView]) — off by default. Only ever affects
     * the idle/no-active-stroke view; the live-stroke software rendering path is unconditional
     * and unaffected either way.
     */
    var experimentalGpuCompositor: Boolean
        get() = gpuCompositorState.value
        set(value) {
            gpuCompositorState.value = value
            prefs.edit().putBoolean(KEY_GPU_COMPOSITOR, value).apply()
        }

    private val paperTextureEnabledState = mutableStateOf(prefs.getBoolean(KEY_PAPER_TEXTURE_ENABLED, false))
    private val paperTextureStrengthState = mutableStateOf(prefs.getFloat(KEY_PAPER_TEXTURE_STRENGTH, 0.35f))

    /**
     * Subtle tileable paper-grain texture, multiplied over the composited canvas (see
     * [com.vellum.studio.canvas.PaperTexture]) — off by default so no existing project's look
     * changes without the user opting in.
     */
    var paperTextureEnabled: Boolean
        get() = paperTextureEnabledState.value
        set(value) {
            paperTextureEnabledState.value = value
            prefs.edit().putBoolean(KEY_PAPER_TEXTURE_ENABLED, value).apply()
        }

    /** How strongly the grain shows through, 0..1. Only meaningful while [paperTextureEnabled]. */
    var paperTextureStrength: Float
        get() = paperTextureStrengthState.value
        set(value) {
            paperTextureStrengthState.value = value
            prefs.edit().putFloat(KEY_PAPER_TEXTURE_STRENGTH, value).apply()
        }

    private val pressureCurvePresetState = mutableStateOf(
        prefs.getString(KEY_PRESSURE_CURVE_PRESET, null)
            ?.let { name -> PressureCurvePreset.entries.firstOrNull { it.name == name } }
            ?: PressureCurvePreset.LINEAR,
    )
    private val pressureCurveGammaState = mutableStateOf(prefs.getFloat(KEY_PRESSURE_CURVE_GAMMA, PressureCurvePreset.LINEAR.gamma))

    /**
     * Which preset chip is selected in Settings' "Pressure curve" card -- purely for the UI to know
     * which chip to highlight. The actual gamma DrawingCanvasView reads is always
     * [pressureCurveGamma]; picking a non-[PressureCurvePreset.CUSTOM] preset here immediately
     * snaps [pressureCurveGamma] to that preset's own fixed value, so the two never disagree.
     * Dragging the continuous slider instead sets this to [PressureCurvePreset.CUSTOM] (see
     * SettingsScreen) without touching this setter's snap-to-preset behavior.
     */
    var pressureCurvePreset: PressureCurvePreset
        get() = pressureCurvePresetState.value
        set(value) {
            pressureCurvePresetState.value = value
            prefs.edit().putString(KEY_PRESSURE_CURVE_PRESET, value.name).apply()
            if (value != PressureCurvePreset.CUSTOM) {
                pressureCurveGamma = value.gamma
            }
        }

    /** The gamma actually fed into [com.vellum.studio.canvas.applyPressureCurve] at the input
     * layer -- always current regardless of which preset (if any) it matches. */
    var pressureCurveGamma: Float
        get() = pressureCurveGammaState.value
        set(value) {
            pressureCurveGammaState.value = value
            prefs.edit().putFloat(KEY_PRESSURE_CURVE_GAMMA, value).apply()
        }

    private companion object {
        const val KEY_GPU_COMPOSITOR = "experimental_gpu_compositor"
        const val KEY_PAPER_TEXTURE_ENABLED = "paper_texture_enabled"
        const val KEY_PAPER_TEXTURE_STRENGTH = "paper_texture_strength"
        const val KEY_PRESSURE_CURVE_PRESET = "pressure_curve_preset"
        const val KEY_PRESSURE_CURVE_GAMMA = "pressure_curve_gamma"
    }
}
