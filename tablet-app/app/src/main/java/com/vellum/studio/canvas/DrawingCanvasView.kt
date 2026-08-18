package com.vellum.studio.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vellum.studio.VellumApp
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The actual drawing surface. Deliberately a plain [View] (not Compose) so strokes rasterize with
 * the least possible latency between a S Pen sample arriving and pixels changing on screen.
 *
 * Input routing:
 *  - A stylus/eraser pointer owns the stroke exclusively (only one at a time) and rasterizes
 *    directly (or via the engine's scratch layer — see [CanvasEngine.flattenScratchOnto]).
 *  - Finger pointers drive pan (1 finger) / pan+pinch-zoom+rotate (2 fingers) navigation.
 *  - While a stylus stroke is active, all finger input is swallowed (palm rejection).
 */
class DrawingCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var engine: CanvasEngine? = null
        private set

    var onStrokeCommitted: (() -> Unit)? = null

    /**
     * Fires with `true` right as a stroke starts and `false` when it ends/cancels. Exists purely
     * so an external, optional overlay (the experimental GPU compositor — see
     * [com.vellum.studio.canvas.gl.LayerCompositorGLView]) can know when it's safe to show itself
     * without this view needing to know that overlay exists at all. Null by default, zero cost
     * and zero behavior change for anyone who doesn't set it — same pattern as [onStrokeCommitted].
     */
    var onStrokeActiveChanged: ((Boolean) -> Unit)? = null

    /** Fires whenever pan/zoom/rotate changes [canvasMatrix] — same "optional external listener,
     * zero behavior change if unset" reasoning as [onStrokeActiveChanged]. */
    var onTransformChanged: (() -> Unit)? = null

    /** A defensive copy of the current pan/zoom/rotate transform, safe to read from another
     * thread (e.g. the GL compositor's render thread) without holding a reference to the live,
     * still-mutating instance this view keeps updating on its own thread. */
    fun currentMatrixSnapshot(): Matrix = Matrix(canvasMatrix)

    private val canvasMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private var hasInitializedView = false

    private val backgroundPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    // Shares PaperTexture.shader with CanvasEngine.flatten() -- this is purely the live-view
    // twin of that same overlay so drawing looks the way an export of it will look, not a
    // second definition of the effect.
    private val paperTexturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = PaperTexture.shader
        blendMode = BlendMode.MULTIPLY
    }
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scratchPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33FFFFFF }
    private val numberLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberLabelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2E90FA.toInt()
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    // --- stylus stroke state ---
    private var strokePointerId = -1
    private var strokeRenderer: StrokeRenderer? = null
    private var strokeTargetCanvas: Canvas? = null
    private var strokeTargetLayer: Layer? = null
    private var strokeUsesScratch = false
    private var pendingStroke: UndoManager.PendingStroke? = null

    // --- selection tool state (ToolMode.SELECT only; entirely separate pointer tracking from a
    // stroke's, so this never interacts with the stroke-ownership logic above at all) ---
    private var selectionPointerId = -1
    private var selectionDefining = false // true = dragging out a new rect; false = moving the existing one
    private var selectionAnchorCanvasX = 0f // DEFINING: the fixed corner. MOVING: the drag-start point.
    private var selectionAnchorCanvasY = 0f
    private var selectionMoveOriginalRect: RectF? = null // MOVING only: the rect's position before this drag

    // Symmetry/mirror drawing (see SymmetryMode): each entry is an independent StrokeRenderer --
    // reusing the exact same, already-hardened start()/moveTo() dab-spacing/tilt logic rather than
    // touching it at all -- paired with the coordinate transform that produces its mirrored input
    // from the real stroke's samples. All mirror renderers stamp onto the same strokeTargetCanvas
    // as the real stroke, so they composite and commit together with zero extra wiring elsewhere.
    private var mirrorRenderers: List<Pair<StrokeRenderer, (Float, Float) -> android.graphics.PointF>> = emptyList()
    private val samplePoint = FloatArray(2)

    // toCanvasSpace() writes here instead of returning a boxed Pair<Float,Float> - called once per
    // historical sample plus once for the live sample on every ACTION_MOVE, so on a fast S Pen
    // (report rates well past 100Hz) that's a meaningful amount of avoidable per-sample allocation.
    private var canvasX = 0f
    private var canvasY = 0f

    // The most recent stroke-dirty region in canvas space, reused as saveLayer's bounds in onDraw()
    // (see there) instead of the full canvas - saveLayer is one of the most expensive Canvas
    // operations, and a fast eraser/Pastel drag can trigger it dozens of times a second; bounding it
    // to only what actually changed keeps that cost proportional to the stroke instead of the canvas.
    private val strokeDirtyBoundsCanvasSpace = RectF()
    private var hasStrokeDirtyBounds = false

    // --- finger navigation state ---
    private val navPointerIds = mutableListOf<Int>()
    private var navBaselineSet = false
    private var navLastFocusX = 0f
    private var navLastFocusY = 0f
    private var navLastDist = 0f
    private var navLastAngle = 0f
    private var zoomAccum = 1f

    fun attachEngine(newEngine: CanvasEngine) {
        engine = newEngine
        hasInitializedView = false
        post { resetView() }
        invalidate()
    }

    fun resetView() {
        val eng = engine ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / eng.widthPx, height.toFloat() / eng.heightPx) * 0.92f
        canvasMatrix.reset()
        canvasMatrix.postScale(scale, scale)
        val scaledW = eng.widthPx * scale
        val scaledH = eng.heightPx * scale
        canvasMatrix.postTranslate((width - scaledW) / 2f, (height - scaledH) / 2f)
        zoomAccum = scale
        hasInitializedView = true
        onMatrixChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!hasInitializedView && engine != null) resetView()
    }

    private fun onMatrixChanged() {
        canvasMatrix.invert(inverseMatrix)
        invalidate()
        onTransformChanged?.invoke()
    }

    /**
     * Hints the platform to keep the display at its highest refresh rate while a stroke is active.
     * Plain `invalidate()`-driven redraws (as opposed to property animations) aren't guaranteed to
     * trigger Android's touch-boost heuristic on their own, so this asks explicitly — cheap, and
     * directly relevant to how fluid a fast stroke feels on a 90Hz+ panel like the Tab S9 FE's.
     */
    private fun setHighFrameRateHint(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setRequestedFrameRate(if (active) REQUESTED_FRAME_RATE_CATEGORY_HIGH else REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
        }
    }

    private fun currentScale(): Float {
        canvasMatrix.getValues(matrixValues)
        return hypot(matrixValues[Matrix.MSCALE_X].toDouble(), matrixValues[Matrix.MSKEW_Y].toDouble()).toFloat().coerceAtLeast(0.0001f)
    }

    private fun toCanvasSpace(viewX: Float, viewY: Float) {
        samplePoint[0] = viewX
        samplePoint[1] = viewY
        inverseMatrix.mapPoints(samplePoint)
        canvasX = samplePoint[0]
        canvasY = samplePoint[1]
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val eng = engine ?: return
        canvas.save()
        canvas.concat(canvasMatrix)

        canvas.drawRect(0f, 0f, eng.widthPx.toFloat(), eng.heightPx.toFloat(), backgroundPaint)

        // Scratch-based brushes (pencil/ink/eraser) only commit into the real layer bitmap at
        // stroke end — see StrokeRenderer's class doc for why. Without this, the canvas would show
        // literally nothing happening for the entire stroke and then have it "pop in" all at once
        // on lift, which reads as broken/unresponsive rather than just non-fluid. So: while a
        // scratch-routed stroke is active, composite the live scratch buffer on top of its target
        // layer — purely for display, non-destructively — right where that layer sits in the stack,
        // so layers above it are unaffected and the preview roughly matches what stroke-end will
        // actually commit (capped at the brush's stroke opacity, DST_OUT for the eraser).
        val previewLayer = if (strokeUsesScratch) strokeTargetLayer else null
        for (layer in eng.layers) {
            if (!layer.visible || layer.opacity <= 0f) continue
            layerPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * 255).toInt()
            layerPaint.blendMode = layer.blendMode.blendMode
            if (layer === previewLayer) {
                val previewBrush = strokeRenderer?.brush
                val cap = previewBrush?.strokeOpacityCap ?: 1f
                val previewBlendMode = when {
                    previewBrush?.category == BrushCategory.ERASER -> BlendMode.DST_OUT
                    previewBrush?.pigmentMixing == true -> BlendMode.MULTIPLY
                    else -> null
                }
                if (previewBlendMode != null) {
                    // DST_OUT/MULTIPLY previews need a genuinely alpha-aware destination to
                    // composite against - the View's own hardware-accelerated canvas doesn't
                    // reliably provide one. This is a classic Android drawing-app gotcha: punching
                    // "transparency" via DST_OUT straight onto a View's onDraw() canvas can render
                    // as black garbage instead of revealing what was drawn earlier in the same pass,
                    // because the real display surface behind it has no meaningful translucency of
                    // its own to reveal. The actual stroke-commit path never hits this - it composites
                    // onto Canvas(layer.bitmap), a real software ARGB_8888 bitmap, where the alpha
                    // math is well-defined - which is exactly why the eraser looked broken only
                    // while dragging and "fixed itself" the instant the stroke committed on lift.
                    // saveLayer() gives Skia a temporary, properly alpha-aware offscreen buffer for
                    // just this one layer + its scratch overlay, which then composites back onto the
                    // real canvas normally - so an erased area correctly reveals the white
                    // background/earlier layers already painted below it, in real time.
                    //
                    // Bounds: saveLayer is one of the most expensive Canvas ops there is, and a fast
                    // drag can trigger this branch dozens of times a second - allocating (and
                    // compositing) a full-canvas-sized offscreen buffer on every one of those frames
                    // risks dropping frames badly enough that the screen visibly lags behind the real
                    // stroke, which reads exactly like "nothing erases while I drag" even though the
                    // compositing math itself is correct. Bounding saveLayer to only the region this
                    // frame's dab(s) actually touched (already computed for the dirty-rect invalidate
                    // below) keeps its cost proportional to the stroke instead of the canvas size.
                    val bounds = if (hasStrokeDirtyBounds) {
                        // A little padding so the saveLayer bounds don't clip a dab's soft-falloff
                        // edge right at the boundary; reset right after consuming so accumulation
                        // (see invalidateDirty) starts clean for whatever happens before the next frame.
                        strokeDirtyBoundsCanvasSpace.inset(-4f, -4f)
                        hasStrokeDirtyBounds = false
                        strokeDirtyBoundsCanvasSpace
                    } else {
                        // Deliberately NOT reusing a stale small rect here: this redraw could just as
                        // well have been triggered by something that changes the WHOLE layer's
                        // appearance (its opacity or blend mode, dragged from the Layers panel while
                        // this stroke happens to be paused) rather than new stroke content - reusing
                        // last frame's small dab-sized bounds in that case would only re-composite a
                        // tiny corner correctly and leave the rest of the layer showing stale opacity
                        // for as long as the pause lasts. Falling back to the full canvas costs more
                        // in that narrow situation, but it's the only choice that's always correct.
                        fullCanvasBounds(eng)
                    }
                    // layerPaint (alpha=layer.opacity, blendMode=layer.blendMode) is passed as
                    // saveLayer's own paint, not used inside the offscreen buffer - it's applied once,
                    // here, when the finished offscreen composites back onto the REAL destination,
                    // which is where a non-Normal layer blend mode has real content to blend against.
                    val saveCount = canvas.saveLayer(bounds, layerPaint)
                    // layer.bitmap goes in PLAIN (no blend mode, no alpha) here - the offscreen buffer
                    // starts fully transparent, so evaluating the layer's own blend mode against that
                    // empty backdrop would degrade it to plain unblended painting (see saveLayer's
                    // paint argument above for where that blend mode actually belongs instead).
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                    scratchPreviewPaint.alpha = (cap.coerceIn(0f, 1f) * 255).toInt()
                    scratchPreviewPaint.blendMode = previewBlendMode
                    canvas.drawBitmap(eng.strokeScratch, 0f, 0f, scratchPreviewPaint)
                    canvas.restoreToCount(saveCount)
                } else {
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
                    scratchPreviewPaint.alpha = (layer.opacity.coerceIn(0f, 1f) * cap.coerceIn(0f, 1f) * 255).toInt()
                    scratchPreviewPaint.blendMode = null
                    canvas.drawBitmap(eng.strokeScratch, 0f, 0f, scratchPreviewPaint)
                }
            } else {
                canvas.drawBitmap(layer.bitmap, 0f, 0f, layerPaint)
            }
        }
        layerPaint.blendMode = null

        val settings = VellumApp.instance.settingsRepository
        if (settings.paperTextureEnabled) {
            paperTexturePaint.alpha = (PaperTexture.clampStrength(settings.paperTextureStrength) * 255).toInt()
            canvas.drawRect(0f, 0f, eng.widthPx.toFloat(), eng.heightPx.toFloat(), paperTexturePaint)
        }

        if (eng.currentTool == ToolMode.PAINT_BY_NUMBER) {
            drawNumberLabels(canvas, eng)
        }

        eng.selectionRect?.let { rect ->
            selectionPaint.strokeWidth = 2f / currentScale()
            canvas.drawRect(rect, selectionPaint)
        }

        borderPaint.strokeWidth = 1.5f / currentScale()
        canvas.drawRect(0f, 0f, eng.widthPx.toFloat(), eng.heightPx.toFloat(), borderPaint)
        canvas.restore()
    }

    /** Small white numbered discs at each paint-by-number region's centroid — see [RegionAnalyzer]. */
    private fun drawNumberLabels(canvas: Canvas, eng: CanvasEngine) {
        val regionMap = eng.regionsForPaintByNumber()
        val labelRadius = minOf(eng.widthPx, eng.heightPx) * 0.02f
        numberLabelTextPaint.textSize = labelRadius * 1.15f
        for (region in regionMap.regions) {
            numberLabelPaint.style = Paint.Style.FILL
            numberLabelPaint.color = 0xF2FFFFFF.toInt()
            canvas.drawCircle(region.centroidX, region.centroidY, labelRadius, numberLabelPaint)
            numberLabelPaint.style = Paint.Style.STROKE
            numberLabelPaint.strokeWidth = labelRadius * 0.12f
            numberLabelPaint.color = 0xFF1A1A1A.toInt()
            canvas.drawCircle(region.centroidX, region.centroidY, labelRadius, numberLabelPaint)
            val textY = region.centroidY - (numberLabelTextPaint.descent() + numberLabelTextPaint.ascent()) / 2f
            canvas.drawText(region.number.toString(), region.centroidX, textY, numberLabelTextPaint)
        }
    }

    private val dirtyViewRect = RectF()
    private val fullCanvasBoundsRect = RectF()

    /**
     * Safety-net fallback for onDraw()'s saveLayer bounds (see there) when no stroke dirty rect is
     * available yet. Not reachable in practice today - startStroke() always runs renderer.start()
     * (which touches the dirty-bounds tracking) synchronously before invalidateDirty(), and Android
     * dispatches input/draws serially on the UI thread, so hasStrokeDirtyBounds is guaranteed true by
     * the time any onDraw() for that stroke's previewLayer branch can run. Kept anyway as a genuine
     * safety net: if a future refactor ever reorders those calls, this is what stands between that
     * regression and silently reintroducing the exact full-canvas-every-frame cost this fix removed.
     */
    private fun fullCanvasBounds(eng: CanvasEngine): RectF {
        fullCanvasBoundsRect.set(0f, 0f, eng.widthPx.toFloat(), eng.heightPx.toFloat())
        return fullCanvasBoundsRect
    }

    private fun invalidateDirty(canvasSpaceRect: RectF) {
        // Accumulates (unions) across every call since onDraw() last actually consumed it, not just
        // the latest one - a fast S Pen can generate several ACTION_MOVE events (each calling this)
        // between two real rendered frames, the same way Android's own invalidate(rect) coalesces
        // multiple calls into one combined dirty region for the next draw pass. Overwriting instead
        // of unioning here would mean saveLayer's bounds (see onDraw) only covered the LAST of those
        // calls' small delta, silently leaving earlier dabs from the same unrendered gap uncomposited
        // in the eventual frame.
        if (hasStrokeDirtyBounds) {
            strokeDirtyBoundsCanvasSpace.union(canvasSpaceRect)
        } else {
            strokeDirtyBoundsCanvasSpace.set(canvasSpaceRect)
            hasStrokeDirtyBounds = true
        }

        if (canvasSpaceRect.isEmpty()) {
            invalidate()
            return
        }
        dirtyViewRect.set(canvasSpaceRect)
        canvasMatrix.mapRect(dirtyViewRect)
        dirtyViewRect.inset(-6f, -6f)
        invalidate(
            dirtyViewRect.left.toInt(),
            dirtyViewRect.top.toInt(),
            dirtyViewRect.right.toInt() + 1,
            dirtyViewRect.bottom.toInt() + 1,
        )
    }

    // ---------------------------------------------------------------- touch dispatch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val eng = engine ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                when (event.getToolType(idx)) {
                    MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> {
                        if (strokePointerId != -1 || selectionPointerId != -1) {
                            // already own a stroke or a selection gesture via another pointer, ignore
                        } else if (eng.currentTool == ToolMode.SELECT) {
                            startSelectionGesture(eng, event, idx)
                        } else if (eng.currentTool == ToolMode.FILL || eng.currentTool == ToolMode.PAINT_BY_NUMBER) {
                            performFill(eng, event, idx)
                        } else {
                            startStroke(eng, event, idx)
                        }
                    }
                    MotionEvent.TOOL_TYPE_FINGER -> {
                        // Tracked unconditionally, even while a stroke owns input - Android never
                        // resends ACTION_DOWN for a pointer that's already touching the glass, so a
                        // finger that first went down DURING a stroke (very plausible: resting an
                        // off-hand, or pre-positioning a pinch to fire the instant the pen lifts)
                        // could otherwise never be picked up again for as long as it stays down.
                        // Palm rejection itself still fully holds: ACTION_MOVE only ever reaches
                        // handleFingerMove() below when strokePointerId == -1, so a tracked finger
                        // still has zero visible effect on the canvas until the stroke actually ends.
                        handleFingerDown(event, idx)
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (strokePointerId != -1) moveStroke(event)
                else if (selectionPointerId != -1) moveSelectionGesture(event)
                else handleFingerMove(event)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id == strokePointerId) {
                    endStroke()
                } else if (id == selectionPointerId) {
                    endSelectionGesture(eng)
                } else {
                    handleFingerUp(id)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (strokePointerId != -1) cancelStroke()
                if (selectionPointerId != -1) cancelSelectionGesture(eng)
                navPointerIds.clear()
                navBaselineSet = false
            }
        }
        return true
    }

    // ---------------------------------------------------------------- bucket fill

    /**
     * Tap-to-fill: a single-shot action (no drag/lift lifecycle like a stroke), bounded by whatever
     * layers sit above the active one — see [CanvasEngine.boundaryMaskAbove]. Recorded as one undo
     * step, same as a stroke, so it's just as reversible. In [ToolMode.PAINT_BY_NUMBER] the color
     * comes from whichever numbered region was tapped (see [CanvasEngine.regionsForPaintByNumber])
     * instead of the user's currently selected color — that's the whole mechanic.
     */
    private fun performFill(eng: CanvasEngine, event: MotionEvent, idx: Int) {
        val layer = eng.activeLayer() ?: return
        if (layer.locked) return

        toCanvasSpace(event.getX(idx), event.getY(idx))
        // floor(), not .toInt(): Float.toInt() truncates toward zero, so a tap whose canvas-space
        // coordinate lands in (-1, 0) - up to one canvas pixel outside the actual edge, which at
        // high zoom (up to 40x) covers many screen pixels right where the border stroke sits -
        // would truncate to 0 and incorrectly pass the bounds check below instead of being rejected.
        val px = floor(canvasX).toInt()
        val py = floor(canvasY).toInt()
        if (px < 0 || px >= eng.widthPx || py < 0 || py >= eng.heightPx) return

        val fillColor = if (eng.currentTool == ToolMode.PAINT_BY_NUMBER) {
            eng.regionsForPaintByNumber().regionAt(px, py)?.colorArgb ?: return
        } else {
            eng.currentColorArgb
        }

        val before = layer.snapshot()
        val boundary = eng.boundaryMaskAbove(eng.activeLayerIndex)
        val changed = try {
            FloodFillTool.fill(
                target = layer.bitmap,
                boundary = boundary,
                startX = px,
                startY = py,
                fillColorArgb = fillColor,
                alpha = eng.brushOpacityMultiplier,
            )
        } finally {
            boundary.recycle()
        }

        if (changed) {
            layer.bumpVersion()
            eng.bumpRevision()
            eng.undoManager.beginStroke(layer.id, before).commit(layer.snapshot())
            onStrokeCommitted?.invoke()
            invalidate()
        } else {
            before.recycle()
        }
    }

    // ---------------------------------------------------------------- selection tool
    //
    // Deliberately its own pointer-id tracking (selectionPointerId), completely separate from a
    // stroke's (strokePointerId) -- only ever engaged when ToolMode.SELECT is active, so this has
    // zero effect on the default drawing experience or the hardened stroke-input path above.
    //
    // v1 scope, on purpose: rectangle selection only, one rect at a time, move (not
    // copy/resize/rotate) the selected pixels on the active layer. A drag starting inside the
    // existing rect moves it; a drag starting outside it defines a new one, replacing the old.
    // Freehand lasso selection is a real, documented future extension, not a silent omission --
    // it would need a Path-based mask instead of a plain RectF, which is more machinery than a v1
    // needs to prove the feature out.

    private fun startSelectionGesture(eng: CanvasEngine, event: MotionEvent, idx: Int) {
        val layer = eng.activeLayer() ?: return
        if (layer.locked) return

        toCanvasSpace(event.getX(idx), event.getY(idx))
        val existing = eng.selectionRect
        selectionPointerId = event.getPointerId(idx)
        if (existing != null && existing.contains(canvasX, canvasY)) {
            selectionDefining = false
            selectionMoveOriginalRect = RectF(existing)
        } else {
            selectionDefining = true
            eng.selectionRect = RectF(canvasX, canvasY, canvasX, canvasY)
        }
        selectionAnchorCanvasX = canvasX
        selectionAnchorCanvasY = canvasY
        invalidate()
    }

    private fun moveSelectionGesture(event: MotionEvent) {
        val eng = engine ?: return
        val idx = event.findPointerIndex(selectionPointerId)
        if (idx == -1) return
        toCanvasSpace(event.getX(idx), event.getY(idx))

        if (selectionDefining) {
            eng.selectionRect = RectF(
                minOf(selectionAnchorCanvasX, canvasX), minOf(selectionAnchorCanvasY, canvasY),
                maxOf(selectionAnchorCanvasX, canvasX), maxOf(selectionAnchorCanvasY, canvasY),
            )
        } else {
            val original = selectionMoveOriginalRect ?: return
            val dx = canvasX - selectionAnchorCanvasX
            val dy = canvasY - selectionAnchorCanvasY
            eng.selectionRect = RectF(original.left + dx, original.top + dy, original.right + dx, original.bottom + dy)
        }
        invalidate()
    }

    /** Reverts to the pre-gesture state instead of committing anything -- ACTION_CANCEL (e.g. an
     * incoming system gesture stealing the pointer) shouldn't leave a half-defined or half-moved
     * selection behind, the same "no partial commit" guarantee cancelStroke() gives a stroke. */
    private fun cancelSelectionGesture(eng: CanvasEngine) {
        eng.selectionRect = if (selectionDefining) null else selectionMoveOriginalRect
        selectionPointerId = -1
        selectionMoveOriginalRect = null
        invalidate()
    }

    private fun endSelectionGesture(eng: CanvasEngine) {
        val rect = eng.selectionRect
        val wasMoving = !selectionDefining
        val originalRect = selectionMoveOriginalRect
        selectionPointerId = -1
        selectionMoveOriginalRect = null

        if (rect == null || rect.width() < 2f || rect.height() < 2f) {
            eng.selectionRect = null
            invalidate()
            return
        }
        if (wasMoving && originalRect != null) {
            commitSelectionMove(eng, originalRect, rect)
        }
        invalidate()
    }

    /** Actually moves the pixel content: extracts [from] on the active layer, clears it, and
     * redraws the extracted content at [to]. One undo step, same pattern as a committed stroke. */
    private fun commitSelectionMove(eng: CanvasEngine, from: RectF, to: RectF) {
        val layer = eng.activeLayer() ?: return
        val srcRect = android.graphics.Rect()
        from.roundOut(srcRect)
        srcRect.intersect(0, 0, eng.widthPx, eng.heightPx)
        if (srcRect.isEmpty) return

        val before = layer.snapshot()
        val extracted = Bitmap.createBitmap(layer.bitmap, srcRect.left, srcRect.top, srcRect.width(), srcRect.height())
        val canvas = Canvas(layer.bitmap)
        val clearPaint = Paint().apply { blendMode = BlendMode.CLEAR }
        canvas.drawRect(srcRect.left.toFloat(), srcRect.top.toFloat(), srcRect.right.toFloat(), srcRect.bottom.toFloat(), clearPaint)
        val destLeft = srcRect.left + (to.left - from.left)
        val destTop = srcRect.top + (to.top - from.top)
        canvas.drawBitmap(extracted, destLeft, destTop, null)
        extracted.recycle()

        layer.bumpVersion()
        eng.bumpRevision()
        eng.undoManager.beginStroke(layer.id, before).commit(layer.snapshot())
        onStrokeCommitted?.invoke()
    }

    // ---------------------------------------------------------------- stylus stroke

    private fun sampleFrom(event: MotionEvent, idx: Int, cx: Float, cy: Float): InputSample {
        val pressure = event.getPressure(idx).coerceIn(0f, 1f)
        val tilt = event.getAxisValue(MotionEvent.AXIS_TILT, idx)
        val orientation = event.getOrientation(idx)
        return InputSample(cx, cy, pressure, tilt, orientation)
    }

    private fun startStroke(eng: CanvasEngine, event: MotionEvent, idx: Int) {
        val layer = eng.activeLayer() ?: return
        if (layer.locked) return

        strokePointerId = event.getPointerId(idx)
        eng.strokeInProgressLayerId = layer.id
        setHighFrameRateHint(true)
        onStrokeActiveChanged?.invoke(true)
        // The S Pen's dedicated hardware eraser (its tail button/end, reported as TOOL_TYPE_ERASER)
        // is a quick-access default, not an override: if the user has deliberately picked one of the
        // eraser variants in BrushBar, erasing with the hardware eraser should use THAT variant, not
        // silently discard it back to the plain default. Only fall back to FlatEraser when the
        // currently selected brush isn't an eraser at all (e.g. Pencil is selected and the user flips
        // the pen over) - that's the case the hardware eraser is actually meant to shortcut.
        val brush = if (event.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) {
            eng.currentBrush.takeIf { it.category == BrushCategory.ERASER } ?: BrushPresets.FlatEraser
        } else {
            eng.currentBrush
        }
        val renderer = StrokeRenderer(brush, eng.currentColorArgb, eng.brushSizeMultiplier, eng.brushOpacityMultiplier)
        strokeRenderer = renderer
        strokeTargetLayer = layer
        // Erasers always route through the scratch mask too (see StrokeRenderer's class doc) so a
        // soft-hardness eraser gets a real graduated falloff instead of a hard CLEAR-mode edge.
        strokeUsesScratch = !brush.buildUp
        strokeTargetCanvas = if (strokeUsesScratch) eng.scratch() else Canvas(layer.bitmap)
        pendingStroke = eng.undoManager.beginStroke(layer.id, layer.snapshot())

        val symmetry = eng.symmetryMode
        mirrorRenderers = if (symmetry == SymmetryMode.NONE) {
            emptyList()
        } else {
            symmetry.mirrorTransforms(eng.widthPx / 2f, eng.heightPx / 2f).map { transform ->
                StrokeRenderer(brush, eng.currentColorArgb, eng.brushSizeMultiplier, eng.brushOpacityMultiplier) to transform
            }
        }

        toCanvasSpace(event.getX(idx), event.getY(idx))
        val sample = sampleFrom(event, idx, canvasX, canvasY)
        renderer.start(strokeTargetCanvas!!, sample)
        renderer.takeDirtyBounds()?.let { invalidateDirty(it) }
        for ((mirrorRenderer, transform) in mirrorRenderers) {
            val p = transform(sample.x, sample.y)
            mirrorRenderer.start(strokeTargetCanvas!!, sample.copy(x = p.x, y = p.y))
            mirrorRenderer.takeDirtyBounds()?.let { invalidateDirty(it) }
        }
    }

    private fun moveStroke(event: MotionEvent) {
        val renderer = strokeRenderer ?: return
        val target = strokeTargetCanvas ?: return
        val idx = event.findPointerIndex(strokePointerId)
        if (idx == -1) return
        // Re-checked every move, not just at ACTION_DOWN: a second pointer could lock this exact
        // layer (via the Layers panel) while the stroke is already in flight, and a stroke that
        // started legally shouldn't keep landing content on a layer the user just locked.
        if (strokeTargetLayer?.locked == true) {
            cancelStroke()
            return
        }

        val histCount = event.historySize
        for (h in 0 until histCount) {
            val hx = event.getHistoricalX(idx, h)
            val hy = event.getHistoricalY(idx, h)
            toCanvasSpace(hx, hy)
            val pressure = event.getHistoricalPressure(idx, h).coerceIn(0f, 1f)
            val tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h)
            val orientation = event.getHistoricalOrientation(idx, h)
            val histSample = InputSample(canvasX, canvasY, pressure, tilt, orientation)
            renderer.moveTo(target, histSample)
            for ((mirrorRenderer, transform) in mirrorRenderers) {
                val p = transform(histSample.x, histSample.y)
                mirrorRenderer.moveTo(target, histSample.copy(x = p.x, y = p.y))
            }
        }
        toCanvasSpace(event.getX(idx), event.getY(idx))
        val sample = sampleFrom(event, idx, canvasX, canvasY)
        renderer.moveTo(target, sample)
        for ((mirrorRenderer, transform) in mirrorRenderers) {
            val p = transform(sample.x, sample.y)
            mirrorRenderer.moveTo(target, sample.copy(x = p.x, y = p.y))
        }

        renderer.takeDirtyBounds()?.let { invalidateDirty(it) }
        for ((mirrorRenderer, _) in mirrorRenderers) {
            mirrorRenderer.takeDirtyBounds()?.let { invalidateDirty(it) }
        }
    }

    private fun endStroke() {
        val eng = engine
        val layer = strokeTargetLayer
        val renderer = strokeRenderer
        if (eng != null && layer != null && renderer != null) {
            if (strokeUsesScratch) {
                eng.flattenScratchOnto(
                    layer,
                    renderer.brush.strokeOpacityCap,
                    erasing = renderer.brush.category == BrushCategory.ERASER,
                    mixing = renderer.brush.pigmentMixing,
                    wetness = renderer.brush.wetness,
                )
            }
            layer.bumpVersion()
            eng.bumpRevision()
            pendingStroke?.commit(layer.snapshot())
            onStrokeCommitted?.invoke()
        } else {
            pendingStroke?.discard()
        }
        cleanupStrokeState()
        invalidate()
    }

    private fun cancelStroke() {
        val layer = strokeTargetLayer
        if (layer != null) {
            pendingStroke?.rollback(layer)
        } else {
            pendingStroke?.discard()
        }
        cleanupStrokeState()
        invalidate()
    }

    private fun cleanupStrokeState() {
        setHighFrameRateHint(false)
        engine?.strokeInProgressLayerId = null
        strokePointerId = -1
        strokeRenderer = null
        strokeTargetCanvas = null
        strokeTargetLayer = null
        pendingStroke = null
        navBaselineSet = false
        hasStrokeDirtyBounds = false
        mirrorRenderers = emptyList()
        onStrokeActiveChanged?.invoke(false)
    }

    // ---------------------------------------------------------------- finger navigation

    private fun handleFingerDown(event: MotionEvent, idx: Int) {
        val id = event.getPointerId(idx)
        // Tracks every currently-down finger, not just the first two: handleFingerMove() already
        // only ever reads xs[0]/xs[1] (the two OLDEST still-tracked ids, since this list is
        // append-ordered) for the actual pinch/rotate math, so a 3rd+ finger is naturally along for
        // the ride and ignored - until one of the active two lifts, at which point the next-oldest
        // tracked finger is automatically promoted into its slot instead of the gesture just dying.
        // Capping tracking itself at 2 (the old behavior) meant a 3rd finger touching down mid-pinch
        // could never be picked up later even after one of the original two lifted while it stayed down.
        if (id !in navPointerIds) navPointerIds.add(id)
        navBaselineSet = false
    }

    private fun handleFingerUp(pointerId: Int) {
        navPointerIds.remove(pointerId)
        navBaselineSet = false
    }

    private fun handleFingerMove(event: MotionEvent) {
        if (navPointerIds.isEmpty()) return
        val xs = FloatArray(navPointerIds.size)
        val ys = FloatArray(navPointerIds.size)
        var count = 0
        for (pid in navPointerIds) {
            val idx = event.findPointerIndex(pid)
            if (idx == -1) continue
            xs[count] = event.getX(idx)
            ys[count] = event.getY(idx)
            count++
        }
        if (count == 0) return

        val focusX: Float
        val focusY: Float
        var dist = 0f
        var angle = navLastAngle
        if (count >= 2) {
            focusX = (xs[0] + xs[1]) / 2f
            focusY = (ys[0] + ys[1]) / 2f
            dist = hypot((xs[1] - xs[0]).toDouble(), (ys[1] - ys[0]).toDouble()).toFloat()
            angle = atan2((ys[1] - ys[0]).toDouble(), (xs[1] - xs[0]).toDouble()).toFloat()
        } else {
            focusX = xs[0]
            focusY = ys[0]
        }

        if (!navBaselineSet) {
            navLastFocusX = focusX
            navLastFocusY = focusY
            navLastDist = dist
            navLastAngle = angle
            navBaselineSet = true
            return
        }

        // Pan and rotate deliberately stay live even once zoom is pinned at MIN_ZOOM/MAX_ZOOM below -
        // only the scale itself refuses to go further, matching how pinch gestures behave in most
        // photo/drawing apps (hitting the zoom limit doesn't also freeze panning or rotation, which
        // would feel like the whole gesture locked up rather than just the zoom leveling off).
        val dx = focusX - navLastFocusX
        val dy = focusY - navLastFocusY
        canvasMatrix.postTranslate(dx, dy)

        if (count >= 2 && navLastDist > 1f && dist > 1f) {
            val scaleDelta = dist / navLastDist
            val newZoom = zoomAccum * scaleDelta
            if (newZoom in MIN_ZOOM..MAX_ZOOM) {
                canvasMatrix.postScale(scaleDelta, scaleDelta, focusX, focusY)
                zoomAccum = newZoom
            }
            val rotDeltaDeg = Math.toDegrees((angle - navLastAngle).toDouble()).toFloat()
            canvasMatrix.postRotate(rotDeltaDeg, focusX, focusY)
        }

        navLastFocusX = focusX
        navLastFocusY = focusY
        navLastDist = dist
        navLastAngle = angle
        onMatrixChanged()
    }

    companion object {
        private const val MIN_ZOOM = 0.05f
        private const val MAX_ZOOM = 40f
    }
}
