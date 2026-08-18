package com.vellum.studio.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import com.vellum.studio.VellumApp
import com.vellum.studio.util.DeviceCapabilities

/**
 * Which input mode the stylus is in: freehand strokes, tap-to-fill a bounded region with the
 * current color, or tap-to-fill a bounded region with ITS pre-assigned color (paint by numbers).
 */
enum class ToolMode { BRUSH, FILL, PAINT_BY_NUMBER, SELECT }

/**
 * Holds everything a canvas editing session needs: the layer stack, the currently selected tool,
 * and the undo history. Deliberately framework-light (no Compose UI here) so it's easy to unit test
 * and to host from both the editor screen and a future headless export/thumbnail path.
 */
class CanvasEngine(val widthPx: Int, val heightPx: Int) {

    val layers: SnapshotStateList<Layer> = mutableListOf<Layer>().toMutableStateList()
    var activeLayerIndex by mutableIntStateOf(0)

    var currentBrush by mutableStateOf(BrushPresets.Pencil)
    var currentColorArgb by mutableIntStateOf(Color.BLACK)

    // Keyed per-brush-id (default 1f when absent) rather than two flat fields: brush baseSizePx
    // already varies hugely across the lineup (Fineliner 4f vs Watercolor/Airbrush 70-90f), so a
    // single global multiplier tuned for one brush would silently amplify or dampen the NEXT,
    // completely different brush the moment the user switches - e.g. dialing Pencil up to 4x, then
    // switching to Watercolor, would blow that Watercolor stroke out to 4x its own tuned width with
    // no warning. Exposed as simple Float properties below so callers (BrushBar's sliders,
    // DrawingCanvasView's StrokeRenderer construction) don't need to know this is per-brush at all.
    private val sizeMultipliers = mutableStateMapOf<String, Float>()
    private val opacityMultipliers = mutableStateMapOf<String, Float>()

    var brushSizeMultiplier: Float
        get() = sizeMultipliers[currentBrush.id] ?: 1f
        set(value) { sizeMultipliers[currentBrush.id] = value }

    var brushOpacityMultiplier: Float
        get() = opacityMultipliers[currentBrush.id] ?: 1f
        set(value) { opacityMultipliers[currentBrush.id] = value }

    var currentTool by mutableStateOf(ToolMode.BRUSH)

    /** See [SymmetryMode] -- while not NONE, DrawingCanvasView stamps every dab at its mirrored/
     * rotated position(s) too, live, alongside the real stroke. */
    var symmetryMode by mutableStateOf(SymmetryMode.NONE)

    /**
     * Rectangular selection, canvas-space, only meaningful in [ToolMode.SELECT] -- editing-session
     * UI state (like [currentTool] and [symmetryMode]), not persisted project data. Null means no
     * active selection. DrawingCanvasView owns the actual drag gesture that defines/moves it.
     */
    var selectionRect by mutableStateOf<RectF?>(null)

    /**
     * Cached region map for paint-by-number mode; recomputed lazily, see [regionsForPaintByNumber].
     * Keyed on (active layer, [revision]) rather than just the active layer index - [revision] is
     * already bumped by every single content-affecting mutation in this class (strokes, fills,
     * layer add/delete/move/opacity/visibility/blend-mode), so piggybacking on it means the cache
     * self-invalidates whenever the boundary content could plausibly have changed, without needing
     * every call site that might affect it to separately remember to call [invalidateRegionMap].
     */
    var regionMap by mutableStateOf<RegionMap?>(null)
        private set
    private var regionMapLayerIndex = -1
    private var regionMapRevision = -1

    // Each undo step owns two full-canvas ARGB_8888 bitmaps (before + after); cap total history
    // memory rather than a fixed step count so large canvases don't blow the heap.
    val undoManager = UndoManager(maxDepth = computeUndoDepth())

