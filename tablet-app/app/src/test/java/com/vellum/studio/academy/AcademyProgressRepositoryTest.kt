package com.vellum.studio.academy

import com.vellum.studio.util.RecoveryNotice
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [AcademyProgressRepository] on the plain JVM (the file is constructor-injected): v1 -> v2
 * migration, a damaged file being preserved instead of read as "no progress" and overwritten, and
 * the load-outside-the-lock race that let a stale read wipe earlier progress.
 */
class AcademyProgressRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val notices = RecoveryNotices()
    private val file get() = File(tmp.root, "academy_progress.json")
    private val parser = Json

    private fun repo(now: Long = 1_000L) =
        AcademyProgressRepository({ file }, log = {}, recovery = notices, clock = { now })

    private fun corrupt() = tmp.root.listFiles { f -> f.name.startsWith("academy_progress.json.corrupt") }!!.toList()

    private fun onDisk(): JsonObject = parser.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun `a legacy bare array migrates to v2 keeping every completed lesson and its v1 bytes`() = runBlocking {
        val legacy = """["drawing-101/lines","drawing-101/shapes"]"""
        file.writeText(legacy)
        val r = repo()

        r.load()
        assertEquals(setOf("drawing-101/lines", "drawing-101/shapes"), r.completedLessonKeys.value)
        assertEquals("loading alone does not rewrite the file", legacy, file.readText())

        r.markComplete("color-101", "hue")

        val root = onDisk()
        assertEquals(2, root["schemaVersion"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            setOf("drawing-101/lines", "drawing-101/shapes", "color-101/hue"),
            root["lessons"]!!.jsonObject.keys,
        )
        assertEquals("1000", root["lessons"]!!.jsonObject["color-101/hue"]!!.jsonObject["completedAt"]!!.jsonPrimitive.content)
        assertEquals("the v1 file is kept as .bak", legacy, File(tmp.root, "academy_progress.json.bak").readText())

        // A fresh process sees the same thing from the v2 file.
        val again = repo()
        again.load()
        assertEquals(r.completedLessonKeys.value, again.completedLessonKeys.value)
    }

    @Test
    fun `v2 round-trips and markIncomplete clears only completion`() = runBlocking {
        val r = repo()
        r.markComplete("c", "l1")
        r.markComplete("c", "l2")
        r.markIncomplete("c", "l1")
        assertEquals(setOf("c/l2"), r.completedLessonKeys.value)
        val again = repo().also { it.load() }
        assertEquals(setOf("c/l2"), again.completedLessonKeys.value)
        assertTrue(again.isComplete("c", "l2"))
        assertEquals(1, again.completedCount("c", listOf("l1", "l2")))
    }

    @Test
    fun `fields this build does not know about, and per-lesson extras, are tolerated`() = runBlocking {
        file.writeText("""{"schemaVersion":2,"lessons":{"c/a":{"completedAt":5,"attempts":3,"bestScore":90,"newField":true}},"activity":[19000],"extra":1}""")
        val r = repo()
        r.load()
        assertEquals(setOf("c/a"), r.completedLessonKeys.value)
        r.markComplete("c", "b")
        val a = onDisk()["lessons"]!!.jsonObject["c/a"]!!.jsonObject
        assertEquals("3", a["attempts"]!!.jsonPrimitive.content)
        assertEquals("90", a["bestScore"]!!.jsonPrimitive.content)
        assertEquals("19000", onDisk()["activity"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `a truncated progress file is preserved and a later mark does not overwrite the evidence`() = runBlocking {
        val truncated = """{"schemaVersion":2,"lessons":{"c/a":{"completedAt":5},"c/b":{"compl"""
        file.writeText(truncated)
        val r = repo()

        r.load()
        assertTrue(r.completedLessonKeys.value.isEmpty())
        r.markComplete("c", "new")

        assertEquals(setOf("c/new"), r.completedLessonKeys.value)
        assertEquals(truncated, corrupt().single().readText())
        assertEquals(RecoveryNotice.Kind.SET_ASIDE, notices.notices.value.single().kind)
    }

    @Test
    fun `marking complete without ever loading first still preserves a damaged file`() = runBlocking {
        file.writeText("garbage")
        repo().markComplete("c", "x")
        assertEquals("garbage", corrupt().single().readText())
    }

    @Test
    fun `an unreadable lesson entry is skipped, kept in a copy, and the rest survive`() = runBlocking {
        val original = """{"schemaVersion":2,"lessons":{"c/a":{"completedAt":5},"c/bad":{"completedAt":"soon","attempts":"x"}}}"""
        file.writeText(original)
        val r = repo()
        r.load()
        assertEquals(setOf("c/a"), r.completedLessonKeys.value)
        r.markComplete("c", "z")
        assertEquals(original, corrupt().single().readText())
        assertEquals(setOf("c/a", "c/z"), onDisk()["lessons"]!!.jsonObject.keys)
    }

    @Test
    fun `a file from a newer schema is copied aside before this build rewrites it`() = runBlocking {
        val newer = """{"schemaVersion":3,"lessons":{"c/a":{"completedAt":5,"v3only":1}},"v3thing":{}}"""
        file.writeText(newer)
        val r = repo()
        r.load()
        r.markComplete("c", "b")
        assertEquals(newer, corrupt().single().readText())
        assertEquals(2, onDisk()["schemaVersion"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `concurrent completions interleaved with loads all reach disk`() = runBlocking {
        val r = repo()
        (1..40).map { i ->
            async(Dispatchers.IO) {
                if (i % 3 == 0) r.load()
                r.markComplete("course", "lesson$i")
                if (i % 2 == 0) r.load()
            }
        }.awaitAll()

        val expected = (1..40).map { "course/lesson$it" }.toSet()
        assertEquals(expected, r.completedLessonKeys.value)
        val fresh = repo().also { it.load() }
        assertEquals(expected, fresh.completedLessonKeys.value)
        assertTrue(corrupt().isEmpty())
    }

    @Test
    fun `a load after a mark never reverts the in-memory state to older file content`() = runBlocking {
        val r = repo()
        r.load()
        r.markComplete("c", "a")
        // Simulate the stale-read hazard: whatever a screen's LaunchedEffect load() does afterwards must not
        // shrink the set (the old code re-read the file, and a torn/late read replaced the state).
        file.writeText("[]")
        r.load()
        assertEquals(setOf("c/a"), r.completedLessonKeys.value)
        r.markComplete("c", "b")
        assertEquals(setOf("c/a", "c/b"), onDisk()["lessons"]!!.jsonObject.keys)
    }

    @Test
    fun `no tmp file is left behind`() = runBlocking {
        val r = repo()
        r.markComplete("c", "a")
        assertFalse(File(tmp.root, "academy_progress.json.tmp").exists())
    }
}
