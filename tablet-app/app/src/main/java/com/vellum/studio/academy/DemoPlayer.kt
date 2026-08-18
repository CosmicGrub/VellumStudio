package com.vellum.studio.academy

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vellum.studio.canvas.Brush
import com.vellum.studio.canvas.BrushCategory
import com.vellum.studio.canvas.BrushPresets
import com.vellum.studio.canvas.InputSample
import com.vellum.studio.canvas.StrokeRenderer
import kotlinx.coroutines.delay

/**
 * Replays a [LessonDemo] onto a small standalone bitmap using the app's real stroke-rendering
 * pipeline ([StrokeRenderer] + `BrushStampCache`) — the same classes `DrawingCanvasView` uses for
 * actual touch input — so a played-back demo looks like a genuine hand-drawn stroke, not a canned
 * animation. Each [DemoStroke.path] lives in normalized 0..1 coordinates; it's scaled to this
 * player's pixel size, walked with a [PathMeasure] at a steady on-screen speed, and fed as
 * synthetic [InputSample]s through [StrokeRenderer] a few points per frame, same as real touch.
 *
 * Deliberately not tied to a real project/CanvasEngine — a lesson demo is scratch space the user
 * watches, not something they save, so it only needs a bitmap and a scratch buffer, not
 * layers/undo/persistence.
 *
 * Two playback modes share this one engine (see the demo/driven-mode split described on
 * [Lesson.demo]):
 *  - "demonstration" (paced): the caller drives [playStage] one stage at a time from a Next button.
 *  - "driven" (watch it draw): the caller calls [playAll], which plays every stage back to back.
 */
class DemoPlayer(private val widthPx: Int, private val heightPx: Int) {

    private val baseBitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).drawColor(Color.WHITE)
    }
    private val baseCanvas = Canvas(baseBitmap)

    /** What the UI actually displays: [baseBitmap] plus, while a stroke is in flight, a live preview of its scratch. */
    val bitmap: Bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    private val displayCanvas = Canvas(bitmap)

    private var scratchBitmap: Bitmap? = null
    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flattenPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Bumped on every visible change; a Compose caller keys its ImageBitmap conversion off this. */
    var revision by mutableStateOf(0)
        private set

    /** Index of the stage currently playing, for a caption overlay — -1 when idle. */
    var activeStageIndex by mutableStateOf(-1)
        private set

    init { refreshDisplay() }

    /** Clears back to a blank white page — call before replaying from the start. */
    fun reset() {
        baseCanvas.drawColor(Color.WHITE)
        activeStageIndex = -1
        refreshDisplay()
    }

    suspend fun playAll(demo: LessonDemo, speedPxPerSec: Float = 850f) {
        demo.stages.forEachIndexed { index, stage ->
            playStage(stage, speedPxPerSec, stageIndex = index)
            delay(450)
        }
        activeStageIndex = -1
    }

    /**
     * Plays one stage. [stageIndex], when given, drives the caption overlay (see [activeStageIndex])
     * for paced "Step Through" playback the same way [playAll] does for itself internally - without
     * it, the LessonScreen caller would draw the stage's strokes with no caption shown at all.
     */
    suspend fun playStage(stage: DemoStage, speedPxPerSec: Float = 850f, stageIndex: Int? = null) {
        if (stageIndex != null) activeStageIndex = stageIndex
        for (stroke in stage.strokes) {
            playStroke(stroke, speedPxPerSec)
        }
        refreshDisplay()
    }

    private suspend fun playStroke(stroke: DemoStroke, speedPxPerSec: Float) {
        val brush = BrushPresets.byId(stroke.brushId)
        val renderer = StrokeRenderer(brush, stroke.colorArgb, stroke.sizeMultiplier, 1f)

        val pixelPath = Path()
        val scaleMatrix = Matrix().apply { setScale(widthPx.toFloat(), heightPx.toFloat()) }
        stroke.path.transform(scaleMatrix, pixelPath)

        val measure = PathMeasure(pixelPath, false)
        val length = measure.length.coerceAtLeast(1f)

        val usesScratch = !brush.buildUp
        val scratch = if (usesScratch) ensureScratch() else null
        val target = if (scratch != null) Canvas(scratch) else baseCanvas

        val stepPx = 2.5f
        val fps = 1000f / FRAME_MS
        val pointsPerFrame = ((speedPxPerSec / fps) / stepPx).toInt().coerceAtLeast(1)

        val pos = FloatArray(2)
        var distance = 0f
        var first = true
        var pointsThisFrame = 0

        while (distance <= length) {
            measure.getPosTan(distance, pos, null)
            val t = (distance / length).coerceIn(0f, 1f)
            val sample = InputSample(x = pos[0], y = pos[1], pressure = taperedPressure(t))
            if (first) {
                renderer.start(target, sample)
                first = false
            } else {
                renderer.moveTo(target, sample)
            }
            distance += stepPx
            pointsThisFrame++
            if (pointsThisFrame >= pointsPerFrame) {
                pointsThisFrame = 0
                refreshDisplay(liveScratch = scratch, liveBrush = brush)
                delay(FRAME_MS)
            }
        }
        // One final sample exactly at the path's end so the stroke doesn't fall a fraction short.
        measure.getPosTan(length, pos, null)
        renderer.moveTo(target, InputSample(x = pos[0], y = pos[1], pressure = taperedPressure(1f)))

        if (scratch != null) {
            flattenPaint.alpha = (brush.strokeOpacityCap.coerceIn(0f, 1f) * 255).toInt()
            flattenPaint.blendMode = when {
                brush.category == BrushCategory.ERASER -> BlendMode.DST_OUT
                brush.pigmentMixing -> BlendMode.MULTIPLY
                else -> null
            }
            baseCanvas.drawBitmap(scratch, 0f, 0f, flattenPaint)
            flattenPaint.blendMode = null
            flattenPaint.alpha = 255
        }
        refreshDisplay()
    }

    private fun ensureScratch(): Bitmap {
        var s = scratchBitmap
        if (s == null) {
            s = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            scratchBitmap = s
        } else {
            s.eraseColor(Color.TRANSPARENT)
        }
        return s
    }

    private fun refreshDisplay(liveScratch: Bitmap? = null, liveBrush: Brush? = null) {
        displayCanvas.drawBitmap(baseBitmap, 0f, 0f, null)
        if (liveScratch != null && liveBrush != null) {
            previewPaint.blendMode = when {
                liveBrush.category == BrushCategory.ERASER -> BlendMode.DST_OUT
                liveBrush.pigmentMixing -> BlendMode.MULTIPLY
                else -> null
            }
            displayCanvas.drawBitmap(liveScratch, 0f, 0f, previewPaint)
            previewPaint.blendMode = null
        }
        revision++
    }

    /** Eases pressure up from the start and back down to the end so strokes taper like a real pen lift, instead of starting/ending at an abrupt full-pressure blob. */
    private fun taperedPressure(t: Float): Float {
        val edge = 0.08f
        val fadeIn = smoothstep(0f, edge, t)
        val fadeOut = smoothstep(0f, edge, 1f - t)
        val taper = minOf(fadeIn, fadeOut)
        return (0.25f + 0.65f * taper).coerceIn(0.15f, 1f)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val tt = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return tt * tt * (3f - 2f * tt)
    }

    private companion object {
        const val FRAME_MS = 32L
    }
}