    private fun computeUndoDepth(): Int {
        val bytesPerBitmap = widthPx.toLong() * heightPx.toLong() * 4L
        if (bytesPerBitmap <= 0L) return 6
        // Device-scaled budget (was a flat 180MB regardless of hardware) -- see
        // DeviceCapabilities.undoBudgetBytes() for why: a higher-RAM device earns real extra
        // undo depth instead of the same fixed ceiling as a much more constrained one.
        val budgetBytes = DeviceCapabilities.undoBudgetBytes()
        val depth = (budgetBytes / (bytesPerBitmap * 2)).toInt()
        return depth.coerceIn(6, 60)
    }

    /**
     * Set by DrawingCanvasView while a stylus stroke is in flight, so layer-structure mutations
     * below (delete/move) can refuse to touch the layer a stroke still holds a live Bitmap/Canvas
     * reference to. Without this, a second pointer reaching a Layers panel button (e.g. a finger
     * tapping "Delete Layer" while the stylus is still drawing - stylus-only palm rejection only
     * applies to touches that reach DrawingCanvasView itself, not to a sibling Compose panel) can
     * recycle or reorder out from under the in-progress StrokeRenderer, crashing on the next dab.
     */
    var strokeInProgressLayerId: String? = null

    /** Reused scratch bitmap for non-buildUp strokes; sized to the full canvas, cleared per-stroke. */
    val strokeScratch: Bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    private val scratchCanvas = Canvas(strokeScratch)
    private val compositePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Bumped on every committed stroke/layer change so listeners (thumbnails, autosave) can react. */
    var revision by mutableIntStateOf(0)
        private set

    fun activeLayer(): Layer? = layers.getOrNull(activeLayerIndex)

    fun addLayer(name: String? = null, aboveActive: Boolean = true): Layer {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        return insertLayer(Layer(name = name ?: "Layer ${layers.size + 1}", bitmap = bmp), aboveActive)
    }

