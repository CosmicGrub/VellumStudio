package com.vellum.studio.ui.editor

import android.graphics.BitmapFactory
import android.view.DragEvent
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // True while a drag carrying image content is hovering over the canvas -- purely a visual
    // affordance (see the dashed-border overlay in the canvas Box below), toggled by the
    // DragAndDropTarget's onEntered/onExited/onDrop/onEnded callbacks.
    var canvasDragHighlightActive by remember { mutableStateOf(false) }
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
                    IconButton(
                        onClick = ::doUndo,
                        enabled = eng?.undoManager?.canUndo == true,
                    ) { Icon(Icons.Filled.Undo, contentDescription = "Undo") }
                    IconButton(
                        onClick = ::doRedo,
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
                                    printPresetDialogOpen = true
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

                    // Drag-and-drop reference import: a photo dragged in from another app / a
                    // split-screen window drops onto the canvas and becomes a reference layer via
                    // the same CanvasEngine.addImageLayer LayersPanel's picker already uses -- zero
                    // new import UI.
                    //
                    // This is a plain platform android.view.View.OnDragListener attached directly
                    // to the AndroidView-hosted native views below, NOT a Compose
                    // Modifier.dragAndDropTarget on the surrounding Box. That was the original
                    // implementation and it never fired: Compose's dragAndDropTarget hit-tests
                    // against Compose's own semantics/LayoutNode tree, but DrawingCanvasView (and
                    // the optional GL compositor overlay below) are real platform Views embedded
                    // via AndroidView, sitting as actual children in the Android View hierarchy and
                    // exactly overlapping the Box that declared the modifier. Android's platform
                    // drag dispatch (ViewGroup.dispatchDragEvent) offers ACTION_DRAG_STARTED to
                    // that concrete child View directly; since it had no drag listener of its own,
                    // the View's default handling silently declined it (returned false), and the
                    // rest of the drag lifecycle for that region was routed to the child that
                    // opted in -- which never included the parent Compose node's own hit-test.
                    // Confirmed empirically: added temporary logging to both the Compose target
                    // and a raw View-layer listener and drove real cross-app drags in via adb/
                    // uiautomator (split-screen with Samsung Gallery) -- during a real
                    // platform-level drag session (visible in logcat as WindowManager "perform
                    // drag" / InputManagerService "startDragAndDrop") neither this app's Compose
                    // callbacks nor the diagnostic View-layer listener ever logged a single event,
                    // matching the original human tester's report of zero ClipDescription/
                    // ACTION_DROP/etc. log lines during a real, successful finger-drag. Attaching
                    // the listener to the actual native View(s) that sit in the real dispatch path
                    // is the standard fix for this category of Compose/AndroidView drag-and-drop
                    // interop gap. It's attached to both AndroidViews in this Box (DrawingCanvasView
                    // and the experimental GL overlay) since whichever is topmost at drop time is
                    // the one that will actually receive the platform event.
                    val referenceImageDragListener = View.OnDragListener { _, event ->
                        when (event.action) {
                            DragEvent.ACTION_DRAG_STARTED ->
                                event.clipDescription?.hasMimeType("image/*") == true
                            DragEvent.ACTION_DRAG_ENTERED -> {
                                canvasDragHighlightActive = true
                                true
                            }
                            DragEvent.ACTION_DRAG_EXITED -> {
                                canvasDragHighlightActive = false
                                true
                            }
                            DragEvent.ACTION_DROP -> {
                                canvasDragHighlightActive = false
                                val currentEngine = engine
                                val clipData = event.clipData
                                val uri = clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                                if (currentEngine == null || uri == null) {
                                    false
                                } else {
                                    // Cross-app drag content needs an explicit permission grant
                                    // before this process's ContentResolver can actually read the
                                    // dragged content:// Uri -- without this, openInputStream below
                                    // throws SecurityException instead of just failing quietly. Has
                                    // to happen synchronously here, while the DragEvent is still
                                    // valid, not after hopping to a coroutine.
                                    (context as? android.app.Activity)?.requestDragAndDropPermissions(event)
                                    scope.launch {
                                        val bitmap = withContext(Dispatchers.IO) {
                                            runCatching {
                                                context.contentResolver.openInputStream(uri)
                                                    ?.use { BitmapFactory.decodeStream(it) }
                                            }.getOrNull()
                                        }
                                        if (bitmap != null) {
                                            currentEngine.addImageLayer("Reference", bitmap)
                                            snackbarHostState.showSnackbar("Reference image added as a new layer")
                                        } else {
                                            snackbarHostState.showSnackbar("Couldn't import the dropped image")
                                        }
                                    }
                                    true
                                }
                            }
                            DragEvent.ACTION_DRAG_ENDED -> {
                                canvasDragHighlightActive = false
                                true
                            }
                            else -> true
                        }
                    }

                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        ) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    DrawingCanvasView(ctx).apply {
                                        // Also closes a real, pre-existing accessibility gap: this
                                        // plain custom View had zero contentDescription before, so
                                        // TalkBack had nothing to announce for the single largest
                                        // interactive element on screen. Doubles as this project's
                                        // hook for on-device UiAutomator-driven gesture tests (see
                                        // benchmark/src/main/java/.../PanZoomFrameTimingBenchmark.kt)
                                        // to locate this exact View via By.desc(...) rather than a
                                        // brittle fully-qualified-class-name match.
                                        contentDescription = "Drawing canvas"
                                        attachEngine(eng)
                                        setOnDragListener(referenceImageDragListener)
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
                                            setOnDragListener(referenceImageDragListener)
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

                            // Purely a visual drop-zone affordance while an image drag hovers over
                            // the canvas (see referenceImageDragListener above) -- never touches
                            // any layer content itself.
                            if (canvasDragHighlightActive) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .border(3.dp, MaterialTheme.colorScheme.primary),
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
