package com.vellum.studio.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.DrawingCanvasView
import com.vellum.studio.canvas.gl.LayerCompositorGLView
import com.vellum.studio.model.EditorAutosaver
import com.vellum.studio.model.LoadResult
import com.vellum.studio.model.PaletteRepository
import com.vellum.studio.model.ProjectMeta
import com.vellum.studio.model.ProjectRepository
import com.vellum.studio.model.RecentColors
import com.vellum.studio.model.SaveReason
import com.vellum.studio.model.SettingsRepository
import com.vellum.studio.network.LiveCanvasBridge
import com.vellum.studio.ui.colorpicker.ColorPickerPanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Purpose-built compact layout for the cover-screen posture (see [com.vellum.studio.util.isCompactWidth])
 * -- one brush (whatever [CanvasEngine.currentBrush] already defaults to; no brush switcher here),
 * one color swatch, one small canvas, minimal chrome. Deliberately NOT a shrunk copy of the full
 * [EditorScreen]: no layers panel, tool-mode/symmetry dropdowns, export menu, print flow, or
 * keyboard shortcuts -- a cover-screen quick-capture session is meant to be a fast in/out sketch,
 * not the full drawing workspace.
 *
 * Reuses [CanvasSurface] (the same AndroidView/GPU-compositor/drag-and-drop wiring [EditorScreen]
 * hosts in both its layouts) rather than a fourth copy of that interop code, and the same
 * save/color-picker/LiveCanvasBridge patterns [EditorScreen] already established, just with far
 * less UI wrapped around them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSketchScreen(
    repository: ProjectRepository,
    paletteRepository: PaletteRepository,
    settingsRepository: SettingsRepository,
    projectId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var meta by remember { mutableStateOf<ProjectMeta?>(null) }
    var engine by remember { mutableStateOf<CanvasEngine?>(null) }
    var loading by remember { mutableStateOf(true) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    var strokeActive by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val drawingViewRef = remember { mutableStateOf<DrawingCanvasView?>(null) }
    val glViewRef = remember { mutableStateOf<LayerCompositorGLView?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    // Same durability policy as EditorScreen (see EditorAutosaver): created with the engine once the
    // project has loaded. This screen used to save fire-and-forget every 6th stroke and on Back, which
    // lost up to 5 strokes to Home + a low-memory kill, navigated away before the write landed, and
    // let a cancelled composition scope drop the save -- the same defects the main editor had.
    var autosaver by remember { mutableStateOf<EditorAutosaver?>(null) }
    var openFailure by remember { mutableStateOf<LoadResult.Failed?>(null) }
    var leaving by remember { mutableStateOf(false) }

    /** Back: save, WAIT for the save (bounded), then leave -- see EditorScreen.leaveEditor for why. */
    fun leaveQuickSketch() {
        if (leaving) return
        val saver = autosaver
        if (saver == null) {
            onBack()
            return
        }
        val pending = saver.flush(SaveReason.BACK)
        if (pending.isCompleted) {
            onBack()
            return
        }
        leaving = true
        scope.launch {
            withTimeoutOrNull(BACK_SAVE_WAIT_MS) { pending.await() }
            onBack()
        }
    }

    LaunchedEffect(projectId) {
        loading = true
        openFailure = null
        val loaded = when (val result = repository.loadProject(projectId)) {
            is LoadResult.Ok -> result.project
            is LoadResult.Failed -> {
                openFailure = result
                null
            }
        }
        if (loaded != null) {
            meta = loaded.meta
            autosaver = repository.openAutosaver(loaded.meta, loaded.engine)
            engine = loaded.engine
            LiveCanvasBridge.set(loaded.meta, loaded.engine)
        }
        loading = false
        if (loaded != null && loaded.quarantinedLayerNames.isNotEmpty()) {
            snackbarHostState.showSnackbar(
                "Couldn't read layer ${loaded.quarantinedLayerNames.joinToString(", ") { "\"$it\"" }}; it opens blank. " +
                    "The damaged file was kept aside (.corrupt) rather than overwritten.",
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { LiveCanvasBridge.set(null, null) }
    }

    // Autosave wiring, identical to EditorScreen's: debounce timer with the screen, lifecycle flush
    // (ON_STOP) and final flush on leaving independent of it, saves on the app-scoped coordinator.
    val lifecycleOwner = LocalLifecycleOwner.current
    autosaver?.let { saver ->
        LaunchedEffect(saver) { saver.run() }
        DisposableEffect(saver, lifecycleOwner) {
            lifecycleOwner.lifecycle.addObserver(saver.lifecycleObserver)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(saver.lifecycleObserver)
                saver.close()
            }
        }
    }

    BackHandler { leaveQuickSketch() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        topBar = {
            val eng = engine
            TopAppBar(
                title = { Text("Quick Sketch", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { leaveQuickSketch() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (eng != null) {
                        ColorSwatchButton(colorArgb = eng.currentColorArgb, onClick = { colorPickerOpen = true })
                        IconButton(onClick = {
                            val m = meta ?: return@IconButton
                            scope.launch {
                                val uri = repository.exportToGallery(m, eng)
                                snackbarHostState.showSnackbar(if (uri != null) "Saved to Pictures/Vellum Studio" else "Export failed")
                            }
                        }) {
                            Icon(Icons.Filled.SaveAlt, contentDescription = "Save PNG to Gallery")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                openFailure != null -> ProjectOpenErrorCard(
                    failure = openFailure!!,
                    onBack = { leaveQuickSketch() },
                    onExportLog = { exportDiagnosticLog(context) },
                    modifier = Modifier.align(Alignment.Center),
                )
                loading || engine == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> CanvasSurface(
                    engine = engine!!,
                    settingsRepository = settingsRepository,
                    strokeActive = strokeActive,
                    onStrokeActiveChanged = { active ->
                        strokeActive = active
                        glViewRef.value?.requestComposite()
                    },
                    // The debounce on engine.revision is the real trigger; this every-6th-commit flush
                    // is the backstop under it (same as EditorScreen).
                    onStrokeCommitted = { autosaver?.noteStrokeCommitted() },
                    onTransformChanged = { glViewRef.value?.requestComposite() },
                    onShapeAssistCandidate = {}, // Shape assist is off by default and there's no UI
                    // here to accept a snap suggestion -- deliberately a no-op rather than wiring a
                    // Snackbar action for a toggle this screen never exposes.
                    drawingViewRef = drawingViewRef,
                    glViewRef = glViewRef,
                    onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (leaving) SavingScrim(Modifier.matchParentSize())
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
                    ColorPickerPanel(
                        paletteRepository = paletteRepository,
                        initialColorArgb = eng.currentColorArgb,
                        onColorChanged = { eng.currentColorArgb = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
