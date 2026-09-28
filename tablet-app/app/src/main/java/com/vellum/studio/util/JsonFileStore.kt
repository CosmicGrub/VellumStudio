package com.vellum.studio.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException

/**
 * The one way a user-data JSON file (custom brushes, palettes, the My Photos index, Academy
 * progress) is read and written. It exists to stop a pattern that silently destroyed data: every
 * store used to do `runCatching { decode }.getOrElse { empty }` and then load-all + mutate +
 * save-all, so ONE unreadable byte (a torn write, a hand edit, an enum constant a newer build
 * added and this one doesn't know) made the store look empty, and the very next mutation wrote
 * "just the new item" over the only copy of everything else.
 *
 * Three rules, each mapped to a failure it prevents:
 * - A MISSING file is a normal first run: empty, and safe to create. An UNREADABLE file is not the
 *   same thing and never becomes "empty and overwritable": it is moved aside to
 *   `<name>.corrupt[-<ts>]` ([DurableFile.quarantine]) so the bytes survive, and a notice is
 *   reported. If it cannot even be moved, [ListRead.safeToWrite] is false and [update] refuses to
 *   write over it.
 * - Lists decode ELEMENT BY ELEMENT: one bad entry is skipped (logged, and the file's original
 *   bytes copied aside via [DurableFile.preserveCopy] before any later save can drop that entry)
 *   while every good one still loads. Combined with `coerceInputValues` on the caller's [Json] a
 *   wrong-typed field falls back to its default instead of costing the entry at all.
 * - Writes go through [DurableFile] (tmp + fsync + atomic rename), so a kill mid-write can no longer
 *   create the unreadable file in the first place.
 *
 * Deliberately NOT thread-safe: each repository already serializes its read-modify-write with its
 * own Mutex and must take it for reads too (a read that quarantines a file must not race a writer
 * that just replaced it). The [json] instance stays lenient (ignoreUnknownKeys = true) -- on-disk
 * user data tolerates fields from newer builds, unlike the strict authored-content formats.
 */
