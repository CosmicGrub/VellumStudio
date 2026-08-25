package com.vellum.studio.util

import android.content.Context
import android.os.Build
import android.os.Process
import com.vellum.studio.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Durable, append-only, on-device diagnostic log. Exists because this project has repeatedly hit
 * connected devices vanishing from adb mid-session with zero record of what led up to it -- a
 * plain file in app-private storage ([Context.filesDir], survives process death, does NOT survive
 * [Context.getExternalFilesDir]'s data-extraction-rules exclusion since it's a different root, and
 * is readable purely on-device) is a strictly stronger record than whatever adb/logcat happened to
 * still be attached and scrolled-back far enough at the moment of the vanish.
 *
 * Entirely local: no network calls, no remote crash-reporting service, zero cost. Just a rolling
 * text file plus a chained [Thread.UncaughtExceptionHandler].
 *
 * Deliberately synchronous, plain [File] I/O rather than routed through a background
 * dispatcher/queue: every call site here already either runs on a background thread (IO/Default
 * dispatchers in ProjectRepository/PhotoConverter/PoseOverlay) or writes a single short line from
 * the main thread at a rare lifecycle moment (app start), and the one call that MUST be
 * synchronous -- the uncaught-exception handler -- has to finish writing before the process dies,
 * which rules out fire-and-forget async logging for that path anyway. One synchronous code path
 * used everywhere is simpler and more clearly correct than two.
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostic.log"

    /** Hard cap: a write that would push the file past this triggers a trim first. */
    private const val MAX_BYTES = 300 * 1024L

    /** What a trim brings the file back down to -- comfortably under [MAX_BYTES] so a burst of a
     * few more lines doesn't immediately re-trigger another trim on the very next call. */
    private const val TRIM_TO_BYTES = 200 * 1024

    private val lock = Any()
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // A dedicated subdirectory, not a bare file directly under filesDir -- FileProvider's
    // SimplePathStrategy resolves a declared <files-path> by matching a DIRECTORY prefix and then
    // substring()-ing it off the real path; point it at a path that IS the full file (as this
    // originally did) and, whenever that file's path length exactly equals the declared root's,
    // its "+1 for the separator" arithmetic reads one character past the end and throws
    // StringIndexOutOfBoundsException (confirmed on-device: length=49, index=50 -- see
    // FileProvider.java's SimplePathStrategy#getUriForFile). Every real FileProvider example
    // declares a directory for exactly this reason; see file_provider_paths.xml's matching entry.
    private const val DIR_NAME = "diagnostics"

    private fun logFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)

    /**
     * Installs this log as the process's [Thread.setDefaultUncaughtExceptionHandler], chaining to
     * whatever handler was already installed (Android's own default handler, always present in
     * practice, generates the tombstone/"has stopped" dialog and actually terminates the process)
     * rather than replacing it -- this only records the crash first; it never swallows it. Call
     * once, as early as possible in [com.vellum.studio.VellumApp.onCreate].
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                append(appContext, "CRASH", "Uncaught exception on thread '${thread.name}':\n$sw")
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // No prior handler is not expected on Android (the runtime always installs one
                // before Application.onCreate), but if it ever happened, silently returning here
                // would leave the thread just as dead with no crash dialog/tombstone at all -- so
                // fail the same way the platform default would rather than swallow it quietly.
                Process.killProcess(Process.myPid())
            }
        }
    }

    /** Appends one `[timestamp] [tag] message` line. Safe to call from any thread. */
    fun log(context: Context, tag: String, message: String) {
        append(context.applicationContext, tag, message)
    }

    private fun append(context: Context, tag: String, message: String) {
        synchronized(lock) {
            runCatching {
                val file = logFile(context)
                val line = "[${timestampFormat.format(Date())}] [$tag] $message\n"
                if (file.exists() && file.length() + line.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                    trim(file)
                }
                file.appendText(line, Charsets.UTF_8)
            }
        }
    }

    /** Drops the oldest lines, keeping only the most recent [TRIM_TO_BYTES] worth (rounded to a
     * line boundary so the file never starts mid-line). */
    private fun trim(file: File) {
        val bytes = file.readBytes()
        if (bytes.size <= TRIM_TO_BYTES) return
        var start = bytes.size - TRIM_TO_BYTES
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        start = (start + 1).coerceAtMost(bytes.size)
        file.writeBytes(bytes.copyOfRange(start, bytes.size))
    }

    /** Current on-disk size of the log file, or 0 if nothing has been logged yet. */
    fun sizeBytes(context: Context): Long {
        val file = logFile(context)
        return if (file.exists()) file.length() else 0L
    }

    /** The most recent [n] lines, oldest first -- for a quick Settings-screen preview. */
    fun lastLines(context: Context, n: Int = 12): List<String> {
        val file = logFile(context)
        if (!file.exists()) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList()).takeLast(n)
    }

    /** The log file itself, for the Settings-screen share-sheet export. */
    fun file(context: Context): File = logFile(context)

    /** Wipes the log's contents (Settings > Diagnostics "Clear" button). */
    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { logFile(context).writeText("", Charsets.UTF_8) }
        }
    }

    /** One-line device/app identity banner, meant to lead off "app start" so an exported log is
     * self-describing without needing separate device info alongside it. */
    fun deviceBanner(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "Vellum Studio ${BuildConfig.VERSION_NAME}"
}
