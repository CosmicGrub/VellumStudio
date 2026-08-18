package com.vellum.studio.model

import android.content.Context
import com.vellum.studio.canvas.Brush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * User-created brush presets — one small aggregate JSON file, same reasoning and same
 * load-all/mutate/save-all-under-a-lock pattern as [PaletteRepository] (few of them, all tiny,
 * simpler than per-brush files). A custom brush is a full [Brush] value with a user-chosen name
 * and a fresh id (`custom_<uuid>`, so it can never collide with a built-in preset's id) — created
 * by tweaking an existing preset's parameters in the brush editor and saving, not built from
 * scratch, so it always starts from a known-good, already-tuned starting point.
 */
class CustomBrushRepository(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val file: File
        get() = File(appContext.getExternalFilesDir(null), "custom_brushes.json")

    private val writeLock = Mutex()

    suspend fun loadCustomBrushes(): List<Brush> = withContext(Dispatchers.IO) {
        loadBlocking()
    }

    private fun loadBlocking(): List<Brush> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Brush>>(file.readText()) }.getOrElse { emptyList() }
    }

    private fun saveBlocking(brushes: List<Brush>) {
        file.writeText(json.encodeToString(brushes))
    }

    suspend fun saveBrush(brush: Brush): List<Brush> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val current = loadBlocking()
            val replaced = current.filterNot { it.id == brush.id } + brush
            saveBlocking(replaced)
            replaced
        }
    }

    suspend fun deleteBrush(id: String): List<Brush> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val remaining = loadBlocking().filterNot { it.id == id }
            saveBlocking(remaining)
            remaining
        }
    }
}
