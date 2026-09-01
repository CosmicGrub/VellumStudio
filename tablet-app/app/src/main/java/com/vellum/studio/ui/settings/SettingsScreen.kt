package com.vellum.studio.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.vellum.studio.BuildConfig
import com.vellum.studio.canvas.PressureCurvePreset
import com.vellum.studio.canvas.PressureCurveRange
import com.vellum.studio.model.SettingsRepository
import com.vellum.studio.util.DiagnosticLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsRepository: SettingsRepository, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                // Pre-existing gap: this Column had no scroll at all, so cards below "Pressure
                // curve" (including "Experimental", predating this task) were already clipped
                // off-screen on a portrait tablet layout -- not just the new "Diagnostics" card
                // added here. Fixing it is in scope: without it, Diagnostics would be unreachable
                // in the very UI this task asks for.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard(title = "About") {
                Text("Vellum Studio ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "A high-fidelity S Pen drawing app: pressure- and tilt-sensitive brushes, layers with full blend modes, " +
                        "bounded undo history, and a Wi-Fi bridge to a PC companion app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsCard(title = "Input") {
                Text(
                    "Drawing is stylus-only by design: the S Pen (or eraser end) always draws, one finger pans, and two fingers " +
                        "pinch-zoom and rotate. While the pen is down, touch input is ignored to prevent palm smudges.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Keyboard shortcuts",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "With a hardware or Bluetooth keyboard connected, the editor also responds to: " +
                        "Ctrl+Z undo, Ctrl+Shift+Z or Ctrl+Y redo, [ and ] to step brush size down/up, " +
                        "and 1-9 / 0 to set brush opacity to 10%-100%. You can also drag an image in " +
                        "from another app or a split-screen window and drop it on the canvas to add it " +
                        "as a reference layer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsCard(title = "Storage") {
                Text(
                    "Projects live in this app's private storage as layered PNGs + metadata, so they survive app updates. " +
                        "Use Export > Save PNG to Gallery to get a flattened copy into your Pictures.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsCard(title = "Canvas") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Paper texture", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A subtle procedural paper grain, multiplied over the canvas so strokes pick up a bit of " +
                                "tooth instead of looking perfectly flat. Applies live while drawing and to every " +
                                "export/thumbnail/print of this project. Off by default.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settingsRepository.paperTextureEnabled,
                        onCheckedChange = { settingsRepository.paperTextureEnabled = it },
                    )
                }
                if (settingsRepository.paperTextureEnabled) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Strength", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
                        Slider(
                            value = settingsRepository.paperTextureStrength,
                            onValueChange = { settingsRepository.paperTextureStrength = it },
                            valueRange = 0.05f..1f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            SettingsCard(title = "Pressure curve") {
                Text(
                    "Shapes how S Pen pressure translates into brush size and opacity. Soft reaches " +
                        "full effect with a light touch; Firm needs real force before it kicks in; " +
                        "Linear passes pressure through unchanged. Drag the gamma slider directly for " +
                        "anything in between — it always reflects exactly what's applied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(PressureCurvePreset.SOFT, PressureCurvePreset.LINEAR, PressureCurvePreset.FIRM).forEach { preset ->
                        FilterChip(
                            selected = settingsRepository.pressureCurvePreset == preset,
                            onClick = { settingsRepository.pressureCurvePreset = preset },
                            label = { Text(preset.label) },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Gamma", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = settingsRepository.pressureCurveGamma,
                        onValueChange = { gamma ->
                            settingsRepository.pressureCurveGamma = gamma
                            settingsRepository.pressureCurvePreset = PressureCurvePreset.CUSTOM
                        },
                        valueRange = PressureCurveRange.MIN_GAMMA..PressureCurveRange.MAX_GAMMA,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format("%.2f", settingsRepository.pressureCurveGamma),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            SettingsCard(title = "Experimental") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("GPU-accelerated compositing", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Renders the canvas view with OpenGL when you're not actively drawing (panning/zooming/" +
                                "just looking), instead of the normal software path. Off by default. Drawing itself is " +
                                "always unaffected either way — this only changes how the settled canvas is displayed, " +
                                "and only when every visible layer uses Normal, Multiply, or Screen blend mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settingsRepository.experimentalGpuCompositor,
                        onCheckedChange = { settingsRepository.experimentalGpuCompositor = it },
                    )
                }
            }
            SettingsCard(title = "Diagnostics") {
                val context = LocalContext.current
                // Bumped after Clear so the size/preview below re-read the file instead of showing
                // stale state from before the button press -- DiagnosticLog itself isn't
                // Compose-observable (it's a plain file, written from background threads all over
                // the app), so this screen has to explicitly ask it to refresh.
                var refreshTick by remember { mutableIntStateOf(0) }
                val sizeBytes = remember(refreshTick) { DiagnosticLog.sizeBytes(context) }
                val lastLines = remember(refreshTick) { DiagnosticLog.lastLines(context) }

                Text(
                    "An on-device log of key app events (start, project open/save, photo conversion, pose " +
                        "detection) and any crash — kept only on this device, never sent anywhere. Capped at a " +
                        "few hundred KB; the oldest entries drop off automatically once it fills up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Current size: ${formatLogSize(sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (lastLines.isEmpty()) {
                    Text(
                        "No entries yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        lastLines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val file = DiagnosticLog.file(context)
                            if (file.exists() && file.length() > 0) {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    putExtra(Intent.EXTRA_SUBJECT, "Vellum Studio diagnostic log")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Export diagnostic log"))
                            }
                        },
                        enabled = sizeBytes > 0,
                    ) {
                        Icon(Icons.Filled.IosShare, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Export")
                    }
                    OutlinedButton(
                        onClick = {
                            DiagnosticLog.clear(context)
                            refreshTick++
                        },
                        enabled = sizeBytes > 0,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Clear")
                    }
                }
            }
        }
    }
}

private fun formatLogSize(bytes: Long): String = if (bytes < 1024) {
    "$bytes B"
} else {
    String.format("%.1f KB", bytes / 1024.0)
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScopeContent) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private typealias ColumnScopeContent = androidx.compose.foundation.layout.ColumnScope.() -> Unit
