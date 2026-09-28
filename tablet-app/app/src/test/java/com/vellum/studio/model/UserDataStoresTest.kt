package com.vellum.studio.model

import com.vellum.studio.VellumApp
import com.vellum.studio.canvas.Brush
import com.vellum.studio.canvas.BrushCategory
import com.vellum.studio.util.RecoveryNotice
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The silent-wipe regressions for the two aggregate JSON stores the user authors content in
 * ([CustomBrushRepository], [PaletteRepository]). Before the fix each did
 * `runCatching { decode }.getOrElse { emptyList() }` and then load-all + add + save-all with a plain
 * `writeText`, so one unreadable byte (or one enum constant this build doesn't know) made the store
 * look empty and the NEXT save overwrote everything else with just the new item. Every test here is
 * the exact sequence that used to destroy data: damage the file, then mutate.
 *
 * Robolectric only for a real Context (getExternalFilesDir); the repositories are otherwise pure
 * java.io + kotlinx.serialization.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = VellumApp::class)
class UserDataStoresTest {

    private val app = RuntimeEnvironment.getApplication()
    private val notices = RecoveryNotices()
    private val dir: File get() = app.getExternalFilesDir(null)!!

    private val brushFile get() = File(dir, "custom_brushes.json")
    private val paletteFile get() = File(dir, "palettes.json")

    @After
    fun cleanUp() {
        dir.listFiles { f -> f.name.startsWith("custom_brushes.json") || f.name.startsWith("palettes.json") }?.forEach { it.delete() }
    }

    private fun corrupt(name: String): List<File> = dir.listFiles { f -> f.name.startsWith("$name.corrupt") }!!.toList()

    private fun brush(id: String) = Brush(id = id, name = "Brush $id", category = BrushCategory.PENCIL, baseSizePx = 8f)

    private fun brushJson(id: String, category: String = "PENCIL") =
        """{"id":"$id","name":"Brush $id","category":"$category","baseSizePx":8.0}"""

    @Test
    fun `a brush with an unknown category is skipped alone and the good brushes survive the next save`() = runBlocking {
        brushFile.writeText("[${brushJson("good1")},${brushJson("future", category = "SMUDGE")},${brushJson("good2")}]")
        val repo = CustomBrushRepository(app, notices)

        assertEquals(listOf("good1", "good2"), repo.loadCustomBrushes().map { it.id })

        val after = repo.saveBrush(brush("new"))
        assertEquals(listOf("good1", "good2", "new"), after.map { it.id })
        assertEquals(listOf("good1", "good2", "new"), CustomBrushRepository(app, notices).loadCustomBrushes().map { it.id })
        // The unreadable entry is not lost either: the original file was copied aside first.
        val kept = corrupt("custom_brushes.json").single()
        assertTrue(kept.readText().contains("SMUDGE"))
        assertEquals(RecoveryNotice.Kind.ENTRIES_SKIPPED, notices.notices.value.single().kind)
    }

    @Test
    fun `a brush missing a required field is skipped and does not take the list down`() = runBlocking {
        brushFile.writeText("""[${brushJson("good")},{"id":"broken","name":"No category or size"}]""")
        assertEquals(listOf("good"), CustomBrushRepository(app, notices).loadCustomBrushes().map { it.id })
    }

    @Test
    fun `a truncated custom_brushes file is preserved and a following save does not overwrite it`() = runBlocking {
        val full = "[${brushJson("a")},${brushJson("b")}]"
        val truncated = full.substring(0, full.length - 20)
        brushFile.writeText(truncated)
        val repo = CustomBrushRepository(app, notices)

        assertTrue(repo.loadCustomBrushes().isEmpty())
        val saved = repo.saveBrush(brush("fresh"))

        assertEquals(listOf("fresh"), saved.map { it.id })
        val kept = corrupt("custom_brushes.json").single()
        assertEquals("the damaged bytes are still there, untouched", truncated, kept.readText())
        assertEquals(RecoveryNotice.Kind.SET_ASIDE, notices.notices.value.single().kind)
    }

    @Test
    fun `deleting a brush from a damaged file also preserves it instead of writing the empty remainder over it`() = runBlocking {
        brushFile.writeText("not json at all")
        val repo = CustomBrushRepository(app, notices)
        repo.deleteBrush("anything")
        assertEquals("not json at all", corrupt("custom_brushes.json").single().readText())
    }

    @Test
    fun `parallel saves and loads never lose a brush`() = runBlocking {
        val repo = CustomBrushRepository(app, notices)
        (1..25).map { i ->
            async(Dispatchers.IO) {
                repo.saveBrush(brush("b$i"))
                repo.loadCustomBrushes()
            }
        }.awaitAll()
        assertEquals((1..25).map { "b$it" }.toSet(), repo.loadCustomBrushes().map { it.id }.toSet())
        assertTrue("nothing was ever damaged", corrupt("custom_brushes.json").isEmpty())
    }

    @Test
    fun `an old build's bare-array brush file still loads and no envelope is introduced`() = runBlocking {
        val repo = CustomBrushRepository(app, notices)
        repo.saveBrush(brush("a"))
        assertTrue(brushFile.readText().trimStart().startsWith("["))
    }

    @Test
    fun `a corrupt palettes file is preserved and addColor on a new palette does not wipe it`() = runBlocking {
        val original = """[{"id":"p1","name":"Mine","colors":[1,2,3]}, {"id":"p2","na"""
        paletteFile.writeText(original)
        val repo = PaletteRepository(app, notices)

        assertTrue(repo.loadCustomPalettes().isEmpty())
        val created = repo.createPalette("After", 0xFF112233.toInt())

        assertEquals(listOf(created.id), repo.loadCustomPalettes().map { it.id })
        assertEquals(original, corrupt("palettes.json").single().readText())
    }

    @Test
    fun `one undecodable palette is skipped and the rest are kept through a mutation`() = runBlocking {
        paletteFile.writeText("""[{"id":"p1","name":"Keep","colors":[5]},{"id":7,"name":[]},{"id":"p3","name":"Also keep","colors":[6]}]""")
        val repo = PaletteRepository(app, notices)

        val after = repo.addColor("p1", 9)

        assertEquals(listOf("p1", "p3"), after.map { it.id })
        assertEquals(listOf(5, 9), after.first().colors)
        assertFalse(corrupt("palettes.json").isEmpty())
    }

    @Test
    fun `palette mutations still work on a healthy file`() = runBlocking {
        val repo = PaletteRepository(app, notices)
        val p = repo.createPalette("One")
        repo.addColor(p.id, 4)
        repo.renamePalette(p.id, "Renamed")
        val list = repo.loadCustomPalettes()
        assertEquals("Renamed", list.single().name)
        assertEquals(listOf(4), list.single().colors)
        assertTrue(repo.deletePalette(p.id).isEmpty())
    }
}
