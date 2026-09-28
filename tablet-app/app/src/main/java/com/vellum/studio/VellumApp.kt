package com.vellum.studio

import android.app.Application
import android.os.StrictMode
import com.vellum.studio.academy.AcademyProgressRepository
import com.vellum.studio.model.CustomBrushRepository
import com.vellum.studio.model.PaletteRepository
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.SettingsRepository
import com.vellum.studio.model.UserPhotoTemplateRepository
import com.vellum.studio.util.DiagnosticLog
import com.vellum.studio.util.RecoveryNotices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VellumApp : Application() {
    // Kept as a named Lazy so onTrimMemory can ask "was it ever created" without creating it.
    private val repositoryDelegate = lazy { ProjectRepository(this) }
    val repository: ProjectRepository by repositoryDelegate
    // User-data files (brushes, palettes, My Photos index, Academy progress) that turned out damaged and
    // were set aside; shown by the same app-level host as save failures (see SaveFailureHost).
    val recoveryNotices = RecoveryNotices()
    val paletteRepository: PaletteRepository by lazy { PaletteRepository(this, recoveryNotices) }
    val academyProgressRepository: AcademyProgressRepository by lazy { AcademyProgressRepository(this, recoveryNotices) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val customBrushRepository: CustomBrushRepository by lazy { CustomBrushRepository(this, recoveryNotices) }
    val userPhotoTemplateRepository: UserPhotoTemplateRepository by lazy { UserPhotoTemplateRepository(this, recoveryNotices) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        installStrictModeIfDebug()
        // Installed before anything else so a crash during the rest of this method's own
        // initialization (repositories are lazy, but a bad first touch of one would still land
        // here) is captured too.
        DiagnosticLog.install(this)
        DiagnosticLog.log(this, "Lifecycle", "App started (${DiagnosticLog.deviceBanner()})")
        // Recently deleted keeps a project for ProjectRepository.TRASH_RETENTION_MS (30 days); this is
        // where older ones are finally removed. Off the main thread (it lists and deletes folders),
        // best-effort (an unpurged entry is simply retried next launch), and it creates nothing when
        // there is no trash. Process-lifetime scope: there is nothing to cancel it for.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { repository.purgeExpiredTrash() }
                .onFailure { DiagnosticLog.log(this@VellumApp, "Lifecycle", "Trash purge failed: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /**
     * DEBUG-build-only developer diagnostics: flags disk reads/writes on the main thread and leaked
     * Closeable/SQLite objects, both LOGGED (`penaltyLog()`), never `penaltyDeath()` -- this is meant
     * to surface a jank-causing main-thread file read in Logcat during development, not to crash a
     * release build (or a debug build mid-demo) over something that was already shipping fine. A
     * release build never even calls [StrictMode.setThreadPolicy]/[StrictMode.setVmPolicy], so it
     * costs nothing there; `BuildConfig.DEBUG` is the same gate [UndoManager]'s crop-based-undo
     * correctness net uses for the same "on for every real dev/test run, off in what ships" reason.
     */
    private fun installStrictModeIfDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }

    /**
     * Process-wide memory-pressure callback. UI_HIDDEN (and everything above it) means no UI of ours
     * is visible any more, i.e. the process just became a kill candidate: any editor with unsaved
     * changes flushes NOW rather than hoping the debounce beats lmkd. The editor's own ON_STOP flush
     * normally got there first, in which case this is free (dirty flag). Deliberately does not touch
     * [repository] if nothing ever created it -- an editor cannot be open without it.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (repositoryDelegate.isInitialized()) repository.onTrimMemory(level)
    }

    companion object {
        /**
         * Process-wide Application context, for the handful of call sites that have no Context of
         * their own to work with -- e.g. a ColoringTemplate's `draw: (Canvas, Int) -> Unit` closure,
         * whose signature is shared by every template (procedural and asset-backed alike) and
         * shouldn't grow a Context parameter just for the few real-masterwork ones that need to
         * read a bundled asset. Safe: this is always the Application context (never an Activity),
         * so it can't leak a destroyed screen.
         */
        lateinit var instance: VellumApp
            private set
    }
}
