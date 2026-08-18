package com.vellum.studio

import android.app.Application
import com.vellum.studio.academy.AcademyProgressRepository
import com.vellum.studio.model.CustomBrushRepository
import com.vellum.studio.model.PaletteRepository
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.SettingsRepository

class VellumApp : Application() {
    val repository: ProjectRepository by lazy { ProjectRepository(this) }
    val paletteRepository: PaletteRepository by lazy { PaletteRepository(this) }
    val academyProgressRepository: AcademyProgressRepository by lazy { AcademyProgressRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val customBrushRepository: CustomBrushRepository by lazy { CustomBrushRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
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
