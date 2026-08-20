package com.vellum.studio.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.DrawingCanvasView
import com.vellum.studio.canvas.ToolMode
import com.vellum.studio.canvas.gl.CompositorRenderer
import com.vellum.studio.canvas.gl.LayerCompositorGLView
import com.vellum.studio.model.CustomBrushRepository
import com.vellum.studio.model.PaletteRepository
import com.vellum.studio.model.ProjectMeta
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.RecentColors
import com.vellum.studio.model.SettingsRepository
import com.vellum.studio.network.LiveCanvasBridge
import com.vellum.studio.ui.colorpicker.ColorPickerPanel
import com.vellum.studio.util.Printing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    repository: ProjectRepository,
    paletteRepository: PaletteRepository,
    settingsRepository: SettingsRepository,
    customBrushRepository: CustomBrushRepository,
    projectId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var engine by remember { mutableStateOf<CanvasEngine?>(null) }
    var loading by remember { mutableStateOf(true) }
    var layersPanelOpen by remember { mutableStateOf(false) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var strokesSinceSave by remember { mutableStateOf(0) }
    var undoRedoTick by remember { mutableStateOf(0) }
    val drawingViewRef = remember { mutableStateOf<DrawingCanvasView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Experimental GPU compositor state -- see LayerCompositorGLView's class doc for the exact
    // activation preconditions this mirrors (setting on, no active stroke, every visible layer's
    // blend mode in CompositorRenderer.GPU_SUPPORTED_BLEND_MODES). strokeActive is fed by
    // DrawingCanvasView's onStrokeActiveChanged callback; everything else here is already
    // Compose-observable state read directly below.
    var strokeActive by remember { mutableStateOf(false) }
    val glViewRef = remember { mutableStateOf<LayerCompositorGLView?>(null) }

    fun saveNow() {
        val m = meta
        val e = engine
        if (m != null && e != null) {
            scope.launch { meta = repository.saveProject(m, e) }
        }
    }

    LaunchedEffect(projectId) {
        loading = true
        val loaded = repository.loadProject(projectId)
        if (loaded != null) {
            meta = loaded.first
            engine = loaded.second
            LiveCanvasBridge.set(loaded.first, loaded.second)
        }
        loading = false
    }

    // currentTool is included so switching into Paint by Number immediately shows the numbered
    // region overlay (drawn in DrawingCanvasView.onDraw) instead of waiting for some unrelated
    // redraw (e.g. a pan/zoom gesture) to happen to reveal it - the canvas is a plain AndroidView,
    // so a Compose state change alone doesn't invalidate it without this explicit wiring.
    LaunchedEffect(engine?.revision, undoRedoTick, engine?.currentTool) {
        drawingViewRef.value?.invalidate()
    }

    DisposableEffect(Unit) {
        onDispose { LiveCanvasBridge.set(null, null) }
    }

    BackHandler {
        saveNow()
        onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            val eng = engine
            TopAppBar(
                title = { Text(meta?.name ?: "Loading…", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { saveNow(); onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            eng ?: return@IconButton
                            eng.undoManager.undo { id -> eng.layers.firstOrNull { it.id == id } }
                            eng.bumpRevision()
                            undoRedoTick++
                        },
                        enabled = eng?.undoManager?.canUndo == true,
                    ) { Icon(Icons.Filled.Undo, contentDescription = "Undo") }
                    IconButton(
                        onClick = {
                            eng ?: return@IconButton
                            eng.undoManager.redo { id -> eng.layers.firstOrNull { it.id == id } }
                            eng.bumpRevision()
                            undoRedoTick++
                        },
                        enabled = eng?.undoManager?.canRedo == true,
                    ) { Icon(Icons.Filled.Redo, contentDescription = "Redo") }

                    if (eng != null) {
                        var toolMenuOpen by remember { mutableStateOf(false) }
                        val toolActive = eng.currentTool != ToolMode.BRUSH
                        Box {
                            IconButton(onClick = { toolMenuOpen = true }) {
                                Icon(
                                    when (eng.currentTool) {
                                        ToolMode.PAINT_BY_NUMBER -> Icons.Filled.LooksOne
                                        ToolMode.SELECT -> Icons.Filled.CropFree
                                        else -> Icons.Filled.FormatColorFill
                                    },
                                    contentDescription = "Tools",
                                    tint = if (toolActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            DropdownMenu(expanded = toolMenuOpen, onDismissRequest = { toolMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Brush (free draw)") },
                                    leadingIcon = { Icon(Icons.Filled.Brush, contentDescription = null) },
                                    onClick = { eng.currentTool = ToolMode.BRUSH; toolMenuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Bucket fill") },
                                    leadingIcon = { Icon(Icons.Filled.FormatColorFill, contentDescription = null) },
                                    onClick = { eng.currentTool = ToolMode.FILL; toolMenuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Paint by number") },
                                    leadingIcon = { Icon(Icons.Filled.LooksOne, contentDescription = null) },
                                    onClick = { eng.currentTool = ToolMode.PAINT_BY_NUMBER; toolMenuOpen = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Select (move pixels)") },
                                    leadingIcon = { Icon(Icons.Filled.CropFree, contentDescription = null) },
                                    onClick = { eng.currentTool = ToolMode.SELECT; toolMenuOpen = false },
                                )
                            }
                        }
                        // Smart Shape Assist (see ShapeAssist/DrawingCanvasView) -- placed right
                        // next to the tool-mode dropdown it modifies the behavior of. Purely a
                        // per-session toggle: turning it off mid-drawing has zero effect on
                        // strokes already on the canvas, only on what happens after the *next*
                        // stroke completes.
                        IconButton(onClick = { eng.shapeAssistEnabled = !eng.shapeAssistEnabled }) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = "Shape assist",
                                tint = if (eng.shapeAssistEnabled) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                        ColorSwatchButton(colorArgb = eng.currentColorArgb, onClick = { colorPickerOpen = true })

                        var symmetryMenuOpen by remember { mutableStateOf(false) }
                        val symmetryActive = eng.symmetryMode != com.vellum.studio.canvas.SymmetryMode.NONE
                        Box {
                            IconButton(onClick = { symmetryMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.Balance,
                                    contentDescription = "Symmetry",
                                    tint = if (symmetryActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                )
                            }
                            DropdownMenu(expanded = symmetryMenuOpen, onDismissRequest = { symmetryMenuOpen = false }) {
                                com.vellum.studio.canvas.SymmetryMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = { eng.symmetryMode = mode; symmetryMenuOpen = false },
                                    )
                                }
                            }
                        }
                    }

                    IconButton(onClick = { drawingViewRef.value?.resetView() }) {
                        Icon(Icons.Filled.CenterFocusWeak, contentDescription = "Fit to screen")
                    }
                    IconButton(onClick = { layersPanelOpen = !layersPanelOpen }) {
                        Icon(Icons.Filled.Layers, contentDescription = "Layers")
                    }

                    if (eng != null) {
                        var exportMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { exportMenuOpen = true }) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export")
                        }
                        DropdownMenu(expanded = exportMenuOpen, onDismissRequest = { exportMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Save PNG to Gallery") },
                                leadingIcon = { Icon(Icons.Filled.SaveAlt, contentDescription = null) },
                                onClick = {
                                    exportMenuOpen = false
                                    val m = meta
                                    val e = engine
                                    if (m != null && e != null) {
                                        // A stylus stroke reachable via a second finger on this menu could still
                                        // be live-mutating a layer's Bitmap on the main thread while flatten()
                                        // reads it on a background thread - same hazard flattenScratchOnto's
                                        // strokeInProgressLayerId guard exists for on the layer-delete path.
                                        if (e.strokeInProgressLayerId != null) {
                                            scope.launch { snackbarHostState.showSnackbar("Finish the current stroke before exporting") }
                                        } else {
                                            scope.launch {
                                                val uri = repository.exportToGallery(m, e)
                                                snackbarHostState.showSnackbar(if (uri != null) "Saved to Pictures/Vellum Studio" else "Export failed")
                                            }
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Print") },
                                leadingIcon = { Icon(Icons.Filled.Print, contentDescription = null) },
                                onClick = {
                                    exportMenuOpen = false
                                    val m = meta
                                    val e = engine
                                    if (m != null && e != null) {
                                        if (e.strokeInProgressLayerId != null) {
                                            scope.launch { snackbarHostState.showSnackbar("Finish the current stroke before printing") }
                                        } else {
                                            // flatten() allocates and composites a full-resolution bitmap - keep it
                                            // off the main thread so a large multi-layer canvas doesn't jank/ANR
                                            // right as the print dialog is trying to appear.
                                            scope.launch {
                                                val bmp = withContext(Dispatchers.Default) { e.flatten() }
                                                Printing.printBitmap(context, m.name, bmp)
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading || engine == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    val eng = engine!!
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    DrawingCanvasView(ctx).apply {
                                        attachEngine(eng)
                                        onStrokeCommitted = {
                                            strokesSinceSave++
                                            if (strokesSinceSave >= 6) {
                                                strokesSinceSave = 0
                                                saveNow()
                                            }
                                        }
                                        onStrokeActiveChanged = { active ->
                                            strokeActive = active
                                            glViewRef.value?.requestComposite()
                                        }
                                        onTransformChanged = { glViewRef.value?.requestComposite() }
                                        onShapeAssistCandidate = { label ->
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Snap to $label?",
                                                    actionLabel = "Snap",
                                                    duration = SnackbarDuration.Short,
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    drawingViewRef.value?.applyPendingShapeSnap()
                                                }
                                            }
                                        }
                                        drawingViewRef.value = this
                                    }
                                },
                            )

                            // Experimental GPU compositor overlay -- see LayerCompositorGLView's
                            // class doc. Only ever shown when idle; DrawingCanvasView underneath
                            // keeps rendering itself regardless (harmless -- while idle it isn't
                            // receiving invalidate() calls anyway), so hiding this overlay by
                            // flipping the setting off instantly reveals the exact proven software
                            // path with no other change needed.
                            val gpuEligible = settingsRepository.experimentalGpuCompositor &&
                                !strokeActive &&
                                eng.layers.all { !it.visible || it.blendMode in CompositorRenderer.GPU_SUPPORTED_BLEND_MODES }
                            // Reading eng.revision ties this composable's recomposition to every
                            // content-affecting mutation, so the GL overlay redraws whenever the
                            // settled canvas actually changed underneath it.
                            val contentRevision = eng.revision
                            if (gpuEligible) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize(),
                                    factory = { ctx ->
                                        LayerCompositorGLView(ctx).apply {
                                            attach(eng) { drawingViewRef.value?.currentMatrixSnapshot() ?: android.graphics.Matrix() }
                                            glViewRef.value = this
                                        }
                                    },
                                    update = { view ->
                                        contentRevision // read for recomposition tracking, see comment above
                                        view.requestComposite()
                                    },
                                    onRelease = { if (glViewRef.value === it) glViewRef.value = null },
                                )
                            }
                        }
                        BrushBar(engine = eng, customBrushRepository = customBrushRepository, modifier = Modifier.fillMaxWidth())
                    }

                    AnimatedVisibility(
                        visible = layersPanelOpen,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        enter = slideInHorizontally(initialOffsetX = { it }),
                        exit = slideOutHorizontally(targetOffsetX = { it }),
                    ) {
                        LayersPanel(
                            engine = eng,
                            modifier = Modifier.width(340.dp).fillMaxHeight(),
                            onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                        )
                    }
                }
            }
        }
    }

    val eng = engine
    if (colorPickerOpen && eng != null) {
        Dialog(onDismissRequest = {
            colorPickerOpen = false
            RecentColors.note(eng.currentColorArgb)
        }) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp).width(320.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                    Text("Color", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    ColorPickerPanel(
                        paletteRepository = paletteRepository,
                        initialColorArgb = eng.currentColorArgb,
                        onColorChanged = { eng.currentColorArgb = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorSwatchButton(colorArgb: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 6.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(Color(colorArgb))
            .border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
    )
}
