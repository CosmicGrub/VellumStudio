package com.vellum.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
                VellumNavGraph(
                    repository = app.repository,
                    paletteRepository = app.paletteRepository,
                    academyProgressRepository = app.academyProgressRepository,
                    settingsRepository = app.settingsRepository,
                    customBrushRepository = app.customBrushRepository,
                )
            }
        }
    }
}
