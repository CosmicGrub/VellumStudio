package com.vellum.studio.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [JsonFileStore]'s contract in isolation (plain JUnit, no Robolectric -- pure java.io + kotlinx):
 * a MISSING file is empty, an UNREADABLE one is set aside and never overwritten, a list decodes
 * element by element, and no write ever goes over bytes that were about to be lost.
 */
class JsonFileStoreTest {

    enum class Kind { A, B }

    // Same shape as Brush for the purpose that matters: a required enum with no default.
    @Serializable
    data class Item(val id: String, val kind: Kind, val size: Float = 1f)

    @get:Rule val tmp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val notices = mutableListOf<RecoveryNotice>()

    private fun target() = File(tmp.root, "items.json")

    private fun store() = JsonFileStore({ target() }, json, "items", onRecovery = { notices += it })

    private fun corruptSiblings(): List<File> =
        tmp.root.listFiles { f -> f.name.startsWith("items.json.corrupt") }!!.toList()

    @Test
    fun `a missing file is an empty first run and is not created by reading`() {
        val read = store().readList(Item.serializer())
        assertEquals(JsonFileStore.ListRead.State.MISSING, read.state)
        assertTrue(read.items.isEmpty())
        assertTrue(read.safeToWrite)
        assertFalse(target().exists())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `an unknown enum value skips that one entry and keeps every good one`() {
        target().writeText(
            """[{"id":"a","kind":"A"},{"id":"future","kind":"SMUDGE"},{"id":"b","kind":"B","size":2.5}]""",
        )
        val read = store().readList(Item.serializer())
        assertEquals(listOf("a", "b"), read.items.map { it.id })
        assertEquals(1, read.skipped)
        assertEquals(RecoveryNotice.Kind.ENTRIES_SKIPPED, notices.single().kind)
    }

    @Test
    fun `a wrong-typed field with a default is coerced instead of costing the entry`() {
        target().writeText("""[{"id":"a","kind":"A","size":null}]""")
        val read = store().readList(Item.serializer())
        assertEquals(1f, read.items.single().size, 0f)
        assertEquals(0, read.skipped)
    }

    @Test
    fun `entries that were skipped are copied aside before a later write can drop them`() {
        val original = """[{"id":"a","kind":"A"},{"id":"future","kind":"SMUDGE"}]"""
        target().writeText(original)
        val s = store()

        val updated = s.update(Item.serializer()) { it + Item("c", Kind.B) }

        assertEquals(listOf("a", "c"), updated.map { it.id })
        assertFalse("the live file no longer has the bad entry", target().readText().contains("SMUDGE"))
        val copy = corruptSiblings().single()
        assertEquals("the copy is the original bytes", original, copy.readText())
    }

    @Test
    fun `re-reading the same still-damaged file does not pile up copies`() {
        target().writeText("""[{"id":"a","kind":"A"},{"id":"x","kind":"NOPE"}]""")
        val s = store()
        repeat(4) { s.readList(Item.serializer()) }
        assertEquals(1, corruptSiblings().size)
    }

    @Test
    fun `a truncated file is set aside byte-for-byte, reads as empty and is never overwritten by later saves`() {
        val truncated = """[{"id":"a","kind":"A"},{"id":"b","ki"""
        target().writeText(truncated)
        val s = store()

        val read = s.readList(Item.serializer())
        assertEquals(JsonFileStore.ListRead.State.UNREADABLE_SET_ASIDE, read.state)
        assertTrue(read.items.isEmpty())
        assertTrue(read.safeToWrite)
        assertFalse("the bad file is out of the way", target().exists())
        val kept = corruptSiblings().single()
        assertEquals(truncated, kept.readText())
        assertEquals(RecoveryNotice.Kind.SET_ASIDE, notices.single().kind)
        assertEquals(kept.name, notices.single().keptAt)

        // A later mutation starts a fresh file and leaves the evidence alone.
        s.update(Item.serializer()) { it + Item("new", Kind.A) }
        assertEquals(listOf("new"), s.readList(Item.serializer()).items.map { it.id })
        assertEquals(truncated, kept.readText())
    }

    @Test
    fun `garbage bytes and an empty file are both unreadable, not empty-and-overwritable`() {
        for (bytes in listOf(byteArrayOf(0, 1, 2, -1, -2, 65), ByteArray(0))) {
            corruptSiblings().forEach { it.delete() }
            target().writeBytes(bytes)
            val read = store().readList(Item.serializer())
            assertTrue(read.unreadable)
            assertFalse(target().exists())
            assertArrayEquals(bytes, corruptSiblings().single().readBytes())
        }
    }

    @Test
    fun `valid JSON of the wrong shape is set aside like garbage`() {
        target().writeText("""{"formatVersion":9,"brushes":[]}""")
        val read = store().readList(Item.serializer())
        assertTrue(read.unreadable)
        assertFalse(target().exists())
        assertTrue(corruptSiblings().single().readText().contains("formatVersion"))
    }

    @Test
    fun `two corrupt files in a row keep both sets of bytes`() {
        val s = store()
        target().writeText("[{")
        s.readList(Item.serializer())
        target().writeText("[{\"id\"")
        s.readList(Item.serializer())
        assertEquals(setOf("[{", "[{\"id\""), corruptSiblings().map { it.readText() }.toSet())
    }

    @Test
    fun `update round-trips and leaves no tmp file`() {
        val s = store()
        s.update(Item.serializer()) { it + Item("a", Kind.A) }
        s.update(Item.serializer()) { it + Item("b", Kind.B) }
        assertEquals(listOf("a", "b"), s.readList(Item.serializer()).items.map { it.id })
        assertFalse(DurableFile.tmpFor(target()).exists())
    }

    @Test
    fun `a list that is unreadable in place is not safe to write`() {
        val read = JsonFileStore.ListRead<Item>(emptyList(), JsonFileStore.ListRead.State.UNREADABLE_IN_PLACE)
        assertFalse(read.safeToWrite)
    }
}
