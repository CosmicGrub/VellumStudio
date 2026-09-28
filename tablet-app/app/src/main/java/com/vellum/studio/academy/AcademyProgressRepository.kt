package com.vellum.studio.academy

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.vellum.studio.util.DiagnosticLog
import com.vellum.studio.util.DurableFile
import com.vellum.studio.util.JsonFileStore
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException

/**
 * What is remembered about one lesson. [completedAt] non-null IS "complete" (epoch ms; 0 for a
 * lesson carried over from the v1 format, which stored no time). The other fields are reserved for
 * the streak / review / scoring work so that landing them needs no second migration: they are read
 * and written back untouched today.
 */
@Serializable
data class LessonProgress(
    val completedAt: Long? = null,
    val lastViewedAt: Long? = null,
    val practicedAt: Long? = null,
    val bestScore: Int? = null,
    val attempts: Int = 0,
)

/** The whole `academy_progress.json` (schema v2). [activity] holds day-epoch numbers, reserved for streaks. */
@Serializable
data class AcademyProgressData(
    val schemaVersion: Int = AcademyProgressRepository.SCHEMA_VERSION,
    val lessons: Map<String, LessonProgress> = emptyMap(),
    val activity: List<Long> = emptyList(),
)

/**
 * Tracks which lessons the user has marked complete, as one small JSON file. Lesson identity is
 * `"$courseId/$lessonId"` since lesson ids are only unique within their own course.
 *
 * FORMAT: v1 was a bare array of completed keys with no version. v2 is
 * `{ schemaVersion, lessons: { key: LessonProgress }, activity: [...] }`. A v1 file is read as v2 in
 * memory and rewritten as v2 by the next change, first keeping the v1 bytes as
 * `academy_progress.json.bak`. On-disk user data, so decoding is lenient (unknown keys ignored).
 *
 * DATA SAFETY: the state is loaded ONCE per process, under the same lock the writers take, and every
 * change is applied to that in-memory copy and written back whole. That closes the old lost-update
 * window where a screen's `load()` re-read the file while a write was truncating it (or before it
 * landed), replaced the in-memory set with the stale or empty result, and the next [markComplete]
 * then persisted just that one key over all prior progress. A file that can't be read is set aside as
 * `academy_progress.json.corrupt` rather than treated as "no progress" and overwritten; writes are
 * atomic ([DurableFile]); a write failure is logged and retried on the next change (the whole state is
 * rewritten each time) instead of crashing the screen that launched it.
 *
 * [completedLessonKeys] is an in-memory, observable mirror so course/lesson list screens can show
 * progress reactively without re-reading the file on every recomposition; [load] (idempotent, safe
 * to call from any number of screens) makes sure it is populated, and [markComplete]/[markIncomplete]
 * keep it in sync.
 */
