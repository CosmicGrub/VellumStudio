package com.vellum.studio.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * User-created palettes, stored as one small aggregate JSON file (unlike projects, which each get
 * their own folder — palettes are tiny and there are few of them, so load-all/mutate/save-all is
 * simpler than per-palette files and the whole list is cheap to round-trip).
 */
class PaletteRepository(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val file: File
        get() = File(appContext.getExternalFilesDir(null), "palettes.json")

    // Every mutator below independently does load-all -> mutate -> save-all with no shared state
    // except the file itself. Without serializing them, two calls fired close together (e.g. tapping
    // "+" to add the current color to one palette, then immediately long-pressing a swatch to remove
    // a different one) can interleave: both read the same pre-change snapshot before either writes,
    // so whichever save finishes last silently clobbers the other's change on disk. Holding this for
    // the full duration of each read-modify-write turns every mutator into one atomic step relative
    // to the others.
    private val writeLock = Mutex()

    suspend fun loadCustomPalettes(): List<Palette> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString<List<Palette>>(file.readText()) }.getOrElse { emptyList() }
    }

    private fun loadCustomPalettesBlocking(): List<Palette> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Palette>>(file.readText()) }.getOrElse { emptyList() }
    }

    private fun saveCustomPalettesBlocking(palettes: List<Palette>) {
        file.writeText(json.encodeToString(palettes))
    }

    suspend fun createPalette(name: String, initialColorArgb: Int? = null): Palette = withContext(Dispatchers.IO) {
        val palette = Palette(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled Palette" },
            colors = initialColorArgb?.let { listOf(it) } ?: emptyList(),
        )
        writeLock.withLock { saveCustomPalettesBlocking(loadCustomPalettesBlocking() + palette) }
        palette
    }

    suspend fun addColor(paletteId: String, colorArgb: Int): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val updated = loadCustomPalettesBlocking().map {
                if (it.id == paletteId && colorArgb !in it.colors) it.copy(colors = it.colors + colorArgb) else it
            }
            saveCustomPalettesBlocking(updated)
            updated
        }
    }

    suspend fun removeColor(paletteId: String, colorArgb: Int): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val updated = loadCustomPalettesBlocking().map {
                if (it.id == paletteId) it.copy(colors = it.colors.filterNot { c -> c == colorArgb }) else it
            }
            saveCustomPalettesBlocking(updated)
            updated
        }
    }

    suspend fun renamePalette(paletteId: String, newName: String): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val updated = loadCustomPalettesBlocking().map {
                if (it.id == paletteId) it.copy(name = newName.ifBlank { it.name }) else it
            }
            saveCustomPalettesBlocking(updated)
            updated
        }
    }

    suspend fun deletePalette(paletteId: String): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val updated = loadCustomPalettesBlocking().filterNot { it.id == paletteId }
            saveCustomPalettesBlocking(updated)
            updated
        }
    }
}
