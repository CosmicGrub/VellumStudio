package com.vellum.studio.canvas

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import com.vellum.studio.VellumApp
import com.vellum.studio.util.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * "Pose Reference Overlay": a figure-drawing teaching aid, not a canvas edit. Runs Google ML
 * Kit's on-device, bundled (not Play-Services-unbundled) Pose Detection model against a reference
 * image layer's bitmap and hands back joint positions already in that bitmap's own coordinate
 * space — the same space [DrawingCanvasView.onDraw] paints layers into — so the caller can draw a
 * skeleton overlay with zero further transform, exactly like the paint-by-number numbered-region
 * overlay it sits alongside. Never touches layer pixels; purely a drawn aid, like a photographer's
 * rule-of-thirds grid.
 *
 * "Cost-free AI": the model ships inside the `pose-detection-accurate` AAR itself (loaded on first
 * use), so this makes zero network calls and has zero API cost — see the dependency comment in
 * app/build.gradle.kts for the exact Maven coordinate verification.
 */
object PoseOverlay {

    data class GuideJoint(val x: Float, val y: Float, val confident: Boolean)

    /** [joints] keyed by [PoseLandmark] type constant (e.g. [PoseLandmark.LEFT_SHOULDER]). */
    data class PoseGuide(val joints: Map<Int, GuideJoint>)

    /** Which landmark pairs to connect into "bones" when drawing the overlay — a standard,
     * simplified figure-drawing skeleton (core torso + limbs + a head anchor), not the full
     * ~33-point ML Kit topology (face-mesh-level detail is more clutter than aid here). */
    val CONNECTIONS: List<Pair<Int, Int>> = listOf(
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
        PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
        PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
        PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
        PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
        PoseLandmark.NOSE to PoseLandmark.LEFT_SHOULDER,
        PoseLandmark.NOSE to PoseLandmark.RIGHT_SHOULDER,
    )

    private const val MIN_LIKELIHOOD = 0.4f

    // ML Kit's accurate model has no accuracy benefit past roughly this input resolution but a
    // real cost in latency/memory - and reference layers can be as large as the whole canvas
    // (a few thousand px). Downscaling before detection and re-projecting the returned landmark
    // coordinates back up (see the inverseScale math below) keeps this fast without ever letting
    // that downscale leak into the coordinates callers actually draw with.
    private const val MAX_INPUT_DIMENSION = 1024

    /**
     * Runs on-device pose detection against [source] (expected to be a reference-image layer's
     * bitmap). Returns null on ANY failure -- a bad/corrupt bitmap, the on-device model failing to
     * initialize, detection finding no person, whatever -- callers are expected to treat that
     * uniformly as "nothing to show", the same graceful-nothing-happens contract
     * [ShapeAssist.recognize] has, never an exception a caller needs to catch. Suspends off the
     * caller's dispatcher; safe to call from a Compose coroutine scope.
     */
    suspend fun detectPose(source: Bitmap): PoseGuide? = withContext(Dispatchers.Default) {
        DiagnosticLog.log(VellumApp.instance, "PoseOverlay", "Pose detection started (${source.width}x${source.height})")
        runCatching { detectPoseOrThrow(source) }.fold(
            onSuccess = { guide ->
                DiagnosticLog.log(VellumApp.instance, "PoseOverlay", "Pose detection finished (found=${guide != null})")
                guide
            },
            onFailure = { t ->
                DiagnosticLog.log(VellumApp.instance, "PoseOverlay", "Pose detection failed: ${t::class.java.simpleName}: ${t.message}")
                null
            },
        )
    }

    private suspend fun detectPoseOrThrow(source: Bitmap): PoseGuide? {
        val longSide = maxOf(source.width, source.height)
        val scale = if (longSide > MAX_INPUT_DIMENSION) MAX_INPUT_DIMENSION.toFloat() / longSide else 1f
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        val options = AccuratePoseDetectorOptions.Builder()
            // A single still reference photo, never a live camera stream - SINGLE_IMAGE_MODE (as
            // opposed to the streaming-tuned default) is the documented right choice here, and
            // also avoids the detector holding onto cross-frame tracking state it'll never use.
            .setDetectorMode(AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        val client = PoseDetection.getClient(options)
        try {
            val image = InputImage.fromBitmap(input, 0)
            val pose = suspendCancellableCoroutine<Pose?> { cont ->
                client.process(image)
                    .addOnSuccessListener { result -> cont.resume(result) }
                    .addOnFailureListener { cont.resume(null) }
            }
            val inverseScale = if (scale > 0f) 1f / scale else 1f
            return pose?.let { toGuide(it, inverseScale) }
        } finally {
            if (input !== source) input.recycle()
            client.close()
        }
    }

    private fun toGuide(pose: Pose, inverseScale: Float): PoseGuide? {
        val joints = pose.allPoseLandmarks.associate { lm ->
            lm.landmarkType to GuideJoint(
                x = lm.position.x * inverseScale,
                y = lm.position.y * inverseScale,
                confident = lm.inFrameLikelihood >= MIN_LIKELIHOOD,
            )
        }
        if (joints.isEmpty()) return null
        // Require at least one real "bone" to be drawable, not just isolated confident dots -
        // a couple of stray high-confidence points with nothing else recognized isn't a useful
        // figure-drawing aid and reads more like noise than a guide.
        val hasDrawableBone = CONNECTIONS.any { (a, b) -> joints[a]?.confident == true && joints[b]?.confident == true }
        return if (hasDrawableBone) PoseGuide(joints) else null
    }
}
