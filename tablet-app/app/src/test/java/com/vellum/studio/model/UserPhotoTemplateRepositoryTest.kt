package com.vellum.studio.model

import android.graphics.Bitmap
import android.graphics.Color
import com.vellum.studio.VellumApp
import com.vellum.studio.canvas.PhotoConverter
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * "My Photos" index durability. The source photo is not kept, so the converted `_reference.jpg` +
 * `_lineart.png` pair is the only copy of what the user imported; before the fix a torn or unreadable
 * `index.json` read as an empty list and the next save wrote only the new entry over it, orphaning every
 * earlier pair forever. These tests damage the index and then keep using the repository.
 *
 * NATIVE graphics because the save path encodes real PNG/JPEG bytes (the same reason as
 * ProjectDurabilityTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], application = VellumApp::class)
class UserPhotoTemplateRepositoryTest {

    private val app = RuntimeEnvironment.getApplication()
    private val notices = RecoveryNotices()
    private val photoDir get() = File(app.getExternalFilesDir(null), "photo_templates").apply { mkdirs() }
    private val index get() = File(photoDir, "index.json")

    private fun repo() = UserPhotoTemplateRepository(app, notices)

    private fun result(color: Int = Color.RED) = PhotoConverter.PhotoConversionResult(
        reference = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) },
        lineArt = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) },
        isPaintByNumberEligible = true,
        regionCount = 9,
    )

    private fun corruptIndexFiles() = photoDir.listFiles { f -> f.name.startsWith("index.json.corrupt") }!!.toList()

    @Test
    fun `a truncated index is preserved and rebuilt from the reference and line-art pairs`() = runBlocking {
        val r = repo()
        val a = r.save("Cat", PhotoConverter.Preset.SIMPLE, result())
        val b = r.save("Dog", PhotoConverter.Preset.DETAILED, result(Color.BLUE))
        val full = index.readText()
        val truncated = full.substring(0, full.length / 2)
        index.writeText(truncated)

        // A fresh repository, as after a process restart.
        val list = repo().list()

        assertEquals(setOf(a.id, b.id), list.map { it.id }.toSet())
        list.forEach {
            assertTrue(repo().referenceFile(it).isFile)
            assertTrue(repo().lineArtFile(it).isFile)
            assertTrue(it.name.startsWith("Recovered photo"))
        }
        assertEquals("the damaged index is kept byte-for-byte", truncated, corruptIndexFiles().single().readText())
        // The rebuilt index was persisted: a further restart does not need to rebuild again.
        assertEquals(2, repo().list().size)
        assertEquals("exactly one 'set aside' notice was raised", 1, notices.notices.value.size)
    }

    @Test
    fun `saving after the index was damaged does not orphan the earlier photos`() = runBlocking {
        val first = repo().save("First", PhotoConverter.Preset.SIMPLE, result())
        index.writeText("[{\"id\":\"photo_x\",\"na")

        val second = repo().save("Second", PhotoConverter.Preset.SIMPLE, result(Color.GREEN))

        val ids = repo().list().map { it.id }
        assertTrue(first.id in ids)
        assertTrue(second.id in ids)
        assertTrue(File(photoDir, first.referenceFileName).isFile)
    }

    @Test
    fun `a missing index with photo files on disk is rebuilt`() = runBlocking {
        val saved = repo().save("Lost index", PhotoConverter.Preset.SIMPLE, result())
        assertTrue(index.delete())
        assertEquals(listOf(saved.id), repo().list().map { it.id })
    }

    @Test
    fun `an entry the index cannot decode is skipped but its files are re-indexed, not orphaned`() = runBlocking {
        val saved = repo().save("Keep me", PhotoConverter.Preset.SIMPLE, result())
        index.writeText("""[{"id":"${saved.id}","name":12}]""")
        val list = repo().list()
        assertEquals(listOf(saved.id), list.map { it.id })
        assertFalse(corruptIndexFiles().isEmpty())
    }

    @Test
    fun `a healthy index round-trips unchanged`() = runBlocking {
        val r = repo()
        val a = r.save("A", PhotoConverter.Preset.SIMPLE, result())
        val list = repo().list()
        assertEquals(listOf(a), list)
        assertTrue(corruptIndexFiles().isEmpty())
        assertTrue(notices.notices.value.isEmpty())
    }

    @Test
    fun `delete is a soft delete that undo restores completely`() = runBlocking {
        val r = repo()
        val a = r.save("Undo me", PhotoConverter.Preset.SIMPLE, result())
        val b = r.save("Stay", PhotoConverter.Preset.SIMPLE, result())

        val remaining = r.delete(a.id)
        assertEquals(listOf(b.id), remaining.map { it.id })
        assertFalse("out of the gallery folder", r.referenceFile(a).exists())
        assertTrue("but not gone", File(photoDir, "trash/${a.referenceFileName}").isFile)
        assertTrue(File(photoDir, "trash/${a.lineArtFileName}").isFile)

        val restored = r.restore(a)
        assertEquals(setOf(a.id, b.id), restored.map { it.id }.toSet())
        assertTrue(r.referenceFile(a).isFile)
        assertTrue(r.lineArtFile(a).isFile)
        assertEquals(setOf(a.id, b.id), repo().list().map { it.id }.toSet())
    }

    @Test
    fun `purging after the undo window really removes the files, and a deleted photo is not resurrected by the rebuild`() = runBlocking {
        val r = repo()
        val a = r.save("Gone", PhotoConverter.Preset.SIMPLE, result())
        r.save("Stay", PhotoConverter.Preset.SIMPLE, result())
        r.delete(a.id)
        r.purgeDeleted(a)
        assertFalse(File(photoDir, "trash/${a.referenceFileName}").exists())
        assertFalse(File(photoDir, "trash/${a.lineArtFileName}").exists())

        // Even with the index gone, the deleted one must not come back through orphan recovery.
        index.delete()
        assertFalse(a.id in repo().list().map { it.id })
    }

    @Test
    fun `a delete that was never undone is swept on the next start`() = runBlocking {
        val r = repo()
        val a = r.save("Bye", PhotoConverter.Preset.SIMPLE, result())
        r.delete(a.id)
        assertNotNull(File(photoDir, "trash").listFiles()?.firstOrNull())

        repo().list() // first load of a new process
        assertFalse(File(photoDir, "trash").exists())
    }

    @Test
    fun `undo of a photo whose files are gone does not index a dangling entry`() = runBlocking {
        val r = repo()
        val a = r.save("Ghost", PhotoConverter.Preset.SIMPLE, result())
        r.delete(a.id)
        r.purgeDeleted(a)
        assertTrue(r.restore(a).isEmpty())
    }
}
