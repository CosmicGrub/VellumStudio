package com.vellum.studio.ui.coloringbook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vellum.studio.art.ColoringTemplate
import com.vellum.studio.art.ColoringTemplates
import com.vellum.studio.canvas.PhotoConverter
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.UserPhotoTemplate
import com.vellum.studio.model.UserPhotoTemplateRepository
import com.vellum.studio.util.AssetBitmapCache
import com.vellum.studio.util.FileBitmapCache
import com.vellum.studio.util.Printing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ColoringBookScreen(
    repository: ProjectRepository,
    userPhotoTemplateRepository: UserPhotoTemplateRepository,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var printing by remember { mutableStateOf(false) }
    var viewingReference by remember { mutableStateOf<ColoringTemplate?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // User-generated "My Photos" templates load alongside the bundled library so they behave as
    // first-class gallery entries (same card, same category-filter chip, same open/print/view-
    // reference flow) rather than a parallel UI. Refreshed after every save/delete below.
    var userPhotoTemplates by remember { mutableStateOf<List<UserPhotoTemplate>>(emptyList()) }
    LaunchedEffect(Unit) { userPhotoTemplates = userPhotoTemplateRepository.list() }
    val allTemplates = remember(userPhotoTemplates) {
        ColoringTemplates.all + userPhotoTemplates.map { userPhotoTemplateRepository.toColoringTemplate(it) }
    }
    val userPhotoTemplateIds = remember(userPhotoTemplates) { userPhotoTemplates.map { it.id }.toSet() }
    val categories = remember(userPhotoTemplates) { allTemplates.map { it.category }.distinct() }

    // Photo-import pipeline: pick -> decode -> (name/style dialog) -> convert (CPU-bound, off the
    // main thread inside PhotoConverter.convert itself) -> persist -> show a plain, honest result.
    var pendingPhotoBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var importingPhoto by remember { mutableStateOf(false) }
    var convertingPhoto by remember { mutableStateOf(false) }
    var photoResultMessage by remember { mutableStateOf<String?>(null) }
    var photoErrorMessage by remember { mutableStateOf<String?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null || importingPhoto || convertingPhoto) return@rememberLauncherForActivityResult
        importingPhoto = true
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            importingPhoto = false
            if (bitmap != null) {
                pendingPhotoBitmap = bitmap
            } else {
                photoErrorMessage = "Couldn't read that photo. Try a different one."
            }
        }
    }

    fun convertAndSavePhoto(bitmap: Bitmap, name: String, preset: PhotoConverter.Preset) {
        convertingPhoto = true
        scope.launch {
            val result = runCatching { PhotoConverter.convert(bitmap, preset) }.getOrNull()
            bitmap.recycle()
            if (result == null) {
                convertingPhoto = false
                photoErrorMessage = "Couldn't convert this photo. Try a different one."
                return@launch
            }
            val saved = userPhotoTemplateRepository.save(name, preset, result)
            result.reference.recycle()
            result.lineArt.recycle()
            userPhotoTemplates = userPhotoTemplateRepository.list()
            convertingPhoto = false
            photoResultMessage = if (saved.isPaintByNumberEligible) {
                "\"$name\" is ready in My Photos. Its shapes are clearly separated, so Paint by Number is available for it too."
            } else {
                "\"$name\" is ready in My Photos. This works great to trace or color freely, but its shapes are too soft-edged for paint-by-number — try a photo with bolder, more separated shapes for that mode."
            }
        }
    }

    fun deletePhotoTemplate(template: UserPhotoTemplate) {
        scope.launch {
            userPhotoTemplates = userPhotoTemplateRepository.delete(template.id)
            snackbarHostState.showSnackbar("Removed \"${template.name}\" from My Photos")
        }
    }

    val templates = remember(selectedCategory, userPhotoTemplates) {
        val cat = selectedCategory
        if (cat == null) allTemplates else allTemplates.filter { it.category == cat }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Coloring Book") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (importingPhoto) {
                        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(
                            onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            enabled = !convertingPhoto,
                        ) { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Import photo") }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "Original line-art pages — pick one to start coloring. The outline sits on a locked layer so bucket fill and brushes stay bounded by it. Tap the photo icon above to turn one of your own photos into a page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    lazyRowItems(listOf<String?>(null) + categories) { category ->
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
                        val userPhotoTemplate = if (template.id in userPhotoTemplateIds) {
                            userPhotoTemplates.first { it.id == template.id }
                        } else {
                            null
                        }
                        TemplateCard(
                            template = template,
                            onClick = { startProject(template) },
                            onPrint = { printTemplate(template) },
                            onViewReference = { viewingReference = template },
                            onDelete = userPhotoTemplate?.let { { deletePhotoTemplate(it) } },
                        )
                    }
                }
            }
            if (creating || printing || convertingPhoto) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    if (convertingPhoto) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(
                                "Converting photo… this can take up to 20 seconds",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    viewingReference?.let { template ->
        ReferenceViewerDialog(template = template, onDismiss = { viewingReference = null })
    }

    pendingPhotoBitmap?.let { bitmap ->
        PhotoImportOptionsDialog(
            defaultName = "Photo ${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())}",
            onDismiss = {
                bitmap.recycle()
                pendingPhotoBitmap = null
            },
            onConfirm = { name, preset ->
                pendingPhotoBitmap = null
                convertAndSavePhoto(bitmap, name, preset)
            },
        )
    }

    photoResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { photoResultMessage = null },
            title = { Text("Photo converted") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { photoResultMessage = null }) { Text("OK") } },
        )
    }

    photoErrorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { photoErrorMessage = null },
            title = { Text("Couldn't convert photo") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { photoErrorMessage = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun PhotoImportOptionsDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, preset: PhotoConverter.Preset) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var preset by remember { mutableStateOf(PhotoConverter.Preset.SIMPLE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Convert Photo") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Style", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhotoConverter.Preset.entries.forEach { p ->
                        FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.label) })
                    }
                }
                Text(
                    when (preset) {
                        PhotoConverter.Preset.SIMPLE -> "Bolder, more forgiving outlines — the best default for most camera-roll photos."
                        PhotoConverter.Preset.DETAILED -> "More faithful to the photo's detail, but busy photos are less likely to close into fillable shapes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.ifBlank { defaultName }, preset) }) { Text("Convert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateCard(
    template: ColoringTemplate,
    onClick: () -> Unit,
    onPrint: () -> Unit,
    onViewReference: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        // Long-press to delete mirrors BrushBar's convention for custom brushes -- only "My Photos"
        // cards pass a non-null onDelete, so bundled library pages can never be long-press-deleted.
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onDelete),
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
                // Only templates carrying a fully-realized color reference (real-masterwork scans,
                // or a converted photo's own reference) have a "finished piece" to show alongside
                // the blank line art -- procedural templates have neither path set.
                if (template.referenceAssetPath != null || template.referenceFilePath != null) {
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
    val assetPath = template.referenceAssetPath
    val filePath = template.referenceFilePath
    if (assetPath == null && filePath == null) return
    val bitmapState = produceState<ImageBitmap?>(initialValue = null, assetPath, filePath) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (assetPath != null) AssetBitmapCache.get(context, assetPath).asImageBitmap()
                else FileBitmapCache.get(filePath!!).asImageBitmap()
            }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(template.name) },
        text = {
            Column {
                Text(
                    "The full-color original this line art was derived from — color it however you like.",
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
