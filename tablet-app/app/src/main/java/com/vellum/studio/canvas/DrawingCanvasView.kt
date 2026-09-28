package com.vellum.studio.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.vellum.studio.VellumApp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
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

    /**
     * Fires at most once per committed stroke, only while [CanvasEngine.shapeAssistEnabled] is on
     * and that stroke's point path scored a confident match in [ShapeAssist.recognize]. The
     * argument is a plain-English label ("circle", "rectangle", ...) for a Snackbar/chip; the
     * caller's affordance should call [applyPendingShapeSnap] on its accept action. Never draws
     * any UI itself and never applies anything on its own -- purely a notification, so it's zero
     * behavior change for anyone who leaves it unset, same as [onStrokeCommitted].
     */
    var onShapeAssistCandidate: ((String) -> Unit)? = null

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

    // Pose Reference Overlay (see PoseOverlay) -- a teaching-aid cyan, deliberately distinct from
    // the paint-by-number white/black number discs and the selection tool's blue-dashed marquee,
    // so it reads unambiguously as "a guide drawn ON TOP of the reference photo" rather than any
    // kind of editable canvas content.
    private val poseBonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE029B6F6.toInt()
        strokeCap = Paint.Cap.ROUND
    }
    private val poseJointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF0288D1.toInt()
    }

    // Pen-hover ghost preview: a light-over-dark double ring (same "readable against any
    // background" reasoning as drawNumberLabels' white-fill-plus-black-stroke discs, just as a
    // pure outline here since this previews a brush footprint rather than labeling a region).
    private val hoverGhostOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB3FFFFFF.toInt()
    }
    private val hoverGhostInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xB3000000.toInt()
    }

    // --- stylus stroke state ---
    private var strokePointerId = -1
    private var strokeRenderer: StrokeRenderer? = null
    private var strokeTargetCanvas: Canvas? = null
    private var strokeTargetLayer: Layer? = null
    private var strokeUsesScratch = false
    private var pendingStroke: UndoManager.PendingStroke? = null

    // --- pen-hover preview (ACTION_HOVER_* -- a completely independent MotionEvent stream from
    // the ACTION_DOWN/MOVE/UP touch events above, delivered via onHoverEvent() below rather than
    // onTouchEvent(), so this never touches the stylus/finger touch-routing logic at all) ---
    //
    // Purely a draw-time onDraw() overlay (see drawHoverGhost) -- same non-destructive contract as
    // drawNumberLabels/drawPoseGuide, never baked into any layer's pixels.
    private var hoverActive = false
    private var hoverIsEraser = false
    private var hoverCanvasX = 0f
    private var hoverCanvasY = 0f
    private var hoverTiltRadians = 0f
    private var hoverOrientationRadians = 0f

    // --- Smart Shape Assist (see ShapeAssist) ---
    //
    // A lightweight, PARALLEL point capture -- entirely separate from StrokeRenderer's own dab
    // math above, which this never reads from or writes to. Only ever populated while
    // CanvasEngine.shapeAssistEnabled is true (checked once, at stroke start), so a normal
    // Assist-off stroke allocates and touches none of this.
    private var capturingShapeAssist = false
    private val shapeAssistPoints = mutableListOf<PointF>()
    private var shapeAssistBrush: Brush? = null
    private var shapeAssistColorArgb = 0
    private var shapeAssistSizeMultiplier = 1f
    private var shapeAssistOpacityMultiplier = 1f

    private data class PendingShapeAssist(
        val layerId: String,
        val revisionAtOffer: Int,
        val candidate: ShapeAssist.Candidate,
        val brush: Brush,
        val colorArgb: Int,
        val sizeMultiplier: Float,
        val opacityMultiplier: Float,
    )

    // Survives past cleanupStrokeState() (unlike the capture fields above) since the Snackbar
    // offering it stays alive after the stroke that produced it has fully ended -- see
    // applyPendingShapeSnap's revision check for how a stale offer (something else changed the
    // canvas in the meantime) gets caught instead of silently corrupting a later stroke.
    private var pendingShapeAssist: PendingShapeAssist? = null

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

    // Owns the live-preview compositing for a scratch-routed stroke, including the CUMULATIVE
    // (whole-stroke, canvas-space) bounds of everything stamped so far -- see StrokePreviewCompositor
    // for why that is cumulative rather than the per-frame dirty union this view used to keep here.
    private val strokePreviewCompositor = StrokePreviewCompositor()

    // --- finger navigation state ---
    private val navPointerIds = mutableListOf<Int>()
    private var navBaselineSet = false
    private var navLastFocusX = 0f
    private var navLastFocusY = 0f
    private var navLastDist = 0f
    private var navLastAngle = 0f
    private var zoomAccum = 1f

    // handleFingerMove() only ever reads index 0/1 (see handleFingerDown's comment on why a 3rd+
    // finger is tracked but never consulted for the actual pinch/rotate math) -- reused here for
    // the same reason samplePoint above is reused, instead of a fresh FloatArray(navPointerIds.size)
    // on every single ACTION_MOVE: a fast two-finger pan/zoom/rotate drag can call this dozens of
    // times a second, and allocating (and then GC'ing) two small arrays on every one of those frames
    // is exactly the kind of avoidable per-frame garbage that shows up as jank during fast navigation.
    private val navMoveXs = FloatArray(2)
    private val navMoveYs = FloatArray(2)

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
                // All of the live-preview compositing (why it needs an offscreen buffer for
                // DST_OUT/MULTIPLY strokes AND for any non-Normal layer blend mode, and why that
                // buffer is bounded by the CUMULATIVE stroke bounds with everything outside them
                // drawn plainly) lives in StrokePreviewCompositor -- see its doc. It's a separate
                // class in this file, taking a Canvas, purely so a Robolectric NATIVE test can run the
                // exact same code against a real Skia bitmap without needing this View's app wiring.
                //
                // Pad in canvas units, grown when zoomed out so it stays >= ~4 device px (a canvas
                // unit is < 1 device px there), capped so an extreme zoom-out can't balloon it.
                val boundsPad = PREVIEW_BOUNDS_PAD_PX / currentScale().coerceIn(0.25f, 1f)
                strokePreviewCompositor.drawLayerWithScratch(
                    canvas, layer.bitmap, eng.strokeScratch, layerPaint, previewBlendMode, cap, boundsPad,
                )
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

        if (eng.poseGuideEnabled) {
            eng.poseGuide?.let { drawPoseGuide(canvas, it) }
        }

        // SELECT only: the marquee used to draw in every tool and read as an active clip region
        // while brushing (brush strokes never consult it). CanvasEngine.currentTool also clears
        // the rect on leaving SELECT; this gate is the draw-side half so a stale one can't show.
        if (eng.currentTool == ToolMode.SELECT) {
            eng.selectionRect?.let { rect ->
                selectionPaint.strokeWidth = 2f / currentScale()
                canvas.drawRect(rect, selectionPaint)
            }
        }

        // Hidden the instant a real stroke owns input (strokePointerId != -1) -- once ink is
        // actually landing, a ghost of where the NEXT dab would go is just visual noise, not a
        // preview. Also only for ToolMode.BRUSH -- Fill/Paint-by-Number/Select don't stamp a brush
        // footprint at all, so a brush-shaped ghost there would misrepresent what tapping will do.
        if (hoverActive && strokePointerId == -1 && eng.currentTool == ToolMode.BRUSH) {
            drawHoverGhost(canvas, eng)
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

    /** Figure-drawing skeleton overlay (see [PoseOverlay]) -- drawn fresh every frame from
     * [CanvasEngine.poseGuide], never baked into any layer's pixels, same non-destructive
     * "drawn in onDraw()" contract [drawNumberLabels] above already has. */
    private fun drawPoseGuide(canvas: Canvas, guide: PoseOverlay.PoseGuide) {
        val scale = currentScale()
        poseBonePaint.strokeWidth = 5f / scale
        val jointRadius = 8f / scale
        for ((fromType, toType) in PoseOverlay.CONNECTIONS) {
            val from = guide.joints[fromType]?.takeIf { it.confident } ?: continue
            val to = guide.joints[toType]?.takeIf { it.confident } ?: continue
            canvas.drawLine(from.x, from.y, to.x, to.y, poseBonePaint)
        }
        for (joint in guide.joints.values) {
            if (!joint.confident) continue
            canvas.drawCircle(joint.x, joint.y, jointRadius, poseJointPaint)
        }
    }

    /**
     * Ghost outline of the brush that would land if the pen touched down right now, at the pen's
     * last-known hover position -- see [onHoverEvent]. Drawn fresh every frame straight from
     * [CanvasEngine.currentBrush]/[CanvasEngine.brushSizeMultiplier], the same non-destructive
     * "computed live in onDraw(), never baked into a layer" contract [drawNumberLabels] and
     * [drawPoseGuide] already use.
     *
     * Sized at full (1.0) pressure -- a hovering pen hasn't committed to any particular press yet,
     * so showing its fully-pressed footprint (the largest/most-opaque it could land) is the most
     * useful "what will land" preview, and it also sidesteps needing any pressure reading at all
     * (hover events generally don't report a meaningful one). The tilt-driven widen/squash here
     * deliberately mirrors [StrokeRenderer.stampAt]'s own ellipse math -- this is pure, self-
     * contained brush-shape math with no dependency on stroke state, so re-deriving it here for a
     * preview is exactly "compose around the public API" rather than editing that hardened file.
     */
    private fun drawHoverGhost(canvas: Canvas, eng: CanvasEngine) {
        val brush = if (hoverIsEraser) {
            eng.currentBrush.takeIf { it.category == BrushCategory.ERASER } ?: BrushPresets.FlatEraser
        } else {
            eng.currentBrush
        }
        val diameter = (brush.baseSizePx * eng.brushSizeMultiplier).coerceAtLeast(1f)
        val tiltNorm = (hoverTiltRadians / (PI.toFloat() / 2f)).coerceIn(0f, 1f)
        val widen = 1f + (brush.tiltToSize * tiltNorm * 0.9f).coerceIn(0f, 1.6f)
        val squash = 1f - (brush.tiltToSize * tiltNorm * 0.35f).coerceIn(0f, 0.6f)
        val radiusX = diameter / 2f * widen
        val radiusY = diameter / 2f * squash

        val scale = currentScale()
        hoverGhostOuterPaint.strokeWidth = 3f / scale
        hoverGhostInnerPaint.strokeWidth = 1.25f / scale

        canvas.save()
        canvas.translate(hoverCanvasX, hoverCanvasY)
        if (hoverOrientationRadians != 0f) {
            canvas.rotate(Math.toDegrees(hoverOrientationRadians.toDouble()).toFloat())
        }
        canvas.drawOval(-radiusX, -radiusY, radiusX, radiusY, hoverGhostOuterPaint)
        canvas.drawOval(-radiusX, -radiusY, radiusX, radiusY, hoverGhostInnerPaint)
        canvas.restore()
    }

    private fun invalidateDirty(canvasSpaceRect: RectF) {
        // Unioned into the stroke's CUMULATIVE bounds (never reset between frames, only at stroke
        // start/cleanup) -- see StrokePreviewCompositor. This used to keep a per-frame dirty union
        // that onDraw consumed and reset, which is what made the preview's saveLayer clip the rest
        // of the layer away.
        strokePreviewCompositor.noteDirty(canvasSpaceRect)

        // This used to map canvasSpaceRect into view space and call the deprecated four-arg
        // invalidate(l, t, r, b) with it. Per View.invalidate(int,int,int,int)'s own
        // deprecation note, the framework has ignored that rect since API 21 in favor of an
        // internally-calculated dirty area, and minSdk here is 29 -- so on every API level
        // this app supports, that call was already 100% equivalent to plain invalidate(): the
        // whole view is damaged and redrawn every frame, which is exactly why onDraw() must draw
        // the ENTIRE target layer every frame rather than assume earlier pixels persist.
        invalidate()
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

    // ---------------------------------------------------------------- pen-hover preview
    //
    // ACTION_HOVER_ENTER/MOVE/EXIT are a distinct MotionEvent stream Android only delivers to
    // onHoverEvent(), never onTouchEvent() -- a stylus hovering near the glass but not yet
    // touching it. Entirely separate from the touch dispatch above: this never reads or writes any
    // of onTouchEvent()'s pointer-ownership state (strokePointerId, selectionPointerId,
    // navPointerIds), so it cannot change stylus/finger routing or palm rejection in any way.
    // "toCanvasSpace" is shared with the touch path safely -- both run on the UI thread, so there's
    // no concurrent-write hazard, only a same-thread read-then-copy exactly like the touch call
    // sites already do.
    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val toolType = event.getToolType(0)
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                    toCanvasSpace(event.getX(0), event.getY(0))
                    hoverCanvasX = canvasX
                    hoverCanvasY = canvasY
                    hoverTiltRadians = event.getAxisValue(MotionEvent.AXIS_TILT, 0)
                    hoverOrientationRadians = event.getOrientation(0)
                    hoverIsEraser = toolType == MotionEvent.TOOL_TYPE_ERASER
                    hoverActive = true
                } else {
                    // A finger doesn't hover the same way a stylus does, but some devices still
                    // report finger hover -- make sure a stray one can never leave a stale brush
                    // ghost on screen.
                    hoverActive = false
                }
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hoverActive = false
                invalidate()
            }
        }
        return super.onHoverEvent(event)
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
            // A tap inside the rect is a zero-distance "move". Committing it would snapshot the
            // whole layer twice and burn an undo slot for a no-op (depth can be as low as 6, so a
            // handful of stray taps evicted the real history). Keep the rect exactly where it was
            // (not the sub-pixel-drifted copy) and push nothing.
            if (abs(rect.left - originalRect.left) < SELECTION_MOVE_EPSILON_PX &&
                abs(rect.top - originalRect.top) < SELECTION_MOVE_EPSILON_PX
            ) {
                eng.selectionRect = originalRect
            } else {
                commitSelectionMove(eng, originalRect, rect)
            }
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

    /** Reads Settings' current pressure-curve gamma -- one SharedPreferences-backed read, cheap
     * enough to call once per gesture start/move but deliberately not called once PER SAMPLE (see
     * [moveStroke], which caches this once per ACTION_MOVE rather than once per historical point). */
    private fun currentPressureGamma(): Float = VellumApp.instance.settingsRepository.pressureCurveGamma

    /** [pressureGamma] is [PressureCurvePreset.LINEAR]'s gamma (1f, a no-op) by default, and every
     * call site below always passes the caller's own already-read gamma instead of re-reading
     * Settings per sample -- see [currentPressureGamma]'s doc comment for why. */
    private fun sampleFrom(event: MotionEvent, idx: Int, cx: Float, cy: Float, pressureGamma: Float): InputSample {
        val rawPressure = event.getPressure(idx).coerceIn(0f, 1f)
        val pressure = applyPressureCurve(rawPressure, pressureGamma)
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
        strokePreviewCompositor.beginStroke()

        // Checked once, here, rather than on every sample below -- see the field's own doc
        // comment for why an Assist-off stroke never touches any of this.
        capturingShapeAssist = eng.shapeAssistEnabled
        if (capturingShapeAssist) {
            shapeAssistPoints.clear()
            shapeAssistBrush = brush
            shapeAssistColorArgb = eng.currentColorArgb
            shapeAssistSizeMultiplier = eng.brushSizeMultiplier
            shapeAssistOpacityMultiplier = eng.brushOpacityMultiplier
        }
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
        val sample = sampleFrom(event, idx, canvasX, canvasY, currentPressureGamma())
        renderer.start(strokeTargetCanvas!!, sample)
        renderer.takeDirtyBounds()?.let { invalidateDirty(it) }
        for ((mirrorRenderer, transform) in mirrorRenderers) {
            val p = transform(sample.x, sample.y)
            mirrorRenderer.start(strokeTargetCanvas!!, sample.copy(x = p.x, y = p.y))
            mirrorRenderer.takeDirtyBounds()?.let { invalidateDirty(it) }
        }
        if (capturingShapeAssist) shapeAssistPoints.add(PointF(sample.x, sample.y))
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

        val pressureGamma = currentPressureGamma()

        val histCount = event.historySize
        for (h in 0 until histCount) {
            val hx = event.getHistoricalX(idx, h)
            val hy = event.getHistoricalY(idx, h)
            toCanvasSpace(hx, hy)
            val rawPressure = event.getHistoricalPressure(idx, h).coerceIn(0f, 1f)
            val pressure = applyPressureCurve(rawPressure, pressureGamma)
            val tilt = event.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, h)
            val orientation = event.getHistoricalOrientation(idx, h)
            val histSample = InputSample(canvasX, canvasY, pressure, tilt, orientation)
            renderer.moveTo(target, histSample)
            for ((mirrorRenderer, transform) in mirrorRenderers) {
                val p = transform(histSample.x, histSample.y)
                mirrorRenderer.moveTo(target, histSample.copy(x = p.x, y = p.y))
            }
            if (capturingShapeAssist && shapeAssistPoints.size < MAX_SHAPE_ASSIST_POINTS) {
                shapeAssistPoints.add(PointF(histSample.x, histSample.y))
            }
        }
        toCanvasSpace(event.getX(idx), event.getY(idx))
        val sample = sampleFrom(event, idx, canvasX, canvasY, pressureGamma)
        renderer.moveTo(target, sample)
        for ((mirrorRenderer, transform) in mirrorRenderers) {
            val p = transform(sample.x, sample.y)
            mirrorRenderer.moveTo(target, sample.copy(x = p.x, y = p.y))
        }
        if (capturingShapeAssist && shapeAssistPoints.size < MAX_SHAPE_ASSIST_POINTS) {
            shapeAssistPoints.add(PointF(sample.x, sample.y))
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
            offerShapeAssistIfCandidate(eng, layer)
        } else {
            pendingStroke?.discard()
        }
        cleanupStrokeState()
        invalidate()
    }

    /** Runs [ShapeAssist.recognize] against this just-committed stroke's captured point path and,
     * if confident, stashes the candidate + fires [onShapeAssistCandidate] -- see
     * [applyPendingShapeSnap] for how an accepted offer actually gets applied. A no-op (no
     * candidate stashed, no callback fired) when Assist mode was off for this stroke or nothing
     * confident was found, matching "never force a snap the user didn't ask for". */
    private fun offerShapeAssistIfCandidate(eng: CanvasEngine, layer: Layer) {
        if (!capturingShapeAssist || shapeAssistPoints.size < 2) return
        val candidate = ShapeAssist.recognize(shapeAssistPoints) ?: return
        pendingShapeAssist = PendingShapeAssist(
            layerId = layer.id,
            revisionAtOffer = eng.revision,
            candidate = candidate,
            brush = shapeAssistBrush ?: eng.currentBrush,
            colorArgb = shapeAssistColorArgb,
            sizeMultiplier = shapeAssistSizeMultiplier,
            opacityMultiplier = shapeAssistOpacityMultiplier,
        )
        onShapeAssistCandidate?.invoke(ShapeAssist.labelFor(candidate))
    }

    /**
     * Accepts the current Assist-mode shape offer (see [onShapeAssistCandidate]): undoes the
     * freehand stroke it was computed from (restoring the layer to its pre-stroke content, the
     * same call the top bar's Undo button makes) and redraws the recognized geometry in its place
     * at the same layer/brush/color/size, committed as its own normal, independently undoable
     * stroke -- so the freehand commit ends up genuinely SUPERSEDED (one Undo after accepting
     * fully reverts to before the freehand stroke, not to the freehand stroke itself) rather than
     * just visually covered up.
     *
     * No-ops silently -- never crashes, never forces anything -- if the canvas has changed since
     * the offer (another stroke, an undo/redo, a layer edit all bump [CanvasEngine.revision]) or
     * the target layer is gone/locked, since applying a stale offer on top of unrelated later
     * work would silently destroy that work.
     */
    fun applyPendingShapeSnap() {
        val pending = pendingShapeAssist ?: return
        pendingShapeAssist = null
        val eng = engine ?: return
        if (eng.revision != pending.revisionAtOffer) return
        val layer = eng.layers.firstOrNull { it.id == pending.layerId } ?: return
        if (layer.locked || layer.id == eng.strokeInProgressLayerId) return

        eng.undoManager.undo { id -> eng.layers.firstOrNull { it.id == id } }
        eng.bumpRevision()

        val before = layer.snapshot()
        drawShapeOnto(eng, layer, pending)
        layer.bumpVersion()
        eng.bumpRevision()
        eng.undoManager.beginStroke(layer.id, before).commit(layer.snapshot())
        onStrokeCommitted?.invoke()
        invalidate()
    }

    /** Redraws [pending]'s recognized geometry onto [layer] through the exact same
     * StrokeRenderer + scratch/flatten pipeline a real freehand stroke commits through (see
     * [endStroke]) -- reusing that hardened compositing path rather than a bespoke "draw a shape"
     * routine, so a snapped shape looks and behaves exactly like a hand-drawn one of the same
     * brush would. Synthetic samples use full pressure/no tilt -- a "perfectly drawn" ruler-clean
     * stroke, on purpose. */
    private fun drawShapeOnto(eng: CanvasEngine, layer: Layer, pending: PendingShapeAssist) {
        val path = ShapeAssist.perimeterPoints(pending.candidate)
        if (path.size < 2) return
        val brush = pending.brush
        val renderer = StrokeRenderer(brush, pending.colorArgb, pending.sizeMultiplier, pending.opacityMultiplier)
        val usesScratch = !brush.buildUp
        val target = if (usesScratch) eng.scratch() else Canvas(layer.bitmap)
        val first = path.first()
        renderer.start(target, InputSample(first.x, first.y, pressure = 1f))
        for (i in 1 until path.size) {
            val p = path[i]
            renderer.moveTo(target, InputSample(p.x, p.y, pressure = 1f))
        }
        if (usesScratch) {
            eng.flattenScratchOnto(
                layer,
                brush.strokeOpacityCap,
                erasing = brush.category == BrushCategory.ERASER,
                mixing = brush.pigmentMixing,
                wetness = brush.wetness,
            )
        }
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
        strokePreviewCompositor.beginStroke()
        mirrorRenderers = emptyList()
        // Deliberately NOT clearing pendingShapeAssist here -- see its own field doc comment for
        // why it needs to outlive this cleanup (a Snackbar offering it is shown right after this
        // call, on endStroke's path, and stays alive well past it).
        capturingShapeAssist = false
        shapeAssistPoints.clear()
        shapeAssistBrush = null
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
        val xs = navMoveXs
        val ys = navMoveYs
        var count = 0
        for (pid in navPointerIds) {
            if (count >= xs.size) break
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

        // Canvas px below which a selection "move" counts as a tap (see endSelectionGesture).
        // Half a pixel: a real drag at any zoom moves at least a whole canvas pixel, while stylus
        // jitter on a tap stays far under it.
        private const val SELECTION_MOVE_EPSILON_PX = 0.5f

        // Base padding (canvas units) around the cumulative stroke bounds in the live preview, so a
        // dab's soft falloff edge and bilinear filtering don't get cut right at the seam.
        private const val PREVIEW_BOUNDS_PAD_PX = 4f

        // Safety cap on Smart Shape Assist's parallel point capture (see shapeAssistPoints) -- a
        // very long, slow drag shouldn't grow this list unboundedly. Recognition doesn't need
        // every sample past this many anyway (ShapeAssist.recognize's Douglas-Peucker simplifies
        // heavily regardless); this only stops capturing further points, it never affects the
        // actual dab rendering path above.
        private const val MAX_SHAPE_ASSIST_POINTS = 3000
    }
}

