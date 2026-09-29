package com.vellum.studio.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

/**
 * [ImageImport] against real decodes. Needs `@GraphicsMode(NATIVE)`: the default legacy graphics
 * mode has no real image decoder and would hand back blank bitmaps, so every assertion here would
 * be about a stub. The fixtures are generated in the test (a JPEG whose EXIF orientation is written
 * with the framework's own [ExifInterface], a synthetic 200 MP PNG, an alpha PNG) rather than
 * checked in, so what each one proves is visible next to the assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageImportTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun source(file: File) = ImageDecoder.createSource(file)

    private fun decoded(result: ImageImport.Result): Bitmap {
        assertTrue("expected a decode, got $result", result is ImageImport.Result.Decoded)
        return (result as ImageImport.Result.Decoded).bitmap
    }

    /** [width] x [height] JPEG, left half red and right half blue, optionally tagged with an EXIF orientation. */
    private fun jpeg(width: Int, height: Int, orientation: Int? = null): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            val color = if (x < width / 2) Color.RED else Color.BLUE
            for (y in 0 until height) bitmap.setPixel(x, y, color)
        }
        val file = tmp.newFile()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        if (orientation != null) {
            val exif = ExifInterface(file.path)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        return file
    }

    private fun isRedish(c: Int) = Color.red(c) > 180 && Color.green(c) < 90 && Color.blue(c) < 90
    private fun isBluish(c: Int) = Color.blue(c) > 180 && Color.red(c) < 90 && Color.green(c) < 90

    // --- EXIF orientation -----------------------------------------------------------------------

    @Test fun `an EXIF orientation 6 jpeg decodes with width and height swapped`() {
        val file = jpeg(60, 40, orientation = 6)

        // The old call site's behavior, as the control: BitmapFactory ignores the tag, which is
        // exactly the sideways-portrait defect. If this ever stops being 60x40 the test below no
        // longer proves that ImageImport is what fixes it.
        val legacy = BitmapFactory.decodeFile(file.path)
        assertEquals(60, legacy.width)
        assertEquals(40, legacy.height)

        val bitmap = decoded(ImageImport.decode(source(file), maxLongEdge = 4096, flattenAlphaOnWhite = false))
        assertEquals(40, bitmap.width)
        assertEquals(60, bitmap.height)
        // Orientation 6 = rotate 90 degrees clockwise to display: the red LEFT half ends up on top.
        assertTrue("top should be the red half", isRedish(bitmap.getPixel(20, 10)))
        assertTrue("bottom should be the blue half", isBluish(bitmap.getPixel(20, 50)))
    }

    @Test fun `a jpeg with no orientation tag keeps its dimensions`() {
        val bitmap = decoded(ImageImport.decode(source(jpeg(60, 40)), 4096, false))
        assertEquals(60, bitmap.width)
        assertEquals(40, bitmap.height)
        assertTrue(isRedish(bitmap.getPixel(10, 20)))
        assertTrue(isBluish(bitmap.getPixel(50, 20)))
    }

    @Test fun `the size cap is applied in the rotated frame so a capped portrait keeps its aspect`() {
        // 3000x2000 stored landscape, tagged 6 => displays 2000x3000. Cap 1000 => 667x1000 (portrait).
        val bitmap = decoded(ImageImport.decode(source(jpeg(3000, 2000, orientation = 6)), 1000, false))
        assertEquals(1000, bitmap.height)
        assertTrue("width ${bitmap.width} should be ~667", abs(bitmap.width - 667) <= 2)
    }

    // --- Size cap -------------------------------------------------------------------------------

    @Test fun `a synthetic 200 megapixel image is capped to twice the canvas long edge without a full-size decode`() {
        val file = syntheticGrayPng(width = 20_000, height = 10_000)
        val cap = ImageImport.referenceLongEdge(canvasWidth = 2048, canvasHeight = 1536) // 4096
        assertEquals(4096, cap)

        val bitmap = decoded(ImageImport.decode(source(file), cap, false))
        // Long edge at the cap, aspect (2:1) preserved. A full-size ARGB decode of this file would
        // be 800 MB, so simply finishing under the test JVM's heap is part of the assertion too.
        assertEquals(4096, bitmap.width)
        assertEquals(2048, bitmap.height)
        assertEquals(Color.WHITE, bitmap.getPixel(2000, 1000))
    }

    @Test fun `an image already within the cap is not upscaled`() {
        val bitmap = decoded(ImageImport.decode(source(jpeg(60, 40)), maxLongEdge = 4096, flattenAlphaOnWhite = false))
        assertEquals(60, bitmap.width)
    }

    @Test fun `fitWithin scales the long edge to the cap and clamps degenerate panoramas to one pixel`() {
        assertNull(ImageImport.fitWithin(100, 50, 100))
        assertNull(ImageImport.fitWithin(100, 50, 200))
        assertEquals(50 to 25, ImageImport.fitWithin(100, 50, 50))
        assertEquals(25 to 50, ImageImport.fitWithin(50, 100, 50))
        assertEquals(10 to 1, ImageImport.fitWithin(100_000, 1, 10))
        assertNull(ImageImport.fitWithin(0, 0, 10))
    }

    @Test fun `referenceLongEdge is twice the larger canvas side`() {
        assertEquals(8192, ImageImport.referenceLongEdge(4096, 3000))
        assertEquals(6000, ImageImport.referenceLongEdge(1200, 3000))
    }

    // --- Alpha ----------------------------------------------------------------------------------

    /** 20x10: x in 0..4 fully transparent, 5..9 opaque red, 10..14 50% black, 15..19 opaque blue. */
    private fun alphaPng(): File {
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        for (x in 0 until 20) {
            val color = when {
                x < 5 -> Color.TRANSPARENT
                x < 10 -> Color.RED
                x < 15 -> Color.argb(128, 0, 0, 0)
                else -> Color.BLUE
            }
            for (y in 0 until 10) bitmap.setPixel(x, y, color)
        }
        val file = tmp.newFile()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return file
    }

    @Test fun `alpha is flattened over white when asked`() {
        val bitmap = decoded(ImageImport.decode(source(alphaPng()), 4096, flattenAlphaOnWhite = true))
        assertFalse("flattened result must be opaque", bitmap.hasAlpha())
        assertEquals("transparent becomes white, not premultiplied black", Color.WHITE, bitmap.getPixel(2, 5))
        assertEquals(Color.RED, bitmap.getPixel(7, 5))
        assertEquals(Color.BLUE, bitmap.getPixel(17, 5))
        val half = bitmap.getPixel(12, 5)
        assertEquals(255, Color.alpha(half))
        // 50% black over white is mid grey.
        assertTrue("expected mid grey, got ${Integer.toHexString(half)}", abs(Color.red(half) - 127) <= 3)
        assertEquals(Color.red(half), Color.green(half))
        assertEquals(Color.red(half), Color.blue(half))
    }

    @Test fun `alpha is preserved when not flattening so reference layers keep their transparency`() {
        val bitmap = decoded(ImageImport.decode(source(alphaPng()), 4096, flattenAlphaOnWhite = false))
        assertTrue(bitmap.hasAlpha())
        assertEquals(0, Color.alpha(bitmap.getPixel(2, 5)))
        assertNotEquals(Color.WHITE, bitmap.getPixel(2, 5))
    }

    // --- Output format --------------------------------------------------------------------------

    @Test fun `the result is a software ARGB_8888 bitmap OpenCV can read`() {
        for (flatten in listOf(false, true)) {
            val bitmap = decoded(ImageImport.decode(source(jpeg(60, 40)), 4096, flatten))
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config) // HARDWARE would report Config.HARDWARE
            assertFalse(bitmap.isRecycled)
        }
    }

    // --- Failures and the Uri entry point -------------------------------------------------------

    @Test fun `bytes that are not an image fail as unreadable instead of throwing`() {
        val file = tmp.newFile().apply { writeBytes(ByteArray(512) { (it * 31).toByte() }) }
        val result = ImageImport.decode(source(file), 4096, false)
        assertTrue(result is ImageImport.Result.Failed)
        assertEquals(ImageImport.FailureReason.UNREADABLE, (result as ImageImport.Result.Failed).reason)
        assertTrue(result.message.isNotBlank())
    }

    @Test fun `a uri that cannot be opened fails as unreadable`() {
        val context = RuntimeEnvironment.getApplication()
        val result = ImageImport.decode(context, Uri.fromFile(File(tmp.root, "missing.jpg")), 4096)
        assertTrue(result is ImageImport.Result.Failed)
        assertEquals(ImageImport.FailureReason.UNREADABLE, (result as ImageImport.Result.Failed).reason)
    }

    @Test fun `the uri entry point applies orientation and the cap too`() {
        val context = RuntimeEnvironment.getApplication()
        val file = jpeg(60, 40, orientation = 6)
        val bitmap = decoded(ImageImport.decode(context, Uri.fromFile(file), 4096))
        assertEquals(40, bitmap.width)
        assertEquals(60, bitmap.height)
    }

    @Test fun `too large and unreadable have distinct user messages`() {
        assertNotEquals(ImageImport.FailureReason.TOO_LARGE.message, ImageImport.FailureReason.UNREADABLE.message)
    }

    // --- Fixture: a huge image that costs almost nothing on disk --------------------------------

    /**
     * An 8-bit grayscale, all-white PNG of [width] x [height], streamed row by row through a
     * Deflater so building it never holds the raw pixels (20000x10000 is 200 MB raw but deflates to
     * a couple of hundred KB). Hand-built because the smallest way to get a real 200 MP source is
     * a valid PNG whose pixel data is trivially compressible; allocating a 200 MP Bitmap to encode
     * it would itself be the OOM this test is about.
     */
    private fun syntheticGrayPng(width: Int, height: Int): File {
        val idat = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.BEST_SPEED)
        DeflaterOutputStream(idat, deflater).use { out ->
            val row = ByteArray(width + 1) { 0xFF.toByte() }
            row[0] = 0 // PNG filter type "None"
            repeat(height) { out.write(row) }
        }
        deflater.end()

        val file = tmp.newFile("huge.png")
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            fun chunk(type: String, data: ByteArray) {
                out.writeInt(data.size)
                val typeBytes = type.toByteArray(Charsets.US_ASCII)
                out.write(typeBytes)
                out.write(data)
                val crc = CRC32().apply { update(typeBytes); update(data) }
                out.writeInt(crc.value.toInt())
            }
            val ihdr = ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeInt(width); writeInt(height)
                    writeByte(8) // bit depth
                    writeByte(0) // color type: grayscale
                    writeByte(0); writeByte(0); writeByte(0) // deflate, adaptive filtering, no interlace
                }
            }.toByteArray()
            chunk("IHDR", ihdr)
            chunk("IDAT", idat.toByteArray())
            chunk("IEND", ByteArray(0))
        }
        return file
    }
}