    /**
     * Imports [source] as a new layer, letterbox-fit (scaled to fit within the canvas, centered,
     * aspect preserved -- same convention as the real-masterwork coloring templates) rather than
     * stretched. Meant for bringing in a reference photo to sketch/trace over: the new layer is
     * ordinary in every other way (opacity/lock/blend all work normally), so a user who wants to
     * use it as a dimmed, locked underlay just adjusts those from the Layers panel themselves --
     * nothing here is a special "reference layer" mode baked into the engine.
     */
    fun addImageLayer(name: String, source: Bitmap, aboveActive: Boolean = true): Layer {
        val fitted = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(fitted)
        val scale = minOf(widthPx.toFloat() / source.width, heightPx.toFloat() / source.height)
        val drawW = source.width * scale
        val drawH = source.height * scale
        val left = (widthPx - drawW) / 2f
        val top = (heightPx - drawH) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + drawW, top + drawH), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return insertLayer(Layer(name = name, bitmap = fitted), aboveActive)
    }

    private fun insertLayer(layer: Layer, aboveActive: Boolean): Layer {
        val insertAt = if (layers.isEmpty()) 0 else (activeLayerIndex + if (aboveActive) 1 else 0)
        val clampedInsertAt = insertAt.coerceIn(0, layers.size)
        // If a stroke is live on the active layer, an insert at-or-before its index (aboveActive =
        // false inserts AT activeLayerIndex, pushing the stroked layer up by one) silently shifts
        // what activeLayerIndex numerically points at even without us touching the field directly -
        // re-locate the actual stroked Layer object after inserting instead of jumping to the new
        // layer, so a second pointer adding a layer mid-stroke doesn't redirect the NEXT stroke onto
        // a layer the user never selected. See strokeInProgressLayerId's doc comment.
        val strokedLayer = strokeInProgressLayerId?.let { id -> layers.getOrNull(activeLayerIndex)?.takeIf { it.id == id } }
        layers.add(clampedInsertAt, layer)
        activeLayerIndex = if (strokedLayer != null) {
            layers.indexOf(strokedLayer).coerceAtLeast(0)
        } else {
            clampedInsertAt.coerceIn(0, layers.size - 1)
        }
        bumpRevision()
        return layer
    }

    fun duplicateActiveLayer() {
        val src = activeLayer() ?: return
        val copy = Layer(name = "${src.name} copy", bitmap = src.snapshot(), opacity = src.opacity, visible = src.visible, blendMode = src.blendMode)
        layers.add(activeLayerIndex + 1, copy)
        // Keep focus on the layer actually being stroked right now instead of jumping to the new
        // duplicate - see strokeInProgressLayerId's doc comment. Inserting strictly after
        // activeLayerIndex never shifts what that index itself points at, so (unlike addLayer, which
        // can insert AT-OR-BEFORE the active index) no re-lookup by identity is needed here.
        if (src.id != strokeInProgressLayerId) {
            activeLayerIndex += 1
        }
        bumpRevision()
    }

    fun deleteActiveLayer() {
        if (layers.size <= 1) return
        val layer = layers.getOrNull(activeLayerIndex) ?: return
        if (layer.id == strokeInProgressLayerId) return
        layers.removeAt(activeLayerIndex)
        layer.bitmap.recycle()
        activeLayerIndex = activeLayerIndex.coerceIn(0, layers.size - 1)
        bumpRevision()
    }

    fun moveActiveLayer(delta: Int) {
        val from = activeLayerIndex
        val to = (from + delta).coerceIn(0, layers.size - 1)
        if (to == from) return
        val layer = layers[from]
        if (layer.id == strokeInProgressLayerId) return
        layers.removeAt(from)
        layers.add(to, layer)
        activeLayerIndex = to
        bumpRevision()
    }

    fun bumpRevision() {
        revision++
    }

    // Layer property mutations funnel through here (rather than setting Layer fields directly from
    // the UI) so every visible change reliably bumps [revision] — the plain-View drawing surface
    // isn't part of Compose's snapshot system, so it relies on watching this counter to know when
    // to invalidate.
    fun setLayerOpacity(layer: Layer, opacity: Float) {
        layer.opacity = opacity.coerceIn(0f, 1f)
        bumpRevision()
    }

    fun setLayerVisible(layer: Layer, visible: Boolean) {
        layer.visible = visible
        bumpRevision()
    }

    fun setLayerBlendMode(layer: Layer, mode: LayerBlendMode) {
        layer.blendMode = mode
        bumpRevision()
    }

    fun setLayerLocked(layer: Layer, locked: Boolean) {
        layer.locked = locked
        bumpRevision()
    }

    /** Prepares (and clears) the shared scratch canvas used by non-buildUp strokes. */
    fun scratch(): Canvas {
        strokeScratch.eraseColor(Color.TRANSPARENT)
        return scratchCanvas
    }

    /**
     * Composites the scratch bitmap onto [layer] once, at the brush's stroke-level opacity cap.
     * Normal brushes use SRC_OVER (paint onto the layer); [erasing] switches to DST_OUT, which
     * subtracts the scratch mask's alpha from the layer instead — the graduated-soft-eraser trick
     * described on [StrokeRenderer]'s class doc. [mixing] switches to MULTIPLY so a capped,
     * pigment-mixing brush (pastel) genuinely darkens/blends into whatever's already on the layer
     * when the stroke commits, not just against itself while it was still in the scratch buffer.
     * [wetness] (from [Brush.wetness]), when above 0, applies a real [BlurMaskFilter] to this one
     * commit — a genuine softening/spreading of the stroke's edges, not the opacity-jitter
     * approximation the brush's per-dab rendering otherwise relies on. This runs once per
     * stroke (at commit, not per live-preview frame), so the cost of a mask blur here is a
     * non-issue compared to what it would be in the real-time preview path.
     */
    fun flattenScratchOnto(layer: Layer, alpha: Float, erasing: Boolean = false, mixing: Boolean = false, wetness: Float = 0f) {
        val canvas = Canvas(layer.bitmap)
        compositePaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        compositePaint.blendMode = when {
            erasing -> BlendMode.DST_OUT
            mixing -> BlendMode.MULTIPLY
            else -> null
        }
        if (wetness > 0f) {
            // Radius scales with canvas resolution so the same wetness value looks the same
            // relative amount of "bleed" regardless of what size canvas it's painted on.
            val radius = (widthPx.coerceAtMost(heightPx) * 0.006f * wetness).coerceAtLeast(1f)
            compositePaint.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(strokeScratch, 0f, 0f, compositePaint)
        compositePaint.maskFilter = null
        compositePaint.blendMode = null
        compositePaint.alpha = 255
    }

    /**
     * Composites every VISIBLE layer stacked above [layerIndex] into a fresh transparent bitmap —
     * the "walls" the bucket-fill tool stops at. This is what makes fill respect line art drawn on
     * a layer above the one you're coloring on (the standard coloring-book layer convention: line
     * art on top, locked; color layers underneath) without needing any special "this is line art"
     * flag on Layer — any opaque content above the active layer blocks the fill, full stop. Caller
     * owns the returned bitmap and must recycle it.
     */
    fun boundaryMaskAbove(layerIndex: Int): Bitmap {
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in (layerIndex + 1) until layers.size) {
            val layer = layers[i]
            if (!layer.visible || layer.opacity <= 0f) continue
            paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            paint.blendMode = layer.blendMode.blendMode
            canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
            paint.blendMode = null
        }
        return out
    }

    /**
     * Computes (or returns the cached) [RegionMap] for the active layer's boundary — every
     * enclosed area bounded by whatever's on the layers above it, numbered and pre-colored. See the
     * [regionMap] field doc for how the cache invalidates automatically via [revision].
     */
    fun regionsForPaintByNumber(): RegionMap {
        val cached = regionMap
        if (cached != null && regionMapLayerIndex == activeLayerIndex && regionMapRevision == revision) return cached
        val boundary = boundaryMaskAbove(activeLayerIndex)
        val computed = try {
            RegionAnalyzer.analyze(boundary)
        } finally {
            boundary.recycle()
        }
        regionMap = computed
        regionMapLayerIndex = activeLayerIndex
        regionMapRevision = revision
        return computed
    }

    /** Rarely needed explicitly now that the cache also keys on [revision] - kept for callers that want to force a recompute. */
    fun invalidateRegionMap() {
        regionMap = null
        regionMapLayerIndex = -1
        regionMapRevision = -1
    }

    /**
     * Flattens all visible layers into one bitmap, honoring opacity + blend mode, for
     * export/thumbnails/print/the PC mirror stream. Reads the paper-texture setting straight off
     * [VellumApp.instance] rather than taking it as a parameter (same reasoning as the
     * Application-context doc comment on [VellumApp.instance] itself) so every one of this
     * method's several call sites - none of which otherwise touch [com.vellum.studio.model.SettingsRepository] -
     * automatically stays honest with what's actually on screen without each needing its own wiring.
     */
    fun flatten(): Bitmap {
        val out = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            paint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            paint.blendMode = layer.blendMode.blendMode
            canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
            paint.blendMode = null
        }
        val settings = VellumApp.instance.settingsRepository
        if (settings.paperTextureEnabled) {
            paint.shader = PaperTexture.shader
            paint.blendMode = BlendMode.MULTIPLY
            paint.alpha = (PaperTexture.clampStrength(settings.paperTextureStrength) * 255).toInt()
            canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), paint)
            paint.shader = null
            paint.blendMode = null
        }
        return out
    }

    fun recycleAll() {
        layers.forEach { it.bitmap.recycle() }
        strokeScratch.recycle()
        undoManager.clear()
    }
}
