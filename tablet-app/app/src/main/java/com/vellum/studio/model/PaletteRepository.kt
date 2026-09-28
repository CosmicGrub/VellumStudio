package com.vellum.studio.model

import android.content.Context
import com.vellum.studio.util.DiagnosticLog
import com.vellum.studio.util.JsonFileStore
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * User-created palettes, stored as one small aggregate JSON file (unlike projects, which each get
 * their own folder — palettes are tiny and there are few of them, so load-all/mutate/save-all is
 * simpler than per-palette files and the whole list is cheap to round-trip).
 *
 * DATA SAFETY: read and written through [JsonFileStore] -- a damaged file is set aside as
 * `palettes.json.corrupt` (never treated as "no palettes" and then overwritten), one undecodable
 * palette is skipped without dropping the rest, and every write is atomic.
 */
class PaletteRepository(private val appContext: Context, recovery: RecoveryNotices = RecoveryNotices()) {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }

    private val store = JsonFileStore(
        fileProvider = { File(appContext.getExternalFilesDir(null), "palettes.json") },
        json = json,
        label = "palettes",
        log = { DiagnosticLog.log(appContext, "PaletteRepository", it) },
        onRecovery = recovery::report,
    )

    // Every mutator below independently does load-all -> mutate -> save-all with no shared state
    // except the file itself. Without serializing them, two calls fired close together (e.g. tapping
    // "+" to add the current color to one palette, then immediately long-pressing a swatch to remove
    // a different one) can interleave: both read the same pre-change snapshot before either writes,
    // so whichever save finishes last silently clobbers the other's change on disk. Holding this for
    // the full duration of each read-modify-write turns every mutator into one atomic step relative
    // to the others. Reads take it too so a read that sets a damaged file aside cannot interleave
    // with a writer that just replaced it.
    private val writeLock = Mutex()

    private fun update(transform: (List<Palette>) -> List<Palette>): List<Palette> =
        store.update(Palette.serializer(), transform)

    suspend fun loadCustomPalettes(): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock { store.readList(Palette.serializer()).items }
    }

    suspend fun createPalette(name: String, initialColorArgb: Int? = null): Palette = withContext(Dispatchers.IO) {
        val palette = Palette(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled Palette" },
            colors = initialColorArgb?.let { listOf(it) } ?: emptyList(),
        )
        writeLock.withLock { update { it + palette } }
        palette
    }

    suspend fun addColor(paletteId: String, colorArgb: Int): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            update { current ->
                current.map {
                    if (it.id == paletteId && colorArgb !in it.colors) it.copy(colors = it.colors + colorArgb) else it
                }
            }
        }
    }

    suspend fun removeColor(paletteId: String, colorArgb: Int): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            update { current ->
                current.map {
                    if (it.id == paletteId) it.copy(colors = it.colors.filterNot { c -> c == colorArgb }) else it
                }
            }
        }
    }

    suspend fun renamePalette(paletteId: String, newName: String): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            update { current ->
                current.map {
                    if (it.id == paletteId) it.copy(name = newName.ifBlank { it.name }) else it
                }
            }
        }
    }

    suspend fun deletePalette(paletteId: String): List<Palette> = withContext(Dispatchers.IO) {
        writeLock.withLock { update { current -> current.filterNot { it.id == paletteId } } }
    }
}
