package com.vellum.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vellum.studio.canvas.Brush
import java.util.UUID

/**
 * Tweak an existing brush's tunable parameters and save the result as a new custom preset.
 * Deliberately starts from a real, already-tuned brush (whichever was selected when the editor
 * was opened) rather than a blank slate — every parameter here already has a sensible starting
 * value, so a user is nudging a known-good brush toward what they want, not guessing at what
 * "hardness 0.5, spacing 0.1" even feels like from nothing.
 */
@Composable
fun BrushEditorDialog(
    baseBrush: Brush,
    onDismiss: () -> Unit,
    onSave: (Brush) -> Unit,
) {
    var name by remember { mutableStateOf("${baseBrush.name} Custom") }
    var baseSizePx by remember { mutableFloatStateOf(baseBrush.baseSizePx) }
    var hardness by remember { mutableFloatStateOf(baseBrush.hardness) }
    var spacing by remember { mutableFloatStateOf(baseBrush.spacing) }
    var baseOpacity by remember { mutableFloatStateOf(baseBrush.baseOpacity) }
    var pressureToSize by remember { mutableFloatStateOf(baseBrush.pressureToSize) }
    var pressureToOpacity by remember { mutableFloatStateOf(baseBrush.pressureToOpacity) }
    var tiltToSize by remember { mutableFloatStateOf(baseBrush.tiltToSize) }
    var jitter by remember { mutableFloatStateOf(baseBrush.jitter) }
    var opacityJitter by remember { mutableFloatStateOf(baseBrush.opacityJitter) }
    var wetness by remember { mutableFloatStateOf(baseBrush.wetness) }
    var buildUp by remember { mutableStateOf(baseBrush.buildUp) }
    var pigmentMixing by remember { mutableStateOf(baseBrush.pigmentMixing) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Brush") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Starting from ${baseBrush.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                ParamSlider("Size", baseSizePx, 2f, 120f, { baseSizePx = it })
                ParamSlider("Hardness (soft → crisp)", hardness, 0f, 1f, { hardness = it })
                ParamSlider("Spacing", spacing, 0.03f, 0.4f, { spacing = it })
                ParamSlider("Base opacity", baseOpacity, 0.05f, 1f, { baseOpacity = it })
                ParamSlider("Pressure → size", pressureToSize, 0f, 1f, { pressureToSize = it })
                ParamSlider("Pressure → opacity", pressureToOpacity, 0f, 1f, { pressureToOpacity = it })
                ParamSlider("Tilt → size (shading)", tiltToSize, 0f, 1f, { tiltToSize = it })
                ParamSlider("Jitter (position)", jitter, 0f, 1f, { jitter = it })
                ParamSlider("Jitter (opacity)", opacityJitter, 0f, 1f, { opacityJitter = it })
                ParamSlider("Wetness (edge diffusion)", wetness, 0f, 1f, { wetness = it })
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = buildUp, onCheckedChange = { buildUp = it })
                    Text(
                        "Build up (repeated passes keep darkening, like marker/airbrush)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pigmentMixing, onCheckedChange = { pigmentMixing = it })
                    Text(
                        "Pigment mixing (overlapping colors blend, like watercolor/pastel)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    baseBrush.copy(
                        id = "custom_${UUID.randomUUID()}",
                        name = name.ifBlank { "${baseBrush.name} Custom" },
                        baseSizePx = baseSizePx,
                        hardness = hardness,
                        spacing = spacing,
                        baseOpacity = baseOpacity,
                        pressureToSize = pressureToSize,
                        pressureToOpacity = pressureToOpacity,
                        tiltToSize = tiltToSize,
                        jitter = jitter,
                        opacityJitter = opacityJitter,
                        wetness = wetness,
                        buildUp = buildUp,
                        pigmentMixing = pigmentMixing,
                    ),
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ParamSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(String.format("%.2f", value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}
