package com.vellum.studio.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.Layer
import com.vellum.studio.canvas.LayerBlendMode
import com.vellum.studio.canvas.PoseOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun LayersPanel(engine: CanvasEngine, modifier: Modifier = Modifier, onMessage: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    // Which reference-image layer (if any) a pose-detection pass is currently running against --
    // keyed by layer id rather than a plain Boolean so multiple reference layers in one project
    // each get their own independent loading indicator instead of one flag ambiguously covering
    // whichever was tapped most recently.
    var poseDetectingLayerId by remember { mutableStateOf<String?>(null) }

    fun togglePoseGuide(layer: Layer) {
        val eng = engine
        when {
            eng.poseGuideEnabled && eng.poseGuideLayerId == layer.id -> eng.poseGuideEnabled = false
            eng.poseGuideLayerId == layer.id && eng.poseGuide != null && eng.poseGuideContentVersion == layer.contentVersion -> {
                // Already computed for this exact layer content -- just re-show it, no need to
                // re-run detection.
                eng.poseGuideEnabled = true
            }
            else -> {
                poseDetectingLayerId = layer.id
                scope.launch {
                    val guide = PoseOverlay.detectPose(layer.bitmap)
                    poseDetectingLayerId = null
                    if (guide != null) {
                        eng.poseGuide = guide
                        eng.poseGuideLayerId = layer.id
                        eng.poseGuideContentVersion = layer.contentVersion
                        eng.poseGuideEnabled = true
                    } else {
                        eng.poseGuideEnabled = false
                        onMessage("No clear pose detected in this image -- try a photo with a fully visible person")
                    }
                }
            }
        }
    }

    // Modern system photo picker (androidx.activity) -- no storage permission needed, and it's
    // the same picker the rest of Android uses, so it's a UI the user already recognizes.
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || importing) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap != null) {
                engine.addImageLayer("Reference", bitmap)
            }
            importing = false
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Layers", style = MaterialTheme.typography.titleMedium)
            Row {
                if (importing) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Import reference image") }
                }
                IconButton(onClick = { engine.addLayer() }) { Icon(Icons.Filled.Add, contentDescription = "Add layer") }
                IconButton(onClick = { engine.duplicateActiveLayer() }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate layer") }
                IconButton(onClick = { engine.deleteActiveLayer() }) { Icon(Icons.Filled.Delete, contentDescription = "Delete layer") }
            }
        }
        LazyColumn {
            val total = engine.layers.size
            itemsIndexed(engine.layers.asReversed(), key = { _, layer -> layer.id }) { reversedIndex, layer ->
                val actualIndex = total - 1 - reversedIndex
                LayerRow(
                    layer = layer,
                    isActive = actualIndex == engine.activeLayerIndex,
                    canMoveUp = actualIndex < total - 1,
                    canMoveDown = actualIndex > 0,
                    onSelect = { engine.activeLayerIndex = actualIndex },
                    onMoveUp = { engine.activeLayerIndex = actualIndex; engine.moveActiveLayer(1) },
                    onMoveDown = { engine.activeLayerIndex = actualIndex; engine.moveActiveLayer(-1) },
                    onToggleVisible = { engine.setLayerVisible(layer, !layer.visible) },
                    onToggleLocked = { engine.setLayerLocked(layer, !layer.locked) },
                    onOpacityChange = { engine.setLayerOpacity(layer, it) },
                    onBlendModeChange = { engine.setLayerBlendMode(layer, it) },
                    poseGuideShown = engine.poseGuideEnabled && engine.poseGuideLayerId == layer.id,
                    poseGuideLoading = poseDetectingLayerId == layer.id,
                    onTogglePoseGuide = { togglePoseGuide(layer) },
                )
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLocked: () -> Unit,
    onOpacityChange: (Float) -> Unit,
    onBlendModeChange: (LayerBlendMode) -> Unit,
    poseGuideShown: Boolean = false,
    poseGuideLoading: Boolean = false,
    onTogglePoseGuide: () -> Unit = {},
) {
    var blendMenuOpen by remember { mutableStateOf(false) }
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LayerThumbnail(layer)
            Column(Modifier.padding(start = 10.dp, end = 4.dp).weight(1f)) {
                Text(layer.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    Text(
                        layer.blendMode.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { blendMenuOpen = true },
                    )
                    DropdownMenu(expanded = blendMenuOpen, onDismissRequest = { blendMenuOpen = false }) {
                        LayerBlendMode.entries.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.label) }, onClick = { onBlendModeChange(mode); blendMenuOpen = false })
                        }
                    }
                }
            }
            // Fixed-size (smaller than IconButton's 48dp default touch target) so four of these
            // plus the thumbnail and name column can all fit in a 340dp panel without squeezing the
            // name/blend-mode text down to a couple of characters per line - see LayersPanel width
            // in EditorScreen.
            val actionIconSize = Modifier.size(36.dp)
            IconButton(onClick = onToggleLocked, modifier = actionIconSize) {
                Icon(
                    if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (layer.locked) "Unlock layer" else "Lock layer",
                    tint = if (layer.locked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = onToggleVisible, modifier = actionIconSize) {
                Icon(if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = "Toggle visibility")
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = actionIconSize) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up") }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = actionIconSize) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down") }
            // Pose Reference Overlay entry point (see PoseOverlay) -- only ever shown for a layer
            // actually created via "Import reference image" (Layer.isReferenceImage), so an
            // ordinary drawing layer never grows a 5th action icon. Degrades to simply not being
            // there at all when no reference image is present, per the task's "unavailable, not a
            // crash" requirement -- there's no separate disabled/greyed state to reason about.
            if (layer.isReferenceImage) {
                IconButton(onClick = onTogglePoseGuide, modifier = actionIconSize, enabled = !poseGuideLoading) {
                    if (poseGuideLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Filled.Accessibility,
                            contentDescription = if (poseGuideShown) "Hide pose guide" else "Show pose guide",
                            tint = if (poseGuideShown) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Opacity", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
            Slider(
                value = layer.opacity,
                onValueChange = onOpacityChange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LayerThumbnail(layer: Layer) {
    val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, layer.id, layer.contentVersion) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val scale = 48f / maxOf(layer.bitmap.width, layer.bitmap.height)
                Bitmap.createScaledBitmap(
                    layer.bitmap,
                    (layer.bitmap.width * scale).toInt().coerceAtLeast(1),
                    (layer.bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
    ) {
        bitmapState.value?.let { bmp ->
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(44.dp))
        }
    }
}
