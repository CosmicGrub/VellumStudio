package com.vellum.studio.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The one place every user-picked photo is decoded: the Coloring Book "My Photos" import, the
 * Layers-panel reference picker and the editor's drag-and-drop all go through [decode]. They used
 * to be three copy-pasted `BitmapFactory.decodeStream(it)` calls, which shared four defects:
 *
 *  1. EXIF orientation was ignored. A phone portrait is stored as landscape pixels plus an
 *     orientation tag, and the photo picker hands back the ORIGINAL file, so portraits came in
 *     sideways and (for the coloring book) the rotated line art was then saved permanently.
 *     [ImageDecoder] applies the tag itself, and reports [ImageDecoder.ImageInfo.size] already in
 *     the rotated frame (ImageImportTest pins both with an orientation-6 JPEG under Robolectric's
 *     native decoder), so the size cap below is computed in the same frame it is applied in.
 *  2. Nothing bounded the decode, so a 50 to 200 MP photo allocated its full ARGB size (200 to
 *     800 MB) before being letterboxed onto the canvas or downsized by PhotoConverter. The header
 *     listener here sets a target size from the header alone, so the full-size pixels are never
 *     allocated (the native decoder samples while it decodes).
 *  3. A HARDWARE bitmap (ImageDecoder's default allocator) cannot be drawn into the software
 *     Canvas that CanvasEngine.addImageLayer uses, nor read by Utils.bitmapToMat / getPixels; a
 *     wide-gamut (Display-P3, common on Samsung cameras) or 16-bit source can come back as
 *     RGBA_F16, which bitmapToMat rejects. So: software allocator, sRGB target, and a final
 *     ARGB_8888 guard.
 *  4. Any failure, out-of-memory included, collapsed to one generic "couldn't read" (or, in the
 *     layer picker, to nothing at all). [Result.Failed] carries a [FailureReason] so the caller
 *     can tell "too big" from "not an image", and both paths show the message.
 *
 * Everything stays on-device and this touches neither the stylus input rules nor the stamp loop.
 * The PhotoConverter golden-master fixtures are decoded with BitmapFactory by their own tests, so
 * they do not depend on this file.
 */
object ImageImport {

    /**
     * Long-edge cap for the Coloring Book import. PhotoConverter itself resizes to a 2048 long edge
     * (its LINEART_LONG_EDGE, private there), so 2x that leaves its INTER_AREA downscale real
     * source detail to average instead of feeding it a decoder-scaled 2048 image, while a 50 MP
     * photo still costs about 50 MB instead of about 200 MB.
     */
    const val COLORING_IMPORT_LONG_EDGE = 4096

    /**
     * Long-edge cap for a reference layer on a [canvasWidth] x [canvasHeight] canvas: 2x the
     * canvas's larger side. addImageLayer letterboxes the photo into the canvas with a bilinear
     * draw, so decoding at exactly canvas size would waste nothing but a 2x margin keeps that
     * downscale from aliasing while still bounding the memory.
     */
    fun referenceLongEdge(canvasWidth: Int, canvasHeight: Int): Int = 2 * max(canvasWidth, canvasHeight)

    enum class FailureReason(val message: String) {
        /** The decoder ran out of memory even at the capped size (or the header is absurd). */
        TOO_LARGE("That image is too large to import. Try a smaller one."),

        /** Not decodable: damaged, unsupported format, or the source could not be opened. */
        UNREADABLE("Couldn't import that image. It may be damaged or in a format this device can't read."),
    }

    sealed interface Result {
        /** [bitmap] is software ARGB_8888 in sRGB, EXIF-rotated, and owned (recyclable) by the caller. */
        class Decoded(val bitmap: Bitmap) : Result

        class Failed(val reason: FailureReason, val cause: Throwable?) : Result {
            val message: String get() = reason.message
        }
    }

    /**
     * Decodes [uri] to a bitmap whose long edge is at most [maxLongEdge] (smaller images are never
     * upscaled). With [flattenAlphaOnWhite] any transparency is composited over white, so the
     * result is fully opaque: the coloring-book conversion runs RGBA2GRAY, which ignores alpha and
     * would read transparent pixels (premultiplied black) as the darkest band, and its reference
     * JPEG would get a black background. Layers keep their alpha, so pass false there.
     *
     * Blocking; call it off the main thread. Never throws (bar cancellation of the caller).
     */
    fun decode(
        context: Context,
        uri: Uri,
        maxLongEdge: Int,
        flattenAlphaOnWhite: Boolean = false,
    ): Result = try {
        decode(ImageDecoder.createSource(context.contentResolver, uri), maxLongEdge, flattenAlphaOnWhite)
    } catch (e: Exception) {
        // createSource itself can throw for a null/unsupported provider.
        Result.Failed(FailureReason.UNREADABLE, e)
    }

    /** The [ImageDecoder.Source]-level core of [decode]; internal so tests can feed it a File. */
    internal fun decode(source: ImageDecoder.Source, maxLongEdge: Int, flattenAlphaOnWhite: Boolean): Result {
        return try {
            var decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                // Flattening composites in place below, which needs a mutable bitmap.
                if (flattenAlphaOnWhite) decoder.setMutableRequired(true)
                fitWithin(info.size.width, info.size.height, maxLongEdge)?.let { (w, h) -> decoder.setTargetSize(w, h) }
            }
            if (decoded.config != Bitmap.Config.ARGB_8888) {
                val converted = decoded.copy(Bitmap.Config.ARGB_8888, flattenAlphaOnWhite)
                decoded.recycle()
                decoded = converted ?: return Result.Failed(FailureReason.TOO_LARGE, null)
            }
            if (flattenAlphaOnWhite && decoded.hasAlpha()) {
                // DST_OVER paints white BEHIND what is already there, i.e. the standard
                // over-white composite, and it is correct for premultiplied partial alpha too.
                Canvas(decoded).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                decoded.setHasAlpha(false)
            }
            Result.Decoded(decoded)
        } catch (e: OutOfMemoryError) {
            Result.Failed(FailureReason.TOO_LARGE, e)
        } catch (e: Exception) {
            // ImageDecoder.DecodeException (API 31+) and the IOException it replaces on 29/30,
            // FileNotFoundException, SecurityException on a revoked grant, and so on.
            Result.Failed(FailureReason.UNREADABLE, e)
        }
    }

    /**
     * The target size that fits a [width] x [height] image within [maxLongEdge] on its long edge,
     * aspect preserved, or null when it already fits (or the input is degenerate). Both sides are
     * clamped to at least 1 so an extreme panorama cannot round to a zero-sized target.
     */
    internal fun fitWithin(width: Int, height: Int, maxLongEdge: Int): Pair<Int, Int>? {
        val longEdge = max(width, height)
        if (width <= 0 || height <= 0 || maxLongEdge <= 0 || longEdge <= maxLongEdge) return null
        val scale = maxLongEdge.toDouble() / longEdge
        return Pair((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }
}
