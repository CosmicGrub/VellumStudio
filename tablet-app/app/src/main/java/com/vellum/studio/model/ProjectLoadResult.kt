package com.vellum.studio.model

/**
 * Why a project that exists on disk could not be opened. Each maps to a different thing the user
 * can do about it, which is why they are not one "failed" flag: [OUT_OF_MEMORY] is about this
 * moment on this device (close other apps, try again), while [INVALID_CANVAS_SIZE] is about the
 * files and will never open no matter how often it is tapped.
 */
enum class UnreadableReason {
    /** metadata.json (and its `.bak`) declares a zero/negative/absurd canvas size and no layer PNG survives to infer a real one from. */
    INVALID_CANVAS_SIZE,

    /** Allocating the canvas or decoding a layer PNG ran out of heap. Nothing on disk is wrong. */
    OUT_OF_MEMORY,

    /** A file could not be read (storage error, permissions). */
    IO_ERROR,

    /** Anything else the load threw; the cause is in the diagnostic log. */
    UNEXPECTED,
}

/**
 * What [ProjectRepository.loadProject] found. A sealed result instead of `LoadedProject?` plus
 * exceptions because the caller used to have to guess: a null was rendered as an eternal spinner
 * (the editor could not tell "no such project" from "still loading"), and a throw -- a 0x0 canvas
 * from garbled metadata, an OOM decoding a big layer -- crashed the app on every tap of that card.
 * Every non-[Ok] case is a normal, expected outcome that the editor turns into an error card with a
 * way back, and none of them ever leaves an engine behind that an autosave could write over the
 * real project: no [Ok], no engine, no saver.
 */
sealed interface LoadResult {
    class Ok(val project: ProjectRepository.LoadedProject) : LoadResult

    /** A load that did not produce an engine; [title]/[message] are the user-facing card text. */
    sealed interface Failed : LoadResult {
        val title: String
        val message: String
    }

    /** No project by that id: no metadata, no `.bak`, no recoverable layer file. */
    data object NotFound : Failed {
        override val title get() = "Couldn't open this canvas"
        override val message get() = "This project no longer exists on this device."
    }

    /** Saved by a newer build; refused untouched (see [ProjectTooNewException]). */
    class TooNew(val exception: ProjectTooNewException) : Failed {
        override val title get() = "Made with a newer Vellum Studio"
        override val message get() = exception.userMessage
    }

    class Unreadable(val reason: UnreadableReason, val detail: String?) : Failed {
        override val title get() = "Couldn't open this canvas"
        override val message: String
            get() = when (reason) {
                UnreadableReason.INVALID_CANVAS_SIZE ->
                    "This project's canvas size is damaged and none of its layer images could be read to recover it. " +
                        "Nothing was changed on disk."
                UnreadableReason.OUT_OF_MEMORY ->
                    "There is not enough memory to open this project on this device right now. " +
                        "Close other apps and try again. Nothing was changed on disk."
                UnreadableReason.IO_ERROR ->
                    "This project's files couldn't be read (storage error). Nothing was changed on disk."
                UnreadableReason.UNEXPECTED ->
                    "Something went wrong while opening this project. Nothing was changed on disk."
            }
    }
}
