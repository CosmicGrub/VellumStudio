package com.vellum.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.vellum.studio.ui.navigation.SaveFailureHost
import com.vellum.studio.ui.navigation.VellumNavGraph
import com.vellum.studio.ui.theme.VellumStudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as VellumApp

        setContent {
            VellumStudioTheme {
                Box(Modifier.fillMaxSize()) {
                    VellumNavGraph(
                        repository = app.repository,
                        paletteRepository = app.paletteRepository,
                        academyProgressRepository = app.academyProgressRepository,
                        settingsRepository = app.settingsRepository,
                        customBrushRepository = app.customBrushRepository,
                        userPhotoTemplateRepository = app.userPhotoTemplateRepository,
                    )
                    // Above the nav graph so a save failure outlives the editor that requested it
                    // (Back saves and leaves in one click) -- see SaveFailureHost.
                    SaveFailureHost(app.repository, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