class JsonFileStore(
    private val fileProvider: () -> File,
    private val json: Json,
    /** Human name of the data ("custom brushes"), used in log lines and notices. */
    private val label: String,
    private val log: (String) -> Unit = {},
    private val onRecovery: (RecoveryNotice) -> Unit = {},
) {
    val file: File get() = fileProvider()

    /** What was on disk, before any interpretation of its contents. */
    sealed interface RootRead {
        /** No file: a first run, empty and safe to create. */
        data object Missing : RootRead

        class Parsed(val element: JsonElement) : RootRead

        /** Present but not valid JSON (or not readable at all). [setAsideAs] is where it went, null if it could not be moved. */
        class Unreadable(val setAsideAs: File?) : RootRead
    }

    class ListRead<T>(
        val items: List<T>,
        val state: State,
        /** Entries of a readable file that failed to decode and are NOT in [items]. */
        val skipped: Int = 0,
        val setAsideAs: File? = null,
        /** False when [skipped] entries would be dropped by the next write and their bytes were not copied aside. */
        private val evidenceSecured: Boolean = true,
    ) {
        enum class State { MISSING, OK, UNREADABLE_SET_ASIDE, UNREADABLE_IN_PLACE }

        val unreadable: Boolean get() = state == State.UNREADABLE_SET_ASIDE || state == State.UNREADABLE_IN_PLACE

        /**
         * False when writing would destroy something unrecoverable: the file is damaged and still
         * sitting in the way, or entries we could not decode would be dropped with no copy kept.
         */
        val safeToWrite: Boolean get() = state != State.UNREADABLE_IN_PLACE && evidenceSecured
    }

    // Entries were skipped and the file copied aside once already this process; every later read of
    // the same still-damaged file would otherwise copy it again. Cleared by a successful write, which
    // drops the bad entries from the live file and so makes the next damage a new event.
    private var skippedEvidenceKept = false

    fun readRoot(): RootRead {
        val f = file
        if (!f.exists()) return RootRead.Missing
        val parsed = runCatching { json.parseToJsonElement(f.readText(Charsets.UTF_8)) }
        parsed.getOrNull()?.let { return RootRead.Parsed(it) }
        return RootRead.Unreadable(setAside("unreadable (${parsed.exceptionOrNull()?.message})"))
    }

    /** Reads a top-level JSON array of [T], decoding each element on its own. */
    fun <T> readList(serializer: KSerializer<T>): ListRead<T> {
        return when (val root = readRoot()) {
            RootRead.Missing -> ListRead(emptyList(), ListRead.State.MISSING)
            is RootRead.Unreadable -> unreadableResult(root.setAsideAs)
            is RootRead.Parsed -> {
                // Valid JSON but the wrong shape for a list store is as unusable as garbage bytes.
                val array = root.element as? JsonArray
                    ?: return unreadableResult(setAside("unusable (top level is not an array)"))
                var skipped = 0
                val items = array.mapIndexedNotNull { idx, element ->
                    runCatching { json.decodeFromJsonElement(serializer, element) }
                        .onFailure {
                            skipped++
                            log("$label: skipping unreadable entry #$idx (${it.message})")
                        }
                        .getOrNull()
                }
                ListRead(items, ListRead.State.OK, skipped, evidenceSecured = secureEvidenceForSkipped(skipped))
            }
        }
    }

    /**
     * For a readable file whose [skipped] entries the next write would silently drop: copies the
     * file's bytes aside (once per process while the damage persists) and reports it. Returns false
     * only when there were skipped entries and no copy could be made, i.e. writing now would destroy
     * them for good. Public because a store with a non-list shape (Academy progress) decodes its own
     * entries but needs the same guarantee.
     */
    fun secureEvidenceForSkipped(skipped: Int): Boolean {
        if (skipped == 0) return true
        if (!skippedEvidenceKept) {
            val keptAt = DurableFile.preserveCopy(file)
            skippedEvidenceKept = keptAt != null
            onRecovery(RecoveryNotice(label, RecoveryNotice.Kind.ENTRIES_SKIPPED, keptAt?.name, skipped))
        }
        return skippedEvidenceKept
    }

    private fun <T> unreadableResult(setAsideAs: File?): ListRead<T> = ListRead(
        emptyList(),
        if (setAsideAs != null) ListRead.State.UNREADABLE_SET_ASIDE else ListRead.State.UNREADABLE_IN_PLACE,
        setAsideAs = setAsideAs,
    )

    /**
     * Moves the current file aside (never deletes it), logs and reports it; null if it could not be
     * moved. Public for the caller that finds valid JSON of a shape it cannot use.
     */
    fun setAside(why: String): File? {
        val f = file
        val dest = DurableFile.quarantine(f)
        log("$label: ${f.name} $why; " + if (dest != null) "set aside as ${dest.name}" else "COULD NOT be set aside, leaving it untouched")
        onRecovery(
            RecoveryNotice(
                label = label,
                kind = if (dest != null) RecoveryNotice.Kind.SET_ASIDE else RecoveryNotice.Kind.LEFT_IN_PLACE,
                keptAt = dest?.name,
            ),
        )
        return dest
    }

    /** Atomically replaces the file with [items]. Throws [IOException] with the previous file intact. */
    @Throws(IOException::class)
    fun <T> writeList(serializer: KSerializer<T>, items: List<T>) {
        writeText(json.encodeToString(ListSerializer(serializer), items))
    }

    /** [keepBackup] rotates the file being replaced to `<name>.bak` first (e.g. the last write in an old format). */
    @Throws(IOException::class)
    fun writeText(text: String, keepBackup: Boolean = false) {
        DurableFile.writeText(file, text, keepBackup)
        skippedEvidenceKept = false
    }

    /**
     * Read-modify-write of a list store. When the file is unreadable AND could not be set aside the
     * current list is returned untouched and nothing is written -- the caller sees "no
     * change" rather than a crash, and the damaged file is still exactly as it was.
     */
    @Throws(IOException::class)
    fun <T> update(serializer: KSerializer<T>, transform: (List<T>) -> List<T>): List<T> {
        val read = readList(serializer)
        if (!read.safeToWrite) return read.items
        val updated = transform(read.items)
        writeList(serializer, updated)
        return updated
    }
}
