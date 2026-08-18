package com.vellum.studio.ui.coloringbook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vellum.studio.art.ColoringTemplate
import com.vellum.studio.art.ColoringTemplates
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.util.AssetBitmapCache
import com.vellum.studio.util.Printing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColoringBookScreen(
    repository: ProjectRepository,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }
    var viewingReference by remember { mutableStateOf<ColoringTemplate?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val templates = remember(selectedCategory) {
        val cat = selectedCategory
        if (cat == null) ColoringTemplates.all else ColoringTemplates.all.filter { it.category == cat }
    }

    fun startProject(template: ColoringTemplate) {
        if (creating) return
        creating = true
        scope.launch {
            val (meta, engine) = repository.createFromTemplate(template)
            engine.layers.forEach { it.bitmap.recycle() }
            creating = false
            onOpenProject(meta.id)
        }
    }

    fun printTemplate(template: ColoringTemplate) {
        // Mirrors the `creating` guard on startProject: without it, a fast double-tap on the print
        // icon (easy to do by accident on a touch tablet) launches multiple concurrent coroutines,
        // each allocating its own fresh 2048x2048 bitmap and firing its own print-dialog launch.
        if (printing) return
        printing = true
        scope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                val size = 2048
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(AndroidColor.WHITE)
                template.draw(canvas, size)
                bmp
            }
            Printing.printBitmap(context, template.name, bitmap)
            printing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coloring Book") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Original line-art pages — pick one to start coloring. The outline sits on a locked layer so bucket fill and brushes stay bounded by it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    lazyRowItems(listOf<String?>(null) + ColoringTemplates.categories) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(category ?: "All") },
                        )
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(templates, key = { it.id }) { template ->
                        TemplateCard(
                            template = template,
                            onClick = { startProject(template) },
                            onPrint = { printTemplate(template) },
                            onViewReference = { viewingReference = template },
                        )
                    }
                }
            }
            if (creating || printing) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    viewingReference?.let { template ->
        ReferenceViewerDialog(template = template, onDismiss = { viewingReference = null })
    }
}

@Composable
private fun TemplateCard(template: ColoringTemplate, onClick: () -> Unit, onPrint: () -> Unit, onViewReference: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            TemplatePreview(template, Modifier.fillMaxWidth().aspectRatio(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(template.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(template.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Only real-masterwork templates carry a fully-realized color reference to show
                // alongside the blank line art -- procedural templates have no such "finished
                // piece" to display, so this button only appears when there's something to view.
                if (template.referenceAssetPath != null) {
                    IconButton(onClick = onViewReference) {
                        Icon(Icons.Filled.Visibility, contentDescription = "View the fully-realized original")
                    }
                }
                IconButton(onClick = onPrint) {
                    Icon(Icons.Filled.Print, contentDescription = "Print blank page")
                }
            }
        }
    }
}

@Composable
private fun ReferenceViewerDialog(template: ColoringTemplate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val assetPath = template.referenceAssetPath ?: return
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, assetPath) {
        value = withContext(Dispatchers.IO) {
            runCatching { AssetBitmapCache.get(context, assetPath).asImageBitmap() }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template.name) },
        text = {
            Column {
                Text(
                    "The fully-realized original. Your line-art page is derived from this real painting — color it however you like.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                    bitmapState.value?.let {
                        Image(
                            bitmap = it,
                            contentDescription = template.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height.toFloat()),
                        )
                    } ?: Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun TemplatePreview(template: ColoringTemplate, modifier: Modifier = Modifier) {
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, template.id) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val size = 320
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(AndroidColor.WHITE)
                template.draw(canvas, size)
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    Box(modifier.background(androidx.compose.ui.graphics.Color.White)) {
        val bmp = bitmapState.value
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = template.name, modifier = Modifier.fillMaxSize())
        }
    }
}
