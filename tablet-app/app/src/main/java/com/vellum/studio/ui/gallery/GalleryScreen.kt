package com.vellum.studio.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vellum.studio.model.CanvasSizePreset
import com.vellum.studio.model.CanvasSizePresets
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.ProjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    repository: ProjectRepository,
    onOpenProject: (String) -> Unit,
    onOpenConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenColoringBook: () -> Unit,
    onOpenAcademy: () -> Unit,
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(revision) {
        loading = true
        projects = repository.listProjects()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vellum Studio", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenAcademy) {
                        Icon(Icons.Filled.School, contentDescription = "Academy")
                    }
                    IconButton(onClick = onOpenColoringBook) {
                        Icon(Icons.Filled.MenuBook, contentDescription = "Coloring Book")
                    }
                    IconButton(onClick = onOpenConnect) {
                        Icon(Icons.Filled.Wifi, contentDescription = "Connect to PC")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewCanvasDialog = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New Canvas") },
                // Real, pre-existing accessibility gap, found while wiring up Macrobenchmark's
                // UiAutomator-driven gesture tests (see benchmark/.../PanZoomFrameTimingBenchmark.kt):
                // confirmed via a live `uiautomator dump` against this exact screen that this FAB's
                // icon+text slots never merge into an accessible label at all (the node shows up as
                // NAF="true" -- "not accessibility-friendly" -- with empty text AND empty
                // content-desc, even though "New Canvas" is clearly visible on screen). That means
                // TalkBack users got nothing announced for this button before this fix, not just
                // that By.text("New Canvas") couldn't find it in a test.
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "New Canvas"
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                projects.isEmpty() -> EmptyState(Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onOpenProject(project.id) },
                            onDelete = {
                                scope.launch {
                                    repository.deleteProject(project.id)
                                    revision++
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showNewCanvasDialog) {
        NewCanvasDialog(
            onDismiss = { showNewCanvasDialog = false },
            onCreate = { name, preset ->
                scope.launch {
                    val (meta, engine) = repository.createProject(name, preset.widthPx, preset.heightPx)
                    engine.layers.forEach { it.bitmap.recycle() }
                    showNewCanvasDialog = false
                    onOpenProject(meta.id)
                }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.Brush, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
        Text("No canvases yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("Tap New Canvas to start your first piece.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProjectCard(project: ProjectSummary, onClick: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant)) {
                ThumbnailImage(project.thumbnailFile, Modifier.fillMaxSize())
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThumbnailImage(file: File?, modifier: Modifier = Modifier) {
    if (file == null) {
        Box(modifier)
        return
    }
    val bitmapState = produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val bitmap = bitmapState.value
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    } else {
        Box(modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewCanvasDialog(onDismiss: () -> Unit, onCreate: (String, CanvasSizePreset) -> Unit) {
    var name by remember { mutableStateOf("Untitled") }
    // Device-capability-gated, not the flat full list -- a lower-memory device simply never sees
    // presets that would likely OOM it, rather than offering them and failing later.
    val availablePresets = remember { CanvasSizePresets.availablePresets() }
    var selected by remember { mutableStateOf(availablePresets.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Canvas") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Canvas size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                availablePresets.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            FilterChip(
                                selected = selected == preset,
                                onClick = { selected = preset },
                                label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { "Untitled" }, selected) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
