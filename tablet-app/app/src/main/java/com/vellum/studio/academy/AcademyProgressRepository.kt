package com.vellum.studio.academy

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tracks which lessons the user has marked complete, as one small JSON file (same "load-all,
 * mutate, save-all" shape as [com.vellum.studio.model.PaletteRepository] — this data is tiny and
 * changes rarely, so there's no need for anything heavier). Lesson identity is `"$courseId/$lessonId"`
 * since lesson ids are only unique within their own course.
 *
 * [completedLessonKeys] is an in-memory, observable mirror of what's on disk so course/lesson list
 * screens can show progress reactively without re-reading the file on every recomposition; it's
 * loaded once via [load] (call from a top-level LaunchedEffect) and kept in sync by [markComplete]/
 * [markIncomplete].
 */
class AcademyProgressRepository(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val file: File
        get() = File(appContext.getExternalFilesDir(null), "academy_progress.json")

    val completedLessonKeys = mutableStateOf<Set<String>>(emptySet())

    // Callers launch markComplete/markIncomplete from a screen-scoped rememberCoroutineScope, which
    // gets cancelled the moment the user navigates away (e.g. tapping Mark Complete right before
    // hitting Back). Without NonCancellable, that cancellation can land mid-write and the in-memory
    // completedLessonKeys update above would silently never reach disk. The Mutex additionally
    // serializes overlapping writes (e.g. a rapid double-tap) so they apply in order instead of
    // racing to open independent file handles where whichever finishes last wins arbitrarily.
    private val writeLock = Mutex()

    private fun key(courseId: String, lessonId: String) = "$courseId/$lessonId"

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = if (file.exists()) {
            runCatching { json.decodeFromString<Set<String>>(file.readText()) }.getOrElse { emptySet() }
        } else {
            emptySet()
        }
        completedLessonKeys.value = loaded
    }

    private suspend fun persist(keys: Set<String>) = withContext(NonCancellable + Dispatchers.IO) {
        writeLock.withLock {
            file.writeText(json.encodeToString(keys))
        }
    }

    fun isComplete(courseId: String, lessonId: String): Boolean =
        key(courseId, lessonId) in completedLessonKeys.value

    /** Number of this course's lessons (out of [totalLessons]) the user has completed. */
    fun completedCount(courseId: String, lessonIds: List<String>): Int =
        lessonIds.count { key(courseId, it) in completedLessonKeys.value }

    suspend fun markComplete(courseId: String, lessonId: String) {
        val updated = completedLessonKeys.value + key(courseId, lessonId)
        completedLessonKeys.value = updated
        persist(updated)
    }

    suspend fun markIncomplete(courseId: String, lessonId: String) {
        val updated = completedLessonKeys.value - key(courseId, lessonId)
        completedLessonKeys.value = updated
        persist(updated)
    }
}
