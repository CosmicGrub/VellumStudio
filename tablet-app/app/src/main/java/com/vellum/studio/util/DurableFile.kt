package com.vellum.studio.util

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe file replacement: the one primitive every "the user's data lives in this file" write
 * in the app should go through. A plain `FileOutputStream(target)` truncates the file to zero the
 * instant it is opened, so a process kill, battery death or full disk anywhere during the write
 * leaves a truncated file where the previous good copy used to be -- and the loaders then read
 * "unparseable" as "empty" and the NEXT save overwrites what little survived. This never touches
 * [target] until the replacement is completely on disk:
 *
 * 1. write `<name>.tmp` in the SAME directory (rename is only atomic within one filesystem, and a
 *    sibling file is the only location guaranteed to be on the same one),
 * 2. flush and `FileDescriptor.sync()` it so the bytes are on flash, not in the page cache -- without
 *    this the rename can be persisted before the data on a power loss, which is the classic
 *    zero-length-file-after-crash failure that rename alone does not prevent,
 * 3. optionally rotate the current good [target] to `<name>.bak`,
 * 4. atomically rename the tmp over [target].
 *
 * At every instant [target] is either the complete old file or the complete new file. A failure at
 * any step (disk full, IOException from [write]'s writer, encoder returning false) deletes the tmp
 * and rethrows: the caller sees a failed write, never a half-written one.
 *
 * The parent directory is deliberately not fsynced: Android's public API has no way to open a
 * directory for sync (only the hidden Os.open path), and losing the very last rename to a power cut
 * just means the previous complete version survives -- which is exactly the guarantee we want.
 */
object DurableFile {

    private const val BUFFER_BYTES = 64 * 1024

    fun tmpFor(target: File): File = File(target.parentFile, target.name + ".tmp")

    fun bakFor(target: File): File = File(target.parentFile, target.name + ".bak")

    /**
     * Replaces [target] with whatever [writer] produces. When [keepBackup] is set and [target]
     * currently exists AND passes [backupIsValid], that current version is first copied to
     * `<name>.bak` -- the validity gate is what stops a truncated/garbage current file from
     * clobbering the last good backup on the very save that follows the corruption.
     *
     * @throws IOException (or whatever [writer] throws) if the write could not be completed; in that
     *   case [target] is untouched.
     */
    @Throws(IOException::class)
    fun write(
        target: File,
        keepBackup: Boolean = false,
        backupIsValid: (File) -> Boolean = { it.length() > 0L },
        writer: (OutputStream) -> Unit,
    ) {
        val tmp = tmpFor(target)
        try {
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos, BUFFER_BYTES)
                writer(out)
                out.flush()
                fos.fd.sync()
            }
            if (keepBackup && target.exists() && runCatching { backupIsValid(target) }.getOrDefault(false)) {
                rotateBackup(target)
            }
            atomicReplace(tmp, target)
        } catch (t: Throwable) {
            // Best effort: free the space the half-written tmp is holding (matters most when the
            // failure WAS a full disk) without masking the real error.
            runCatching { tmp.delete() }
            throw t
        }
    }

    @Throws(IOException::class)
    fun writeText(target: File, text: String, keepBackup: Boolean = false, backupIsValid: (File) -> Boolean = { it.length() > 0L }) {
        write(target, keepBackup, backupIsValid) { out -> out.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /**
     * Best-effort: a backup that can't be refreshed (e.g. no room left) must not fail the save it
     * only exists to protect -- the new file is already fully written at this point.
     */
    private fun rotateBackup(target: File) {
        val bak = bakFor(target)
        val bakTmp = File(target.parentFile, bak.name + ".tmp")
        try {
            target.copyTo(bakTmp, overwrite = true)
            atomicReplace(bakTmp, bak)
        } catch (_: Exception) {
            runCatching { bakTmp.delete() }
        }
    }

    @Throws(IOException::class)
    private fun atomicReplace(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // Same directory means same filesystem, so this should not happen; if some exotic
            // storage refuses anyway, a non-atomic replace still beats failing every save forever.
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Sets a file that can no longer be trusted aside as `<name>.corrupt` (or `<name>.corrupt-<ms>`
     * when one is already there) instead of deleting it or letting a later save overwrite it, so
     * whatever bytes survive stay available for manual recovery. Returns where it went, or null if
     * it could not be moved AND could not be copied.
     */
    fun quarantine(file: File): File? {
        val dest = corruptNameFor(file)
        if (file.renameTo(dest)) return dest
        return runCatching { file.copyTo(dest, overwrite = false); file.delete(); dest }.getOrNull()
    }

    /**
     * Like [quarantine] but leaves [file] in place: for a file that is still mostly usable (a list
     * with a few undecodable entries) where the caller keeps serving the good part but must not let
     * the next save destroy the only copy of the bad part. Returns the copy, or null if it failed.
     */
    fun preserveCopy(file: File): File? {
        val dest = corruptNameFor(file)
        return runCatching { file.copyTo(dest, overwrite = false); dest }.getOrNull()
    }

    /** `<name>.corrupt`, else `<name>.corrupt-<ms>` (plus `-<n>` if even that is taken), never an existing file. */
    private fun corruptNameFor(file: File): File {
        val plain = File(file.parentFile, file.name + ".corrupt")
        if (!plain.exists()) return plain
        val stamp = System.currentTimeMillis()
        var candidate = File(file.parentFile, file.name + ".corrupt-" + stamp)
        var n = 1
        while (candidate.exists()) candidate = File(file.parentFile, file.name + ".corrupt-" + stamp + "-" + n++)
        return candidate
    }

    /** Deletes stray `*.tmp` files (an interrupted write's leftovers) directly inside [dir]. */
    fun sweepTmp(dir: File) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }?.forEach { runCatching { it.delete() } }
    }
}
