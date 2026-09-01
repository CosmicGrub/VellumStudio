package com.vellum.studio.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush as BrushIcon
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.vellum.studio.canvas.Brush
import com.vellum.studio.canvas.BrushCategory
import com.vellum.studio.canvas.BrushPresets
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.model.CustomBrushRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrushBar(engine: CanvasEngine, customBrushRepository: CustomBrushRepository, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var customBrushes by remember { mutableStateOf<List<Brush>>(emptyList()) }
    var editorOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { customBrushes = customBrushRepository.loadCustomBrushes() }

    Column(modifier.background(MaterialTheme.colorScheme.surface).padding(vertical = 8.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(BrushPresets.all, key = { it.id }) { brush ->
                val selected = engine.currentBrush.id == brush.id
                BrushChip(brush = brush, selected = selected, onClick = { engine.currentBrush = brush })
            }
            items(customBrushes, key = { it.id }) { brush ->
                val selected = engine.currentBrush.id == brush.id
                BrushChip(
                    brush = brush,
                    selected = selected,
                    onClick = { engine.currentBrush = brush },
                    // Long-press to delete -- custom brushes are the only chips a user can remove,
                    // so this gesture is only wired up here, not on the built-in preset chips.
                    onLongClick = {
                        scope.launch {
                            customBrushes = customBrushRepository.deleteBrush(brush.id)
                            if (engine.currentBrush.id == brush.id) engine.currentBrush = BrushPresets.all.first()
                        }
                    },
                )
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.combinedClickable(onClick = { editorOpen = true })) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "New custom brush", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Custom", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Size", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
            Slider(
                value = engine.brushSizeMultiplier,
                onValueChange = { engine.brushSizeMultiplier = it },
                valueRange = CanvasEngine.MIN_BRUSH_SIZE_MULTIPLIER..CanvasEngine.MAX_BRUSH_SIZE_MULTIPLIER,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
            Slider(
                value = engine.brushOpacityMultiplier,
                onValueChange = { engine.brushOpacityMultiplier = it },
                valueRange = CanvasEngine.MIN_BRUSH_OPACITY_MULTIPLIER..CanvasEngine.MAX_BRUSH_OPACITY_MULTIPLIER,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (editorOpen) {
        BrushEditorDialog(
            baseBrush = engine.currentBrush,
            onDismiss = { editorOpen = false },
            onSave = { saved ->
                editorOpen = false
                scope.launch {
                    customBrushes = customBrushRepository.saveBrush(saved)
                    engine.currentBrush = saved
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrushChip(brush: Brush, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                iconFor(brush),
                contentDescription = brush.name,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(brush.name, style = MaterialTheme.typography.labelSmall)
    }
}

// Erasers are all BrushCategory.ERASER, so - unlike the drawing tools, which get one icon per
// category - they're distinguished by id instead, one icon per variant, so the eraser lineup reads
// as clearly as the drawing lineup does instead of collapsing to identical chips.
private fun iconFor(brush: Brush): ImageVector = when (brush.id) {
    "eraser_precision" -> Icons.Filled.GpsFixed
    "eraser_soft" -> Icons.Filled.BlurCircular
    "eraser_hard" -> Icons.Filled.CropSquare
    "eraser_kneaded" -> Icons.Filled.Texture
    "eraser_fade" -> Icons.Filled.Gradient
    // Graffiti suite: same "one distinct icon per id" treatment as the erasers above, since
    // they all share BrushCategory.GRAFFITI and would otherwise collapse to one identical chip.
    "graffiti_spray" -> Icons.Filled.BlurOn
    "graffiti_fatcap" -> Icons.Filled.FormatBold
    "graffiti_wildstyle" -> Icons.Filled.Gesture
    "graffiti_drip" -> Icons.Filled.WaterDrop
    "graffiti_stencil" -> Icons.Filled.CropSquare
    else -> iconFor(brush.category)
}

private fun iconFor(category: BrushCategory): ImageVector = when (category) {
    BrushCategory.PENCIL -> Icons.Filled.Edit
    BrushCategory.INK -> Icons.Filled.Create
    BrushCategory.FINELINER -> Icons.Filled.Draw
    BrushCategory.MARKER -> Icons.Filled.BrushIcon
    BrushCategory.HIGHLIGHTER -> Icons.Filled.Highlight
    BrushCategory.WATERCOLOR -> Icons.Filled.WaterDrop
    BrushCategory.PASTEL -> Icons.Filled.Grain
    BrushCategory.AIRBRUSH -> Icons.Filled.BlurOn
    BrushCategory.FLAT_FILL -> Icons.Filled.HorizontalRule
    BrushCategory.ERASER -> Icons.AutoMirrored.Filled.Backspace
    // Unreachable in practice (every graffiti brush is id-matched above) -- kept only so this
    // `when` stays exhaustive over BrushCategory without a wildcard branch hiding a real gap.
    BrushCategory.GRAFFITI -> Icons.Filled.BrushIcon
}
