package com.vellum.studio.ui.gallery

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.vellum.studio.util.isCompactWidth
import com.vellum.studio.model.ProjectTooNewException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

// Default size for the cover-screen "Quick Sketch" quick-capture action -- deliberately small
// (a cover-screen canvas is never going to be viewed at Studio-tier resolution) and square (no
// portrait/landscape choice to make, since making that choice is exactly what quick capture skips).
private const val QUICK_SKETCH_CANVAS_PX = 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    repository: ProjectRepository,
    onOpenProject: (String) -> Unit,
    onOpenQuickSketch: (String) -> Unit,
    onOpenConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenColoringBook: () -> Unit,
    onOpenAcademy: () -> Unit,
) {
    var projects by remember { mutableStateOf<List<ProjectSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showNewCanvasDialog by remember { mutableStateOf(false) }
    // True from the Create tap until the project exists and we have navigated. createProject
    // allocates a full CanvasEngine and writes PNGs, so the dialog stays on screen for a
    // noticeable moment; without this a second tap on Create ran a second createProject (an
    // orphan "Untitled" project) and a second navigate. Same guard shape as
    // ColoringBookScreen.startProject's `creating`.
    var creating by remember { mutableStateOf(false) }
    var showRecentlyDeleted by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    val scope = rememberCoroutineScope()
    // Cover-screen (or any similarly narrow) window: lead with one-tap Quick Sketch instead of the
    // full New Canvas dialog's size/preset choices -- see QuickSketchScreen's own doc comment for
    // why that's a purpose-built layout rather than a shrunk Editor. The full dialog is still one
    // tap away via the top-bar "+" that only appears in this same narrow case, so nothing is lost.
    val compactWidth = isCompactWidth()
    // Hosts the "Deleted <name> - Undo" snackbar (and the delete/rename failure notices). Screen-scoped
    // on purpose, unlike the editor's save failures: nothing here navigates away in the same click, and
    // a snackbar lost to navigation costs nothing because the deleted project waits in Recently deleted.
    val snackbarHostState = remember { SnackbarHostState() }
    // Re-list whenever the library changes underneath us -- a save committing (fresh thumbnail and
    // updatedAt sort position), a delete, a rename -- rather than once per composition. Coming back
    // from the editor used to list while the save Back had just started was still being written,
    // and nothing ever re-listed, so the card kept the old thumbnail and sort position. Only the
    // first load shows the spinner: a refresh swaps the list in place (keeps scroll position).
    val libraryRevision by repository.libraryRevision.collectAsState()

    LaunchedEffect(libraryRevision) {
        projects = repository.listProjects()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vellum Studio", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (compactWidth) {
                        IconButton(onClick = { showNewCanvasDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New canvas with size options")
                        }
                    }
                    IconButton(onClick = { showRecentlyDeleted = true }) {
                        Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Recently deleted")
                    }
                    IconButton(onClick = onOpenAcademy) {
                        Icon(Icons.Filled.School, contentDescription = "Academy")
                    }
                    IconButton(onClick = onOpenColoringBook) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Coloring Book")
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // Real, pre-existing accessibility gap, found while wiring up Macrobenchmark's
            // UiAutomator-driven gesture tests (see benchmark/.../PanZoomFrameTimingBenchmark.kt):
            // confirmed via a live `uiautomator dump` against this exact screen that
            // ExtendedFloatingActionButton's icon+text slots never merge into an accessible label
            // at all (the node shows up as NAF="true" -- "not accessibility-friendly" -- with
            // empty text AND empty content-desc, even though the label is clearly visible on
            // screen). That means TalkBack users got nothing announced for this button before this
            // fix, not just that By.text(...) couldn't find it in a test. Applied to both of this
            // screen's FAB variants (compact-width Quick Sketch, and the regular New Canvas) since
            // both are the same ExtendedFloatingActionButton shape with the same gap.
            if (compactWidth) {
                ExtendedFloatingActionButton(
                    onClick = {
                        scope.launch {
                            val (meta, engine) = repository.createProject("Quick Sketch", QUICK_SKETCH_CANVAS_PX, QUICK_SKETCH_CANVAS_PX)
                            engine.layers.forEach { it.bitmap.recycle() }
                            onOpenQuickSketch(meta.id)
                        }
                    },
                    icon = { Icon(Icons.Filled.Brush, contentDescription = null) },
                    text = { Text("Quick Sketch") },
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "Quick Sketch"
                    },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { showNewCanvasDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New Canvas") },
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "New Canvas"
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                projects.isEmpty() -> EmptyState(Modifier.align(Alignment.Center))
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    // Bottom inset clears the New Canvas FAB (56dp + 16dp margin): Scaffold only
                    // insets this content for the top bar, not for the FAB, so with a bare 16dp the
                    // last row's Options button sat underneath it and could not be tapped.
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onOpenProject(project.id) },
                            onRename = { renameTarget = project },
                            onDelete = {
                                scope.launch { deleteWithUndo(repository, snackbarHostState, project) }
                            },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameProjectDialog(
            initialName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                scope.launch { renameWithNotice(repository, snackbarHostState, target, newName) }
            },
        )
    }

    if (showRecentlyDeleted) {
        RecentlyDeletedDialog(repository = repository, onDismiss = { showRecentlyDeleted = false })
    }

    if (showNewCanvasDialog) {
        NewCanvasDialog(
            creating = creating,
            // Cancel / tap-outside / system Back are inert while creating: dismissing the dialog
            // mid-create would leave the project being made with nothing on screen to show it.
            onDismiss = { if (!creating) showNewCanvasDialog = false },
            onCreate = { name, preset ->
                if (!creating) {
                    creating = true
                    scope.launch {
                        try {
                            val (meta, engine) = repository.createProject(name, preset.widthPx, preset.heightPx)
                            engine.layers.forEach { it.bitmap.recycle() }
                            showNewCanvasDialog = false
                            onOpenProject(meta.id)
                        } finally {
                            // Also on failure, so a createProject that throws does not leave the
                            // dialog permanently unusable.
                            creating = false
                        }
                    }
                }
            },
        )
    }
}

/**
 * Soft-deletes [project] and offers Undo for the snackbar's Long duration (10s). Undo renames the
 * folder back out of the trash, so what returns is byte-identical. If the snackbar is dismissed,
 * swiped away or replaced by the next delete, nothing is lost: the project stays restorable from
 * Recently deleted for 30 days. A failed move leaves the project untouched and says so.
 */
private suspend fun deleteWithUndo(repository: ProjectRepository, snackbar: SnackbarHostState, project: ProjectSummary) {
    val trashId = try {
        repository.deleteProject(project.id)
    } catch (e: IOException) {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar("Couldn't delete \"${project.name}\" - it was left as it was")
        return
    } ?: return
    // A second delete must not queue behind the first one's ten seconds: the older Undo window simply closes.
    snackbar.currentSnackbarData?.dismiss()
    val result = snackbar.showSnackbar(
        message = "Deleted \"${project.name}\"",
        actionLabel = "Undo",
        withDismissAction = true,
        duration = SnackbarDuration.Long,
    )
    if (result == SnackbarResult.ActionPerformed && !repository.restoreProject(trashId)) {
        snackbar.showSnackbar("Couldn't restore \"${project.name}\" - look in Recently deleted")
    }
}

/** Renames via [ProjectRepository.renameProjectById]; a refusal (newer build, unreadable metadata) or IO failure is told to the user instead of dropped. */
private suspend fun renameWithNotice(repository: ProjectRepository, snackbar: SnackbarHostState, project: ProjectSummary, newName: String) {
    val message = try {
        if (repository.renameProjectById(project.id, newName)) null else "Couldn't rename \"${project.name}\""
    } catch (e: ProjectTooNewException) {
        e.userMessage
    } catch (e: IOException) {
        "Couldn't rename \"${project.name}\": ${e.message}"
    }
    if (message != null) snackbar.showSnackbar(message)
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
private fun ProjectCard(project: ProjectSummary, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val cardBody: @Composable () -> Unit = {
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
                    if (project.isDamaged) {
                        // Still tappable (isOpenable): the editor then shows WHY, with Back and the
                        // diagnostic-log export, instead of the card silently doing nothing.
                        Text(
                            "Damaged - tap for details",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (project.isOpenable) {
                        Text(
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(project.updatedAt)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // Saved by a newer build: this one lists it but never opens, migrates or saves it
                        // (ProjectRepository refuses), so say why the card does nothing when tapped.
                        Text(
                            "Made with a newer Vellum Studio \u2013 update to open",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // Only for a project whose metadata this build can read AND safely rewrite: a
                        // newer-schema or damaged one is refused by the repository, so don't offer it.
                        if (project.isOpenable && !project.isDamaged) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { menuOpen = false; onRename() },
                            )
                        }
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
    val shape = RoundedCornerShape(16.dp)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    if (project.isOpenable) {
        Card(onClick = onClick, shape = shape, colors = colors) { cardBody() }
    } else {
        // The non-clickable Card overload (not `enabled = false`, which would grey out the message).
        Card(shape = shape, colors = colors) { cardBody() }
    }
}

@Composable
internal fun ThumbnailImage(file: File?, modifier: Modifier = Modifier) {
    if (file == null) {
        Box(modifier)
        return
    }
    // Was `produceState`, converted to the equivalent remember+LaunchedEffect it desugars to
    // internally -- this codebase's Compose runtime version (BOM 2024.12.01, Kotlin 2.0.21) has a
    // confirmed-broken ProduceStateDoesNotAssignValue lint check that flags *every* produceState
    // call regardless of whether it assigns `value` (verified with a minimal
    // `produceState(0) { value = 1 }` repro), so this isn't a lint suppression, it's using the
    // identical underlying primitives directly.
    val bitmapState = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(file.path, file.lastModified()) {
        bitmapState.value = withContext(Dispatchers.IO) {
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

@Composable
private fun RenameProjectDialog(initialName: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename canvas") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = {
            // A blank name would leave a nameless card; an unchanged one has nothing to write.
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty() && trimmed != initialName) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewCanvasDialog(creating: Boolean, onDismiss: () -> Unit, onCreate: (String, CanvasSizePreset) -> Unit) {
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
                // FlowRow (not a fixed 2-per-row chunk) so chips pack as many per line as actually
                // fit and wrap individually otherwise -- on a narrow width (e.g. the Z Fold5's
                // cover screen) a long label like "Tablet Screen · 1440×2304" gets its own
                // line instead of being squeezed into a half-width slot and wrapping into an
                // unreadable single-word column.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    availablePresets.forEach { preset ->
                        FilterChip(
                            selected = selected == preset,
                            onClick = { selected = preset },
                            label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.ifBlank { "Untitled" }, selected) }, enabled = !creating) {
                if (creating) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") }
        },
    )
}