/**
 * Draws the stroke's target layer plus its live scratch overlay for [DrawingCanvasView.onDraw], so
 * what the user sees mid-stroke matches what pen-up will commit. Split out of the View (taking a
 * plain [Canvas]) so a Robolectric `@GraphicsMode(NATIVE)` test can run the exact production code
 * against a real Skia bitmap -- the View itself can't be drawn in a JVM test because onDraw() reaches
 * into the app singleton for paper-texture settings.
 *
 * What commit does (CanvasEngine.flattenScratchOnto + the plain layer draw): the scratch is baked
 * into the layer bitmap (SRC_OVER at the stroke's opacity cap, DST_OUT for erasers, MULTIPLY for
 * pigment mixing) with NO layer opacity/blend involved, and only THEN is the finished layer composited
 * with its opacity and blend mode. The preview reproduces that order with an offscreen buffer:
 * layer + scratch in the buffer, layer opacity/blend applied once when the buffer is restored.
 *
 * Three defects this replaces (both were in onDraw, neither in the frozen dab loop):
 *  1. The offscreen buffer used to be bounded by only the LAST frame's dirty union and was the ONLY
 *     place layer.bitmap got drawn. saveLayer clips to its bounds, and (see invalidateDirty) every
 *     frame damages and redraws the whole view, so an eraser/Pastel drag drew the layer only in a
 *     small moving rectangle -- the rest of the layer blinked away, as did the eraser marks laid down
 *     earlier in the same stroke. Now the bounds are the CUMULATIVE stroke bounds: outside them the
 *     scratch is provably empty, so the layer is drawn plainly there (clipOutRect) and is exactly
 *     right, while the offscreen buffer only covers the region the stroke has actually touched. Cost
 *     therefore grows with the stroke rather than with the canvas, and a mid-stroke Layers-panel
 *     opacity/blend change is always reflected because the plain path reads layerPaint every frame.
 *  2. A non-Normal layer blend mode used to take the plain path, which blends the layer but then
 *     draws the scratch Normal on top -- ink on a Multiply/Screen layer looked wrong until pen-up.
 *     Any non-Normal layer blend now takes the offscreen path too.
 *  3. Same shape at layer opacity below 100%: the plain path attenuated the ink and the layer
 *     content beneath it separately instead of the merged layer once, so ink over existing content
 *     on a dimmed layer looked different until pen-up. Also routed offscreen now.
 *
 * Seam note: the plain region and the offscreen region use the SAME integer-aligned rect (clipped
 * out of one, saveLayer'd for the other), so they tile without overlap. That is exact on
 * axis-aligned views; on a rotated view the two clip edges are each anti-aliased independently, so a
 * faint seam along the rotated bounds edge is possible -- not checkable without hardware.
 */