class AcademyProgressRepository internal constructor(
    private val fileProvider: () -> File,
    log: (String) -> Unit,
    recovery: RecoveryNotices,
    private val clock: () -> Long,
) {

    constructor(appContext: Context, recovery: RecoveryNotices = RecoveryNotices()) : this(
        fileProvider = { File(appContext.getExternalFilesDir(null), "academy_progress.json") },
        log = { DiagnosticLog.log(appContext, "AcademyProgressRepository", it) },
        recovery = recovery,
        clock = System::currentTimeMillis,
    )

    // encodeDefaults: schemaVersion is a defaulted property and would otherwise never reach the file.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    private val logLine = log

    private val store = JsonFileStore(fileProvider, json, "Academy progress", log, recovery::report)

    val completedLessonKeys = mutableStateOf<Set<String>>(emptySet())

    // Callers launch markComplete/markIncomplete from a screen-scoped rememberCoroutineScope, which
    // gets cancelled the moment the user navigates away (e.g. tapping Mark Complete right before
    // hitting Back). Without NonCancellable, that cancellation can land mid-write and the in-memory
    // completedLessonKeys update would silently never reach disk. This Mutex guards EVERYTHING below
    // (the loaded flag, [data], and the file itself), reads included.
    private val lock = Mutex()

    private var data = AcademyProgressData()
    private var loaded = false

    // False when the file on disk is damaged and could not be moved aside, or entries we could not
    // decode could not be copied aside: writing would destroy them, so changes stay in memory only.
    private var writable = true

    // The file currently on disk is still the v1 array; the next write keeps it as .bak first.
    private var legacyOnDisk = false

    private fun key(courseId: String, lessonId: String) = "$courseId/$lessonId"

    suspend fun load() {
        withContext(Dispatchers.IO) { lock.withLock { ensureLoaded() } }
    }

    /** Reads the file into [data] the first time; caller holds [lock]. */
    private fun ensureLoaded() {
        if (loaded) return
        when (val root = store.readRoot()) {
            JsonFileStore.RootRead.Missing -> data = AcademyProgressData()
            is JsonFileStore.RootRead.Unreadable -> {
                data = AcademyProgressData()
                writable = root.setAsideAs != null
            }
            is JsonFileStore.RootRead.Parsed -> readParsed(root.element)
        }
        loaded = true
        publish()
    }

    private fun readParsed(element: JsonElement) {
        when (element) {
            is JsonArray -> {
                // v1: a bare array of completed keys. Non-string elements can't be keys; they are
                // dropped, but the v1 bytes are kept as .bak by the migrating write.
                val keys = element.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
                data = AcademyProgressData(lessons = keys.associateWith { LessonProgress(completedAt = 0L) })
                legacyOnDisk = true
            }
            is JsonObject -> {
                val version = (element["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: SCHEMA_VERSION
                if (version > SCHEMA_VERSION) {
                    // Written by a newer build. Read what we understand, but this build rewriting it as
                    // v2 would drop whatever v3 added -- keep the original bytes.
                    logLine("Academy progress: file is schema v$version (newer than v$SCHEMA_VERSION); keeping a copy before it is rewritten")
                    DurableFile.preserveCopy(store.file)
                }
                var skipped = 0
                val lessons = (element["lessons"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                    runCatching { key to json.decodeFromJsonElement(LessonProgress.serializer(), value) }
                        .onFailure {
                            skipped++
                            logLine("Academy progress: skipping unreadable entry '$key' (${it.message})")
                        }
                        .getOrNull()
                }.toMap()
                val activity = (element["activity"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
                data = AcademyProgressData(lessons = lessons, activity = activity)
                if (!store.secureEvidenceForSkipped(skipped)) writable = false
            }
            else -> {
                // Valid JSON, but neither shape we have ever written.
                data = AcademyProgressData()
                writable = store.setAside("unusable (top level is neither an array nor an object)") != null
            }
        }
    }

    private fun publish() {
        completedLessonKeys.value = data.lessons.filterValues { it.completedAt != null }.keys
    }

    /** Applies [transform] to the one in-memory copy and writes it back, all under [lock]. */
    private suspend fun mutate(transform: (AcademyProgressData) -> AcademyProgressData) {
        withContext(NonCancellable + Dispatchers.IO) {
            lock.withLock {
                ensureLoaded()
                data = transform(data).copy(schemaVersion = SCHEMA_VERSION)
                publish()
                persistLocked()
            }
        }
    }

    private fun persistLocked() {
        if (!writable) {
            logLine("Academy progress: not written (existing file is damaged and could not be set aside); change kept in memory only")
            return
        }
        try {
            store.writeText(json.encodeToString(AcademyProgressData.serializer(), data), keepBackup = legacyOnDisk)
            legacyOnDisk = false
        } catch (e: IOException) {
            // Not fatal and not thrown at the screen that launched us: the in-memory state is right and
            // the next change rewrites the whole file.
            logLine("Academy progress: write failed (${e.message}); will retry with the next change")
        }
    }

    fun isComplete(courseId: String, lessonId: String): Boolean =
        key(courseId, lessonId) in completedLessonKeys.value

    /** Number of this course's lessons (out of [totalLessons]) the user has completed. */
    fun completedCount(courseId: String, lessonIds: List<String>): Int =
        lessonIds.count { key(courseId, it) in completedLessonKeys.value }

    suspend fun markComplete(courseId: String, lessonId: String) {
        val k = key(courseId, lessonId)
        mutate { d ->
            val current = d.lessons[k] ?: LessonProgress()
            // Marking an already-complete lesson again keeps its original completion time.
            d.copy(lessons = d.lessons + (k to current.copy(completedAt = current.completedAt ?: clock())))
        }
    }

    suspend fun markIncomplete(courseId: String, lessonId: String) {
        val k = key(courseId, lessonId)
        mutate { d ->
            val current = d.lessons[k] ?: return@mutate d
            val cleared = current.copy(completedAt = null)
            // An entry with nothing else on it is just absent, so a v1-shaped round trip stays tidy.
            d.copy(lessons = if (cleared == LessonProgress()) d.lessons - k else d.lessons + (k to cleared))
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}
