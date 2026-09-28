package com.vellum.studio

import android.app.Application
import com.vellum.studio.academy.AcademyProgressRepository
import com.vellum.studio.model.CustomBrushRepository
import com.vellum.studio.model.PaletteRepository
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.SettingsRepository
import com.vellum.studio.model.UserPhotoTemplateRepository
import com.vellum.studio.util.DiagnosticLog

class VellumApp : Application() {
    // Kept as a named Lazy so onTrimMemory can ask "was it ever created" without creating it.
    private val repositoryDelegate = lazy { ProjectRepository(this) }
    val repository: ProjectRepository by repositoryDelegate
    val paletteRepository: PaletteRepository by lazy { PaletteRepository(this) }
    val academyProgressRepository: AcademyProgressRepository by lazy { AcademyProgressRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val customBrushRepository: CustomBrushRepository by lazy { CustomBrushRepository(this) }
    val userPhotoTemplateRepository: UserPhotoTemplateRepository by lazy { UserPhotoTemplateRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Installed before anything else so a crash during the rest of this method's own
        // initialization (repositories are lazy, but a bad first touch of one would still land
        // here) is captured too.
        DiagnosticLog.install(this)
        DiagnosticLog.log(this, "Lifecycle", "App started (${DiagnosticLog.deviceBanner()})")
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