internal class StrokePreviewCompositor {
    private val strokeBounds = RectF()
    private var hasStrokeBounds = false
    private val regionRect = RectF()

    // Deliberately its own Paint (never the view's layerPaint): drawn with FILTER_BITMAP so the
    // layer inside the offscreen buffer is sampled with the same bilinear filtering as the plain
    // draw outside it -- with a null paint the two halves would visibly differ when zoomed.
    private val scratchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Clears the cumulative bounds. Call at stroke start and again at cleanup. */
    fun beginStroke() {
        strokeBounds.setEmpty()
        hasStrokeBounds = false
    }

    /** Unions [canvasSpaceRect] (a dab's dirty rect, in canvas pixels) into the stroke's bounds. */
    fun noteDirty(canvasSpaceRect: RectF) {
        if (hasStrokeBounds) {
            strokeBounds.union(canvasSpaceRect)
        } else {
            strokeBounds.set(canvasSpaceRect)
            hasStrokeBounds = true
        }
    }

    /**
     * @param layerPaint already carries the layer's opacity (alpha) and blend mode -- a non-null
     *   `layerPaint.blendMode`, or an alpha below 255, is what routes a layer to the offscreen path.
     * @param scratchBlendMode DST_OUT (eraser), MULTIPLY (pigment mixing) or null (plain ink).
     * @param cap the brush's stroke-level opacity cap.
     * @param pad canvas-unit padding around the cumulative bounds (soft dab edges, filtering).
     */
    fun drawLayerWithScratch(
        canvas: Canvas,
        layerBitmap: Bitmap,
        scratch: Bitmap,
        layerPaint: Paint,
        scratchBlendMode: BlendMode?,
        cap: Float,
        pad: Float,
    ) {
        val capClamped = cap.coerceIn(0f, 1f)
        if (scratchBlendMode == null && layerPaint.blendMode == null && layerPaint.alpha == 255) {
            // Normal ink on a Normal, fully opaque layer: no offscreen buffer needed. Layer and scratch
            // both go straight to the destination SRC_OVER, which is associative with the commit's
            // scratch-into-layer-then-layer-onto-destination order, so this is already exact. It is
            // NOT exact below full layer opacity (the ink would be attenuated by the layer opacity
            // separately from the layer content under it, instead of the merged layer once), which is
            // why that case takes the offscreen path too.
            canvas.drawBitmap(layerBitmap, 0f, 0f, layerPaint)
            scratchPaint.alpha = (capClamped * 255).toInt()
            scratchPaint.blendMode = null
            canvas.drawBitmap(scratch, 0f, 0f, scratchPaint)
            return
        }

        val canvasW = layerBitmap.width.toFloat()
        val canvasH = layerBitmap.height.toFloat()
        if (hasStrokeBounds) {
            regionRect.set(strokeBounds)
            regionRect.inset(-pad, -pad)
            regionRect.set(floor(regionRect.left), floor(regionRect.top), ceil(regionRect.right), ceil(regionRect.bottom))
            if (!regionRect.intersect(0f, 0f, canvasW, canvasH)) {
                // The whole stroke so far is off-canvas: scratch is empty, plain layer is exact.
                canvas.drawBitmap(layerBitmap, 0f, 0f, layerPaint)
                return
            }
            canvas.save()
            canvas.clipOutRect(regionRect)
            canvas.drawBitmap(layerBitmap, 0f, 0f, layerPaint)
            canvas.restore()
        } else {
            // No stroke bounds yet (not reachable today -- startStroke always stamps and notes a dirty
            // rect before any draw -- but a reordering refactor must degrade to correct, not to blank).
            regionRect.set(0f, 0f, canvasW, canvasH)
        }

        // layerPaint is saveLayer's OWN paint, not used inside the buffer: it is applied once, when
        // the finished buffer composites onto the real destination, which is where a non-Normal
        // blend mode has actual content to blend against (against the buffer's empty backdrop it
        // would degrade to plain painting). The DST_OUT/MULTIPLY scratch draw likewise needs the
        // buffer's real alpha channel -- the View's own canvas doesn't reliably provide one, which
        // is the classic "eraser renders black while dragging" gotcha.
        val saveCount = canvas.saveLayer(regionRect, layerPaint)
        scratchPaint.alpha = 255
        scratchPaint.blendMode = null
        canvas.drawBitmap(layerBitmap, 0f, 0f, scratchPaint)
        scratchPaint.alpha = (capClamped * 255).toInt()
        scratchPaint.blendMode = scratchBlendMode
        canvas.drawBitmap(scratch, 0f, 0f, scratchPaint)
        canvas.restoreToCount(saveCount)
    }
}
