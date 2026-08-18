package com.vellum.studio.ui.colorpicker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vellum.studio.model.BuiltInPalettes
import com.vellum.studio.model.Palette
import com.vellum.studio.model.PaletteRepository
import com.vellum.studio.model.RecentColors
import com.vellum.studio.ui.editor.ColorPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The full color suite: the HSV picker, a hex field for precision entry, a recently-used row, and
 * a palette browser (curated built-ins plus user-created custom palettes, persisted).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerPanel(
    paletteRepository: PaletteRepository,
    initialColorArgb: Int,
    onColorChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentColor by remember { mutableStateOf(initialColorArgb) }
    var customPalettes by remember { mutableStateOf<List<Palette>>(emptyList()) }
    var selectedPaletteId by remember { mutableStateOf(BuiltInPalettes.Classic.id) }
    var showNewPaletteDialog by remember { mutableStateOf(false) }
    // (paletteId, colorArgb) of the most recently long-press-deleted swatch, so an accidental
    // long-press - Android's ~500ms threshold is easy to trigger with a slow/hesitant tap, exactly
    // the kind of thing a stylus user might do while deciding whether to select a swatch - is
    // recoverable instead of silently and permanently losing the color.
    var lastRemovedColor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { customPalettes = paletteRepository.loadCustomPalettes() }

    LaunchedEffect(lastRemovedColor) {
        if (lastRemovedColor != null) {
            delay(4000)
            lastRemovedColor = null
        }
    }

    fun emit(argb: Int) {
        currentColor = argb
        onColorChanged(argb)
    }

    val allPalettes = remember(customPalettes) { BuiltInPalettes.all + customPalettes }
    val selectedPalette = allPalettes.firstOrNull { it.id == selectedPaletteId } ?: BuiltInPalettes.Classic

    Column(modifier) {
        ColorPicker(initialColorArgb = currentColor, onColorChanged = { emit(it) })

        Spacer(Modifier.height(14.dp))
        HexField(currentColor, onColorParsed = { emit(it) })

        if (RecentColors.colors.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Recent", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RecentColors.colors) { argb ->
                    Swatch(argb = argb, selected = argb == currentColor, onClick = { emit(argb) })
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Palettes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            IconButton(onClick = {
                if (selectedPalette.isBuiltIn) {
                    showNewPaletteDialog = true
                } else {
                    scope.launch { customPalettes = paletteRepository.addColor(selectedPalette.id, currentColor) }
                }
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add current color to palette")
            }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allPalettes, key = { it.id }) { palette ->
                FilterChip(
                    selected = palette.id == selectedPaletteId,
                    onClick = { selectedPaletteId = palette.id },
                    label = { Text(palette.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (selectedPalette.colors.isEmpty()) {
            Text(
                if (selectedPalette.isBuiltIn) "No swatches." else "Empty — tap + above to add the current color.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selectedPalette.colors.forEach { argb ->
                    Swatch(
                        argb = argb,
                        selected = argb == currentColor,
                        onClick = { emit(argb) },
                        onLongClick = if (!selectedPalette.isBuiltIn) {
                            {
                                scope.launch {
                                    customPalettes = paletteRepository.removeColor(selectedPalette.id, argb)
                                    lastRemovedColor = selectedPalette.id to argb
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        lastRemovedColor?.let { (paletteId, argb) ->
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Color removed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch {
                        customPalettes = paletteRepository.addColor(paletteId, argb)
                        lastRemovedColor = null
                    }
                }) { Text("Undo") }
            }
        }

        if (!selectedPalette.isBuiltIn) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { scope.launch { customPalettes = paletteRepository.deletePalette(selectedPalette.id); selectedPaletteId = BuiltInPalettes.Classic.id } }) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete this palette")
            }
        }
    }

    if (showNewPaletteDialog) {
        NewPaletteDialog(
            onDismiss = { showNewPaletteDialog = false },
            onCreate = { name ->
                scope.launch {
                    val created = paletteRepository.createPalette(name, currentColor)
                    customPalettes = paletteRepository.loadCustomPalettes()
                    selectedPaletteId = created.id
                    showNewPaletteDialog = false
                }
            },
        )
    }
}

@Composable
private fun HexField(colorArgb: Int, onColorParsed: (Int) -> Unit) {
    var text by remember(colorArgb) { mutableStateOf(String.format("#%06X", colorArgb and 0xFFFFFF)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            val hex = input.removePrefix("#")
            if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                runCatching { (0xFF000000.toInt()) or hex.toInt(16) }.getOrNull()?.let(onColorParsed)
            }
        },
        label = { Text("Hex") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Swatch(argb: Int, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                CircleShape,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun NewPaletteDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("My Palette") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Palette") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
