package com.vellum.studio.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [DurableFile]'s contract in isolation: at every instant the target is the complete old file or
 * the complete new one. Plain JUnit (no Robolectric) -- it is pure java.io/java.nio, so this also
 * proves the helper has no hidden Android dependency.
 */
class DurableFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun target() = File(tmp.root, "data.json")

    @Test
    fun `write creates the target and leaves no tmp file behind`() {
        DurableFile.writeText(target(), "hello")
        assertEquals("hello", target().readText())
        assertFalse(DurableFile.tmpFor(target()).exists())
    }

    @Test
    fun `a writer that fails half way leaves the previous content untouched and removes the tmp`() {
        DurableFile.writeText(target(), "old-good")
        try {
            DurableFile.write(target()) { out ->
                out.write("new-but-".toByteArray())
                throw IOException("disk full")
            }
            throw AssertionError("expected the IOException to propagate")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertEquals("old-good", target().readText())
        assertFalse(DurableFile.tmpFor(target()).exists())
    }

    @Test
    fun `a failed first write leaves no target at all rather than a truncated one`() {
        try {
            DurableFile.write(target()) { throw IOException("boom") }
        } catch (_: IOException) {
        }
        assertFalse(target().exists())
        assertFalse(DurableFile.tmpFor(target()).exists())
    }

    @Test
    fun `keepBackup rotates the previous good version into bak`() {
        DurableFile.writeText(target(), "v1", keepBackup = true)
        assertFalse("nothing to back up on the very first write", DurableFile.bakFor(target()).exists())
        DurableFile.writeText(target(), "v2", keepBackup = true)
        DurableFile.writeText(target(), "v3", keepBackup = true)
        assertEquals("v3", target().readText())
        assertEquals("v2", DurableFile.bakFor(target()).readText())
    }

    @Test
    fun `an invalid current file is not allowed to overwrite the last good backup`() {
        DurableFile.writeText(target(), "good", keepBackup = true)
        DurableFile.writeText(target(), "better", keepBackup = true) // bak = good
        target().writeText("{{{ truncated garbage")                  // external corruption
        DurableFile.writeText(target(), "recovered", keepBackup = true, backupIsValid = { it.readText().startsWith("g") || it.readText().startsWith("b") })
        assertEquals("recovered", target().readText())
        assertEquals("the garbage must not have replaced the good backup", "good", DurableFile.bakFor(target()).readText())
    }

    @Test
    fun `quarantine keeps the bytes under a corrupt name and never overwrites an earlier quarantine`() {
        val f = File(tmp.root, "layer.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = DurableFile.quarantine(f)!!
        assertEquals("layer.png.corrupt", first.name)
        assertFalse(f.exists())

        f.writeBytes(byteArrayOf(9, 9))
        val second = DurableFile.quarantine(f)!!
        assertTrue(second.name.startsWith("layer.png.corrupt-"))
        assertArrayEquals(byteArrayOf(1, 2, 3), first.readBytes())
        assertArrayEquals(byteArrayOf(9, 9), second.readBytes())
    }

    @Test
    fun `sweepTmp removes only tmp files`() {
        val keep = File(tmp.root, "keep.json").apply { writeText("x") }
        val bak = File(tmp.root, "keep.json.bak").apply { writeText("x") }
        val stray = File(tmp.root, "stray.png.tmp").apply { writeText("x") }
        DurableFile.sweepTmp(tmp.root)
        assertTrue(keep.exists())
        assertTrue(bak.exists())
        assertFalse(stray.exists())
        assertNotNull(tmp.root.listFiles())
    }
}
