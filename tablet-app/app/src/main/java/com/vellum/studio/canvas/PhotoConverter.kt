package com.vellum.studio.canvas

import android.graphics.Bitmap
import com.vellum.studio.VellumApp
import com.vellum.studio.util.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-device photo -> coloring-activity conversion engine. Ports tools/masterart_pipeline's proven
 * technique (tone-quantize via k-means on smoothed grayscale, then cv2.findContours per tone band
 * on a binary mask -- see that pipeline's generate.py/README.md for why this beats Canny: contours
 * traced from a thresholded mask are CLOSED by construction, which is what
 * [RegionAnalyzer]/paint-by-number needs) from the offline Python tool to a runtime Kotlin path,
 * using OpenCV's Android AAR (org.opencv:opencv, published directly to Maven Central since 4.9.0 --
 * no OpenCV Manager APK, no separate native-loader step). Fully on-device, zero network calls,
 * zero API cost -- satisfies the project's "cost-free AI" constraint.
 *
 * Unlike the Python pipeline, there's no per-photo hand-tuning available at runtime, so this
 * doesn't try to reproduce its bespoke per-work knobs (meanshift_pre, double_bilateral,
 * median_pre combos chosen per painting after visual inspection). Instead: one well-chosen
 * pipeline shape (grayscale -> optional median pre-blur -> bilateral smoothing -> k-means tone
 * quantize -> per-band closed-contour trace), parameterized by exactly two presets -- see
 * [Preset] for the actual numbers and why each is tuned the way it is.
 *
 * [convert] doesn't just produce a candidate and hope -- it runs the *actual*
 * [RegionAnalyzer.analyze] (the same alpha>=40-is-a-wall flood fill paint-by-number uses at draw
 * time) on its own output and reports the real region count, so
 * [PhotoConversionResult.isPaintByNumberEligible] is ground truth, not a guess.
 */
object PhotoConverter {

    /** Long edge (px) of the returned full-color reference bitmap -- matches make_reference(). */
    private const val REFERENCE_LONG_EDGE = 1600

    /** Long edge (px) of the returned line-art bitmap -- matches make_lineart(). */
    private const val LINEART_LONG_EDGE = 2048

    /**
     * A candidate is only worth calling "paint-by-number eligible" if RegionAnalyzer actually
     * finds a *handful* of real closed regions, not just one or two -- one region generally means
     * the outlines didn't close at all (the whole canvas flood-fills as one background blob,
     * exactly the failure mode tools/masterart_pipeline's README documents for raw Canny output);
     * two could still just be "background + a single blob". Three is the lowest bar that means
     * "this photo actually segmented into multiple genuinely fillable shapes", without being so
     * strict that a legitimately simple/bold source photo (a single silhouette against a plain
     * sky, say) gets rejected purely for having few regions.
     */
    private const val MIN_REGIONS_FOR_PAINT_BY_NUMBER = 3

    /**
     * Pure region-count -> eligibility decision, split out of [convert] purely so this specific
     * threshold logic is directly unit-testable: everything else in this object is entangled with
     * a live OpenCV native call chain and a real [Bitmap], neither of which a plain JVM unit test
     * can construct, but this decision itself never touches either. See
     * [MIN_REGIONS_FOR_PAINT_BY_NUMBER]'s own doc for the reasoning behind the threshold value;
     * this function only applies that decision, it doesn't own the reasoning.
     */
    internal fun eligibleForPaintByNumber(regionCount: Int): Boolean =
        regionCount >= MIN_REGIONS_FOR_PAINT_BY_NUMBER

    private val openCvReady = AtomicBoolean(false)

    private fun ensureOpenCvLoaded() {
        if (openCvReady.get()) return
        synchronized(this) {
            if (openCvReady.get()) return
            check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
            openCvReady.set(true)
        }
    }

