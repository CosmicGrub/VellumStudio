package com.vellum.studio.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A user-data file ([JsonFileStore]) that could not be read cleanly, and what was done about it.
 * [keptAt] is the file name (not a path) the original bytes were set aside under, or null when
 * they could not be moved and were left where they were.
 */
class RecoveryNotice(val label: String, val kind: Kind, val keptAt: String?, val skipped: Int = 0) {
    enum class Kind {
        /** The whole file was unreadable; it was moved aside and the store started empty. */
        SET_ASIDE,

        /** The whole file was unreadable and could not be moved: it is left untouched and NOT written to. */
        LEFT_IN_PLACE,

        /** The file was readable but some entries were not; the good ones load, the original bytes were copied aside. */
        ENTRIES_SKIPPED,
    }

    /** Short, user-facing text for a Snackbar. */
    val message: String
        get() = when (kind) {
            Kind.SET_ASIDE ->
                "Your $label file was damaged and couldn't be read. The original was kept as ${keptAt ?: "a backup"} so nothing is lost for good."
            Kind.LEFT_IN_PLACE ->
                "Your $label file is damaged and couldn't be read. It was left untouched, so changes to $label won't be saved until that's resolved."
            Kind.ENTRIES_SKIPPED ->
                "$skipped saved item(s) in $label couldn't be read and were skipped. The original file was kept as ${keptAt ?: "a backup"}."
        }
}

/**
 * App-scoped "your data file was damaged" state, the same shape (and same reason) as
 * ProjectRepository.saveFailures: a STATE rather than an event flow, so a recovery that happens
 * while no host is collecting (a repository's first read runs in a LaunchedEffect mid-navigation)
 * is still shown by the next host, and stays until [acknowledge] says it actually was. One notice
 * per (label, kind) so a store that keeps re-reading the same damaged file cannot queue a snackbar
 * per read.
 */
class RecoveryNotices {
    private val _notices = MutableStateFlow<List<RecoveryNotice>>(emptyList())

    val notices: StateFlow<List<RecoveryNotice>> = _notices.asStateFlow()

    fun report(notice: RecoveryNotice) {
        _notices.update { list -> list.filterNot { it.label == notice.label && it.kind == notice.kind } + notice }
    }

    fun acknowledge(notice: RecoveryNotice) {
        _notices.update { list -> list - notice }
    }
}
