package com.vellum.studio.canvas.gl

import android.content.Context
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import com.vellum.studio.canvas.CanvasEngine

/**
 * Experimental GPU-accelerated compositor (see [CompositorRenderer] for the actual GL work) —
 * deliberately isolated from [com.vellum.studio.canvas.DrawingCanvasView]'s input handling and
 * live-stroke rendering, which stays completely untouched. This view only ever renders a static
 * snapshot of the engine's current layers; EditorScreen swaps it in as an overlay ONLY when:
 *
 *  - the experimental setting is on,
 *  - no stroke is currently in progress, and
 *  - every visible layer's blend mode is one of [CompositorRenderer.GPU_SUPPORTED_BLEND_MODES]
 *    (Normal, Multiply, or Screen — see [CompositorRenderer]'s private `applyBlendMode` for
 *    exactly why those three and not the rest of [com.vellum.studio.canvas.LayerBlendMode]'s full
 *    list).
 *
 * The instant any of those flips, EditorScreen stops showing this view and the proven software
 * `DrawingCanvasView.onDraw()` path (which never stopped rendering underneath — this is purely an
 * on-top overlay, not a replacement) is what's visible again. If this regresses anything, turning
 * the setting off instantly reverts to the exact software path that was already hardened this
 * session, with zero other code changes needed.
 *
 * Not yet handling: live-stroke rendering, and the blend modes outside
 * [CompositorRenderer.GPU_SUPPORTED_BLEND_MODES] (Overlay/Darken/Lighten/Color Dodge/Color
 * Burn/Hard Light/Soft Light/Difference/Exclusion/Hue/Saturation/Color/Luminosity — each is
 * genuinely infeasible in a single GLES2 blend-factor pass, not merely unimplemented; see the
 * doc on [CompositorRenderer.applyBlendMode] for why each one specifically doesn't fit). Extending
 * live-stroke rendering is real future work; extending the excluded blend modes would need either
 * a multi-pass render-to-texture approach or a framebuffer-fetch extension, both out of scope here.
 */
class LayerCompositorGLView(context: Context) : GLSurfaceView(context) {
    private val compositorRenderer = CompositorRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(compositorRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        // Transparent surface + drawn above its sibling in the same Compose Box, so only the
        // layers this renderer actually draws are visible -- nothing GL doesn't cover is hidden.
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    fun attach(engine: CanvasEngine, matrixProvider: () -> Matrix) {
        compositorRenderer.attach(engine, matrixProvider)
    }

    /** Call whenever the engine's content or the pan/zoom/rotate transform changes. */
    fun requestComposite() = requestRender()
}