    enum class Preset(
        val label: String,
        val toneBands: Int,
        val bilateralD: Int,
        val bilateralSigma: Double,
        /** medianBlur kernel size applied to raw grayscale before bilateral smoothing; 0 = skip. Must be odd. */
        val medianPreKernel: Int,
        val minArcLength: Double,
        val closeKernelSize: Int,
        val openKernelSize: Int,
        val strokeThicknessPx: Int,
    ) {
        /**
         * Fewer tone bands + heavier pre-smoothing (median pre-blur, wider bilateral sigma) + a
         * higher min-arc-length floor + bigger morphological close/open kernels + thicker strokes
         * = bolder, more forgiving line art: small/noisy contours get filtered or fused away
         * entirely rather than surviving as stray unclosed detail. Trades fidelity for a much
         * better shot at clean paint-by-number closure on a busy real photo, plus simpler shapes
         * for a beginner/kid to color. Recommended default for arbitrary camera-roll photos,
         * which -- unlike the curated, pre-cropped masterwork scans -- are far more likely to
         * carry background clutter, motion blur, or sensor noise.
         */
        SIMPLE(
            label = "Simple",
            toneBands = 4,
            bilateralD = 9,
            bilateralSigma = 90.0,
            medianPreKernel = 5,
            minArcLength = 55.0,
            closeKernelSize = 7,
            openKernelSize = 5,
            strokeThicknessPx = 3,
        ),

        /**
         * More tone bands + lighter smoothing (no median pre-blur; bilateral(9,75,75), the same
         * default most of the Python pipeline's WORKS entries settled on) + a lower
         * min-arc-length floor + smaller morphological kernels = more faithful to the source
         * photo's actual tonal structure, at the cost of being more likely to fail closed-contour
         * closure (and so paint-by-number eligibility) on busy or noisy photos.
         */
        DETAILED(
            label = "Detailed",
            toneBands = 6,
            bilateralD = 9,
            bilateralSigma = 75.0,
            medianPreKernel = 0,
            minArcLength = 35.0,
            closeKernelSize = 5,
            openKernelSize = 3,
            strokeThicknessPx = 2,
        ),
    }

    data class PhotoConversionResult(
        /** Full-color reference bitmap, long edge resized to [REFERENCE_LONG_EDGE]. */
        val reference: Bitmap,
        /** Transparent-background line-art bitmap (opaque black strokes), long edge [LINEART_LONG_EDGE]. */
        val lineArt: Bitmap,
        /** True when [RegionAnalyzer.analyze] found at least [MIN_REGIONS_FOR_PAINT_BY_NUMBER] real regions in [lineArt]. */
        val isPaintByNumberEligible: Boolean,
        /** The actual region count RegionAnalyzer found -- ground truth, not a guess. */
        val regionCount: Int,
    )

    /**
     * Converts [source] into a reference + line-art pair using [preset]'s parameters. CPU-bound
     * (k-means over up to ~3M samples, plus several full-image filter passes) -- runs on
     * [Dispatchers.Default], so call this from a coroutine off the main thread.
     */
    suspend fun convert(source: Bitmap, preset: Preset): PhotoConversionResult =
        withContext(Dispatchers.Default) {
            DiagnosticLog.log(
                VellumApp.instance, "PhotoConverter",
                "Photo conversion started (preset=${preset.label}, ${source.width}x${source.height})",
            )
            try {
                ensureOpenCvLoaded()

                val reference = makeReference(source)
                val lineArt = makeLineArt(source, preset)

                val regionMap = RegionAnalyzer.analyze(lineArt)
                val result = PhotoConversionResult(
                    reference = reference,
                    lineArt = lineArt,
                    isPaintByNumberEligible = eligibleForPaintByNumber(regionMap.regions.size),
                    regionCount = regionMap.regions.size,
                )
                DiagnosticLog.log(
                    VellumApp.instance, "PhotoConverter",
                    "Photo conversion finished (regions=${result.regionCount}, paintByNumberEligible=${result.isPaintByNumberEligible})",
                )
                result
            } catch (c: CancellationException) {
                // Normal coroutine cancellation (e.g. the caller navigated away mid-conversion),
                // not a real failure -- must still propagate, just not logged as one.
                throw c
            } catch (t: Throwable) {
                DiagnosticLog.log(VellumApp.instance, "PhotoConverter", "Photo conversion failed: ${t::class.java.simpleName}: ${t.message}")
                throw t
            }
        }

