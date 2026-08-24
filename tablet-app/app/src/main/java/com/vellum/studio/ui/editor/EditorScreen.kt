package com.vellum.studio.ui.editor

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.vellum.studio.canvas.CanvasEngine
import com.vellum.studio.canvas.DrawingCanvasView
import com.vellum.studio.canvas.SymmetryMode
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
import com.vellum.studio.util.FoldPosture
import com.vellum.studio.util.Printing
import com.vellum.studio.util.primaryPaneWeightForHingeAngle
import com.vellum.studio.util.rememberFoldState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// One "[" / "]" press worth of brush-size change -- see handleKeyShortcut. Additive rather than
// multiplicative, matching how a single keypress-driven nudge behaves in most drawing apps; picked
// so a single press is a small, easily-repeatable nudge across CanvasEngine's whole size-multiplier
// range (~39 presses end to end), not a jump big enough to overshoot a precise target size.
private const val BRUSH_SIZE_STEP = 0.1f

/** Maps both the top-row number keys and the numpad digits to 0-9 for handleKeyShortcut's
 * opacity shortcut -- a numpad-equipped Bluetooth keyboard should work exactly like the top row. */
private fun digitForKey(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var printPresetDialogOpen by remember { mutableStateOf(false) }
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

    // Foldable-aware layout signal only -- never touches DrawingCanvasView's stylus-exclusive
    // input routing. See util/FoldState.kt. Every posture other than HALF_OPENED_TABLETOP
    // (FLAT, HALF_OPENED_OTHER, NO_FOLD_FEATURE) renders the exact same single-column layout.
    val foldState = rememberFoldState()

    fun saveNow() {
        val m = meta
        val e = engine
        if (m != null && e != null) {
            scope.launch { meta = repository.saveProject(m, e) }
        }
    }

    /** Runs the Print flow's chosen [preset] (see [printPresetDialogOpen]'s dialog) -- flatten +
     * any [Printing.PrintPreset.HIGH_DPI_ARCHIVAL] upscale happen off the main thread (both are
     * real bitmap-sized work), then [Printing.printBitmap] itself runs back on Main, same "resolve
     * heavy work off-thread, hand the system UI call back to Main" split the pre-existing code here
     * already used for plain Standard printing. */
    fun runPrint(preset: Printing.PrintPreset) {
        printPresetDialogOpen = false
        val m = meta
        val e = engine
        if (m == null || e == null) return
        if (e.strokeInProgressLayerId != null) {
            scope.launch { snackbarHostState.showSnackbar("Finish the current stroke before printing") }
            return
        }
        scope.launch {
            val prepared = withContext(Dispatchers.Default) {
                Printing.preparePrintBitmap(e.flatten(), preset)
            }
            Printing.printBitmap(context, m.name, prepared.bitmap)
            if (preset == Printing.PrintPreset.HIGH_DPI_ARCHIVAL && !prepared.archivalUpscaleApplied) {
                snackbarHostState.showSnackbar("Canvas is already at this device's safe resolution ceiling -- printed at standard resolution")
            }
        }
    }

    // --- Keyboard shortcuts (see handleKeyShortcut below for the full list and how this is wired
    // up) -- doUndo/doRedo/adjustBrushSize/setOpacityFromDigit are plain functions rather than
    // being inlined into handleKeyShortcut so the top bar's Undo/Redo IconButtons can call the
    // exact same logic a keyboard shortcut does, instead of two copies of it drifting apart. ---
    fun doUndo() {
        val e = engine ?: return
        if (!e.undoManager.canUndo) return
        e.undoManager.undo { id -> e.layers.firstOrNull { it.id == id } }
        e.bumpRevision()
        undoRedoTick++
    }

    fun doRedo() {
        val e = engine ?: return
        if (!e.undoManager.canRedo) return
        e.undoManager.redo { id -> e.layers.firstOrNull { it.id == id } }
        e.bumpRevision()
        undoRedoTick++
    }

    fun adjustBrushSize(delta: Float) {
        val e = engine ?: return
        e.brushSizeMultiplier = (e.brushSizeMultiplier + delta)
            .coerceIn(CanvasEngine.MIN_BRUSH_SIZE_MULTIPLIER, CanvasEngine.MAX_BRUSH_SIZE_MULTIPLIER)
    }

    fun setOpacityFromDigit(digit: Int) {
        val e = engine ?: return
        // Standard "0 means 100%" convention (matches Photoshop/similar apps): the ten keys 1-9/0
        // map onto the ten round 10% steps, and 100% needs a key too -- 0 is the natural spare.
        e.brushOpacityMultiplier = if (digit == 0) 1f else digit / 10f
    }

    /**
     * Hardware/Bluetooth-keyboard shortcuts for this screen: Ctrl+Z undo, Ctrl+Shift+Z or Ctrl+Y
     * redo, [ / ] step brush size down/up, 1-9 and 0 set brush opacity to 10%-100%. Also documented
     * in Settings > Input so they're discoverable outside of the one-time Snackbar hint below.
     *
     * Wired via a focusable root (see the Scaffold's modifier) + [onPreviewKeyEvent] rather than
     * overriding Activity.onKeyDown -- investigated both, and this is the one that's actually
     * scoped correctly: it only ever fires while EditorScreen (not the gallery, not Settings) is
     * the visible destination, with no NavGraph-level plumbing needed to gate it. Nothing else in
     * this screen's composition ever steals focus away from that root in practice -- there's no
     * TextField living directly in it, and the color-picker/BrushEditorDialog dialogs are each a
     * genuinely separate Android Window, so they naturally take over key routing while shown and
     * hand it back on dismiss with no extra bookkeeping needed here.
     *
     * Deliberately does NOT attempt a Space+drag-to-pan shortcut. This app's pan/zoom is raw-touch
     * driven inside DrawingCanvasView (a plain View, not Compose, and one of this project's
     * hardened files) keyed entirely off MotionEvent tool-type/pointer-id bookkeeping -- there's no
     * existing "is this pointer allowed to pan" hook a Compose-side "space is held" boolean could
     * feed into without either reaching into that hardened stylus/finger routing logic directly, or
     * adding a parallel input path that would only ever be exercised by a mouse pointer (this app
     * has no TOOL_TYPE_MOUSE handling anywhere today -- fingers already pan for free, and a S Pen's
     * whole purpose here is to draw, not pan). That combination -- touching hardened routing, to
     * serve an input device this app doesn't otherwise support -- is exactly the "fragile, not
     * worth forcing" case the task called out, so it's skipped in favor of the four shortcuts above.
     */
    fun handleKeyShortcut(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (engine == null) return false
        return when {
            event.isCtrlPressed && event.isShiftPressed && event.key == Key.Z -> { doRedo(); true }
            event.isCtrlPressed && event.key == Key.Z -> { doUndo(); true }
            event.isCtrlPressed && event.key == Key.Y -> { doRedo(); true }
            event.key == Key.LeftBracket -> { adjustBrushSize(-BRUSH_SIZE_STEP); true }
            event.key == Key.RightBracket -> { adjustBrushSize(BRUSH_SIZE_STEP); true }
            else -> digitForKey(event.key)?.let { setOpacityFromDigit(it); true } ?: false
        }
    }

    val keyboardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { keyboardFocusRequester.requestFocus() }

    LaunchedEffect(loading) {
        if (!loading && !settingsRepository.keyboardShortcutsHintShown) {
            // Set the moment we decide to show it (not after the Snackbar's dismissed) -- backing
            // out of the editor mid-Snackbar shouldn't leave it eligible to fire again next project.
            settingsRepository.keyboardShortcutsHintShown = true
            snackbarHostState.showSnackbar(
                message = "Keyboard shortcuts: Ctrl+Z/Y undo/redo, [ ] brush size, 1-9/0 opacity. " +
                    "More in Settings > Input.",
                actionLabel = "Got it",
                duration = SnackbarDuration.Long,
            )
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
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(keyboardFocusRequester)
            .focusable()
            .onPreviewKeyEvent { handleKeyShortcut(it) },
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
                    // M3's TopAppBar right-aligns this whole actions block at
                    // x = barWidth - actionsWidth, with no awareness of the navigationIcon's own
                    // width. On a wide (tablet) bar that's harmless since actionsWidth already
                    // leaves room, but on a phone-width bar (the Z Fold5's cover screen, ~387dp)
                    // these icons' natural width alone is close to the *entire* bar width, so
                    // that formula lands actions right on top of the nav Back button and squeezes
                    // the title to 0dp. Capping the row's width relative to screen width guarantees
                    // navIcon + a title sliver always get room; horizontalScroll keeps every icon
                    // reachable instead of clipping them. On any screen wide enough for the icons
                    // to fit under the cap already (every tablet/foldable-main-screen posture this
                    // app targets), the cap simply never binds and nothing scrolls -- zero change.
                    // NOTE: undo/redo call the same doUndo/doRedo functions the keyboard shortcuts
                    // use (see handleKeyShortcut above) so the icon and the shortcut can never drift
                    // apart; both still live inside the width cap + horizontalScroll below, so
                    // neither bypasses the cover-screen fix.
                    val actionsMaxWidth = (LocalConfiguration.current.screenWidthDp.dp - 140.dp).coerceAtLeast(120.dp)
                    Row(
                        modifier = Modifier.widthIn(max = actionsMaxWidth).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = ::doUndo,
                            enabled = eng?.undoManager?.canUndo == true,
                        ) { Icon(Icons.Filled.Undo, contentDescription = "Undo") }
                        IconButton(
                            onClick = ::doRedo,
                            enabled = eng?.undoManager?.canRedo == true,
                        ) { Icon(Icons.Filled.Redo, contentDescription = "Redo") }

                        if (eng != null) {
                            ToolModeSelector(eng)
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
                            SymmetryModeSelector(eng)
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
                                        printPresetDialogOpen = true
                                    },
                                )
                            }
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
                    if (foldState.posture == FoldPosture.HALF_OPENED_TABLETOP) {
                        // Tabletop/flex posture: the hinge physically bisects the screen, so split
                        // into two stacked panes -- canvas above the crease, controls below it,
                        // with a Spacer matching the hinge's own size so neither pane draws
                        // content under the occluded/obscured band. As of the continuous-hinge-angle
                        // work (see util/FoldState.kt), the canvas/controls split is proportional to
                        // the live angle rather than a fixed ratio -- see
                        // primaryPaneWeightForHingeAngle.
                        val hingeGapDp = with(LocalDensity.current) {
                            (foldState.hingeBounds?.height() ?: 0).coerceAtLeast(0).toDp()
                        }
                        val canvasWeight = primaryPaneWeightForHingeAngle(foldState.hingeAngleDegrees)
                        Column(Modifier.fillMaxSize()) {
                            CanvasSurface(
                                engine = eng,
                                settingsRepository = settingsRepository,
                                strokeActive = strokeActive,
                                onStrokeActiveChanged = { active ->
                                    strokeActive = active
                                    glViewRef.value?.requestComposite()
                                },
                                onStrokeCommitted = {
                                    strokesSinceSave++
                                    if (strokesSinceSave >= 6) {
                                        strokesSinceSave = 0
                                        saveNow()
                                    }
                                },
                                onTransformChanged = { glViewRef.value?.requestComposite() },
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
                                },
                                drawingViewRef = drawingViewRef,
                                glViewRef = glViewRef,
                                onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                modifier = Modifier.weight(canvasWeight).fillMaxWidth(),
                            )
                            Spacer(Modifier.height(hingeGapDp))
                            Column(
                                Modifier
                                    .weight(1f - canvasWeight)
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    ToolModeSelector(eng)
                                    SymmetryModeSelector(eng)
                                }
                                BrushBar(engine = eng, customBrushRepository = customBrushRepository, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    } else {
                        // FLAT, HALF_OPENED_OTHER (vertical hinge / book-held-ajar), and
                        // NO_FOLD_FEATURE (cover screen, non-foldable displays) all render this
                        // exact same single-column layout -- unchanged from before FoldState existed.
                        // NO_FOLD_FEATURE on a narrow (cover-screen-width) display instead routes to
                        // QuickSketchScreen via NavGraph -- see GalleryScreen's cover-screen quick
                        // capture action -- so a narrow EditorScreen instance here means the user
                        // explicitly opened the full editor from the cover screen and wants it as-is.
                        Column(Modifier.fillMaxSize()) {
                            CanvasSurface(
                                engine = eng,
                                settingsRepository = settingsRepository,
                                strokeActive = strokeActive,
                                onStrokeActiveChanged = { active ->
                                    strokeActive = active
                                    glViewRef.value?.requestComposite()
                                },
                                onStrokeCommitted = {
                                    strokesSinceSave++
                                    if (strokesSinceSave >= 6) {
                                        strokesSinceSave = 0
                                        saveNow()
                                    }
                                },
                                onTransformChanged = { glViewRef.value?.requestComposite() },
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
                                },
                                drawingViewRef = drawingViewRef,
                                glViewRef = glViewRef,
                                onMessage = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                            BrushBar(engine = eng, customBrushRepository = customBrushRepository, modifier = Modifier.fillMaxWidth())
                        }
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

    if (printPresetDialogOpen) {
        AlertDialog(
            onDismissRequest = { printPresetDialogOpen = false },
            title = { Text("Print quality") },
            text = {
                Column {
                    Printing.PrintPreset.entries.forEach { preset ->
                        Text(
                            "${preset.label}: ${preset.description}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { runPrint(Printing.PrintPreset.HIGH_DPI_ARCHIVAL) }) {
                    Text(Printing.PrintPreset.HIGH_DPI_ARCHIVAL.label)
                }
            },
            dismissButton = {
                TextButton(onClick = { runPrint(Printing.PrintPreset.STANDARD) }) {
                    Text(Printing.PrintPreset.STANDARD.label)
                }
            },
        )
    }
}

@Composable
internal fun ColorSwatchButton(colorArgb: Int, onClick: () -> Unit) {
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

/**
 * The drawing surface: [DrawingCanvasView] (the hardened stylus-exclusive input path -- this
 * wrapper never touches its internals, only wires the same three callbacks the screen always
 * wired), the experimental GPU compositor overlay, and drag-and-drop reference-image import.
 * Factored out of [EditorScreen] purely so the tabletop split layout and the normal
 * single-column layout can both host it without duplicating this AndroidView/interop and
 * drag-and-drop wiring in two places -- a photo dragged in from another app / a split-screen
 * window drops straight onto whichever canvas pane is on screen and becomes a reference layer
 * via [CanvasEngine.addImageLayer], the same path LayersPanel's picker already uses.
 *
 * `internal` (not `private`) so `QuickSketchScreen` in this same package can reuse this exact
 * wiring for the cover-screen quick-capture flow too, instead of a third copy of it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CanvasSurface(
    engine: CanvasEngine,
    settingsRepository: SettingsRepository,
    strokeActive: Boolean,
    onStrokeActiveChanged: (Boolean) -> Unit,
    onStrokeCommitted: () -> Unit,
    onTransformChanged: () -> Unit,
    onShapeAssistCandidate: (String) -> Unit,
    drawingViewRef: MutableState<DrawingCanvasView?>,
    glViewRef: MutableState<LayerCompositorGLView?>,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // True while a drag carrying image content is hovering over this surface -- purely a visual
    // affordance (see the dashed-border overlay below), toggled by the DragAndDropTarget's
    // onEntered/onExited/onDrop/onEnded callbacks. Local to this composable (not hoisted to
    // EditorScreen) since only one CanvasSurface is ever on screen at a time.
    var dragHighlightActive by remember { mutableStateOf(false) }

    Box(
        modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.toAndroidDragEvent().clipDescription?.hasMimeType("image/*") == true
            },
            target = remember {
                object : DragAndDropTarget {
                    override fun onEntered(event: DragAndDropEvent) {
                        dragHighlightActive = true
                    }

                    override fun onExited(event: DragAndDropEvent) {
                        dragHighlightActive = false
                    }

                    override fun onEnded(event: DragAndDropEvent) {
                        dragHighlightActive = false
                    }

                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        dragHighlightActive = false
                        val androidEvent = event.toAndroidDragEvent()
                        val clipData = androidEvent.clipData ?: return false
                        if (clipData.itemCount == 0) return false
                        // Cross-app drag content needs an explicit permission grant before this
                        // process's ContentResolver can actually read the dragged content:// Uri --
                        // without this, openInputStream below throws SecurityException instead of
                        // just failing quietly. Has to happen synchronously here, while the
                        // DragEvent is still valid, not after hopping to a coroutine.
                        (context as? android.app.Activity)?.requestDragAndDropPermissions(androidEvent)
                        val uri = clipData.getItemAt(0).uri ?: return false
                        scope.launch {
                            val bitmap = withContext(Dispatchers.IO) {
                                runCatching {
                                    context.contentResolver.openInputStream(uri)
                                        ?.use { BitmapFactory.decodeStream(it) }
                                }.getOrNull()
                            }
                            if (bitmap != null) {
                                engine.addImageLayer("Reference", bitmap)
                                onMessage("Reference image added as a new layer")
                            } else {
                                onMessage("Couldn't import the dropped image")
                            }
                        }
                        return true
                    }
                }
            },
        ),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                DrawingCanvasView(ctx).apply {
                    attachEngine(engine)
                    this.onStrokeCommitted = { onStrokeCommitted() }
                    this.onStrokeActiveChanged = { active -> onStrokeActiveChanged(active) }
                    this.onTransformChanged = { onTransformChanged() }
                    this.onShapeAssistCandidate = { label -> onShapeAssistCandidate(label) }
                    drawingViewRef.value = this
                }
            },
        )

        // Experimental GPU compositor overlay -- see LayerCompositorGLView's class doc. Only ever
        // shown when idle; DrawingCanvasView underneath keeps rendering itself regardless
        // (harmless -- while idle it isn't receiving invalidate() calls anyway), so hiding this
        // overlay by flipping the setting off instantly reveals the exact proven software path
        // with no other change needed.
        val gpuEligible = settingsRepository.experimentalGpuCompositor &&
            !strokeActive &&
            engine.layers.all { !it.visible || it.blendMode in CompositorRenderer.GPU_SUPPORTED_BLEND_MODES }
        // Reading engine.revision ties this composable's recomposition to every content-affecting
        // mutation, so the GL overlay redraws whenever the settled canvas actually changed underneath it.
        val contentRevision = engine.revision
        if (gpuEligible) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    LayerCompositorGLView(ctx).apply {
                        attach(engine) { drawingViewRef.value?.currentMatrixSnapshot() ?: android.graphics.Matrix() }
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

        // Purely a visual drop-zone affordance while an image drag hovers over this surface (see
        // the dragAndDropTarget above) -- never touches any layer content itself.
        if (dragHighlightActive) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(3.dp, MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Tool-mode dropdown (brush / fill / paint-by-number / select) -- shared between the top app bar
 *  and the tabletop-posture bottom-pane quick-access row so both stay in sync automatically. */
@Composable
private fun ToolModeSelector(engine: CanvasEngine) {
    var toolMenuOpen by remember { mutableStateOf(false) }
    val toolActive = engine.currentTool != ToolMode.BRUSH
    Box {
        IconButton(onClick = { toolMenuOpen = true }) {
            Icon(
                when (engine.currentTool) {
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
                onClick = { engine.currentTool = ToolMode.BRUSH; toolMenuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Bucket fill") },
                leadingIcon = { Icon(Icons.Filled.FormatColorFill, contentDescription = null) },
                onClick = { engine.currentTool = ToolMode.FILL; toolMenuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Paint by number") },
                leadingIcon = { Icon(Icons.Filled.LooksOne, contentDescription = null) },
                onClick = { engine.currentTool = ToolMode.PAINT_BY_NUMBER; toolMenuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Select (move pixels)") },
                leadingIcon = { Icon(Icons.Filled.CropFree, contentDescription = null) },
                onClick = { engine.currentTool = ToolMode.SELECT; toolMenuOpen = false },
            )
        }
    }
}

/** Symmetry-mode dropdown -- shared between the top app bar and the tabletop-posture bottom-pane
 *  quick-access row so both stay in sync automatically. */
@Composable
private fun SymmetryModeSelector(engine: CanvasEngine) {
    var symmetryMenuOpen by remember { mutableStateOf(false) }
    val symmetryActive = engine.symmetryMode != SymmetryMode.NONE
    Box {
        IconButton(onClick = { symmetryMenuOpen = true }) {
            Icon(
                Icons.Filled.Balance,
                contentDescription = "Symmetry",
                tint = if (symmetryActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = symmetryMenuOpen, onDismissRequest = { symmetryMenuOpen = false }) {
            SymmetryMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = { engine.symmetryMode = mode; symmetryMenuOpen = false },
                )
            }
        }
    }
}
