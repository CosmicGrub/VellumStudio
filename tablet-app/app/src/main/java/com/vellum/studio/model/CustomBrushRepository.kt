package com.vellum.studio.model

import android.content.Context
import com.vellum.studio.canvas.Brush
import com.vellum.studio.util.DiagnosticLog
import com.vellum.studio.util.JsonFileStore
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * User-created brush presets — one small aggregate JSON file, same reasoning and same
 * load-all/mutate/save-all-under-a-lock pattern as [PaletteRepository] (few of them, all tiny,
 * simpler than per-brush files). A custom brush is a full [Brush] value with a user-chosen name
 * and a fresh id (`custom_<uuid>`, so it can never collide with a built-in preset's id) — created
 * by tweaking an existing preset's parameters in the brush editor and saving, not built from
 * scratch, so it always starts from a known-good, already-tuned starting point.
 *
 * DATA SAFETY (see [JsonFileStore]): this file is the user's own creative work, so a read that goes
 * wrong must never turn into "no brushes" followed by a save of just the new one. Entries decode one
 * at a time, so a brush this build can't read (an unknown [com.vellum.studio.canvas.BrushCategory]
 * constant after a downgrade, a required field a newer build renamed) costs only itself; an
 * unparseable file is set aside as `custom_brushes.json.corrupt`; writes are atomic. The file stays
 * a bare JSON array on purpose -- an older build reading a wrapped envelope would call it corrupt.
 */
class CustomBrushRepository(private val appContext: Context, recovery: RecoveryNotices = RecoveryNotices()) {

    // Lenient on-disk user data (ignoreUnknownKeys) plus coerceInputValues so a wrong-typed value in a
    // field that HAS a default falls back to it. It cannot rescue a field with no default (category,
    // name, id, baseSizePx) -- those entries are skipped by the per-element decode in JsonFileStore.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true }

    private val store = JsonFileStore(
        fileProvider = { File(appContext.getExternalFilesDir(null), "custom_brushes.json") },
        json = json,
        label = "custom brushes",
        log = { DiagnosticLog.log(appContext, "CustomBrushRepository", it) },
        onRecovery = recovery::report,
    )

    // Held for reads too, not just read-modify-write: a read that finds the file damaged moves it
    // aside, and that must not interleave with a writer that just replaced it.
    private val writeLock = Mutex()

    suspend fun loadCustomBrushes(): List<Brush> = withContext(Dispatchers.IO) {
        writeLock.withLock { store.readList(Brush.serializer()).items }
    }

    suspend fun saveBrush(brush: Brush): List<Brush> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            store.update(Brush.serializer()) { current -> current.filterNot { it.id == brush.id } + brush }
        }
    }

    suspend fun deleteBrush(id: String): List<Brush> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            store.update(Brush.serializer()) { current -> current.filterNot { it.id == id } }
        }
    }
}