    /** Plain resize (no OpenCV needed for this half) -- mirrors make_reference()'s long-edge downscale. */
    private fun makeReference(source: Bitmap): Bitmap {
        val scale = REFERENCE_LONG_EDGE.toDouble() / max(source.width, source.height)
        if (scale >= 1.0) return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    /** Ports make_lineart(): resize -> grayscale -> [optional median] -> bilateral -> k-means tone-quantize -> per-band closed contours. */
    private fun makeLineArt(source: Bitmap, preset: Preset): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(source, srcMat)

        val resized = resizeLongEdge(srcMat, LINEART_LONG_EDGE)
        if (resized !== srcMat) srcMat.release()

        val gray = Mat()
        when (resized.channels()) {
            4 -> Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGBA2GRAY)
            3 -> Imgproc.cvtColor(resized, gray, Imgproc.COLOR_RGB2GRAY)
            else -> resized.copyTo(gray)
        }
        resized.release()

        var work = gray
        if (preset.medianPreKernel > 0) {
            val medianOut = Mat()
            Imgproc.medianBlur(work, medianOut, preset.medianPreKernel)
            work = medianOut
        }

        val smooth = Mat()
        Imgproc.bilateralFilter(work, smooth, preset.bilateralD, preset.bilateralSigma, preset.bilateralSigma)
        if (work !== gray) work.release()
        gray.release()

        val lines = quantizeAndTraceContours(smooth, preset)
        smooth.release()

        val zero = Mat.zeros(lines.rows(), lines.cols(), CvType.CV_8UC1)
        val rgba = Mat()
        Core.merge(listOf(zero, zero, zero, lines), rgba)
        zero.release()
        lines.release()

        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    private fun resizeLongEdge(mat: Mat, longEdge: Int): Mat {
        val scale = longEdge.toDouble() / max(mat.rows(), mat.cols())
        if (scale >= 1.0) return mat
        val w = (mat.cols() * scale).roundToInt().coerceAtLeast(1)
        val h = (mat.rows() * scale).roundToInt().coerceAtLeast(1)
        val out = Mat()
        Imgproc.resize(mat, out, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return out
    }

    /** k-means tone-quantizes [smooth] into [Preset.toneBands] bands, then traces each non-lightest band's closed contours onto a fresh 8U mask. */
    private fun quantizeAndTraceContours(smooth: Mat, preset: Preset): Mat {
        val rows = smooth.rows()
        val cols = smooth.cols()
        val total = rows * cols

        val samples = Mat()
        smooth.reshape(1, total).convertTo(samples, CvType.CV_32F)

        val labels = Mat()
        val centers = Mat()
        val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 20, 0.5)
        Core.kmeans(samples, preset.toneBands, labels, criteria, 5, Core.KMEANS_PP_CENTERS, centers)
        samples.release()

        val labelsImg = labels.reshape(1, rows)
        labels.release()

        val centerVals = DoubleArray(preset.toneBands) { centers.get(it, 0)[0] }
        centers.release()
        // Lightest first (paper/background), so index 0 of this order is skipped below.
        val lightestToDarkest = (0 until preset.toneBands).sortedByDescending { centerVals[it] }

        val lines = Mat.zeros(rows, cols, CvType.CV_8UC1)
        val closeKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(preset.closeKernelSize.toDouble(), preset.closeKernelSize.toDouble()),
        )
        val openKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(preset.openKernelSize.toDouble(), preset.openKernelSize.toDouble()),
        )

        for (bandIdx in lightestToDarkest.drop(1)) {
            val bandConst = Mat(labelsImg.size(), CvType.CV_32S, Scalar(bandIdx.toDouble()))
            val mask = Mat()
            Core.compare(labelsImg, bandConst, mask, Core.CMP_EQ)
            bandConst.release()
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openKernel)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
            hierarchy.release()

            for (c in contours) {
                val c2f = MatOfPoint2f(*c.toArray())
                val arcLen = Imgproc.arcLength(c2f, true)
                c2f.release()
                if (arcLen > preset.minArcLength) {
                    Imgproc.drawContours(lines, listOf(c), -1, Scalar(255.0), preset.strokeThicknessPx)
                }
                c.release()
            }
            mask.release()
        }

        closeKernel.release()
        openKernel.release()
        labelsImg.release()

        return lines
    }
}
