# Vellum Studio architecture

This is the map for someone (or some agent) about to change the code: the rules that are not up for
negotiation and why they exist, where things live, how a project gets to disk and how undo works, and how
to run the tests. Facts here were checked against the source at the time of writing; when the code and this
file disagree, the code is right and this file is the bug.

## The five hard constraints

These are decisions, not accidents. Each one has a reason that is easy to forget six months later and
expensive to rediscover. Breaking one is an owner decision, never a side effect of a refactor.

### 1. All AI/ML runs on the device and costs nothing

There is no backend, no account and no per-use bill, and the drawings are the user's own. So every "smart"
feature is either classical computer vision, a heuristic, or a model that ships inside the APK:

- Photo to coloring page and paint by number: OpenCV (`canvas/PhotoConverter.kt`, `RegionAnalyzer.kt`).
- Pose reference overlay: ML Kit `pose-detection-accurate` in its **bundled** flavour (the model is in the
  AAR). The Play-Services variant downloads its model on first use over the network, which would break both
  "works offline" and "zero network calls", so it is deliberately not used.
- Smart Shape Assist: plain geometry over the stroke's own points (`canvas/ShapeAssist.kt`), no model.

Consequences: never add a cloud API, a hosted model, telemetry or a "download the model" step. The
`INTERNET` permission exists only for the LAN sync server.

### 2. Drawing input is stylus-exclusive

Only the S Pen (or its eraser end) makes marks. Fingers pan, pinch-zoom and rotate, and while a stylus
stroke is active every finger movement is ignored (palm rejection). Why: on a tablet a resting hand always
touches the glass, and the whole point of the app is pressure and tilt from the pen; if fingers could draw,
every stray palm would leave a mark and every two-finger gesture would start a stroke. The routing is in
`canvas/DrawingCanvasView.onTouchEvent`, keyed on `MotionEvent.getToolType`. Fingers that land during a stroke
are still tracked (Android never re-sends ACTION_DOWN for a pointer already down) but have no effect until
the stroke ends. Do not add a "draw with finger" mode, an input-type-agnostic path, or anything that lets a
finger reach `startStroke`.

### 3. The dab loop is frozen: never edit `StrokeRenderer.kt` or `BrushStampCache.kt`

They are the inner loop every stroke runs through: stamping soft dabs along the smoothed path, and the
cache of pre-rendered dab bitmaps. Each dab is a single zero-allocation `drawBitmap` on the touch thread, and
a stroke lays down hundreds of them, so any per-dab allocation or extra work shows up as stutter under a
moving pen. Pen feel and input-to-pixel latency were built and measured against exactly this code, and a
small-looking change can regress them in a way no unit test notices. So new capability wraps or composes around it (a new brush is data
plus, at most, a layer around the renderer; a new effect is a pass before or after it), it does not edit it.

Enforced mechanically: `scripts/frozen-files.sha256` records the SHA-256 of both files (computed over the
source text with carriage returns removed, so CRLF checkouts hash the same), `scripts/check-frozen.sh`
verifies it, and it is step 1 of `scripts/verify.sh` and of the pre-push hook. If the owner approves a
change: edit the file, run `bash scripts/check-frozen.sh --update`, and commit the manifest in the same
commit with the approval stated in the message.

### 4. Authored content is strict, on-disk user data is lenient

Two kinds of JSON, deliberately opposite:

- **Authored, bundled content** (`assets/academy/*.json`, decoded by `academy/AcademyContentLoader.kt`) uses
  kotlinx.serialization with `ignoreUnknownKeys = false`. It ships in the APK and is reviewed before it
  ships, so a typo'd key or a missing field should fail a build or a test loudly, never silently decode into
  something the author did not intend.
- **On-disk user data** (`metadata.json`, palettes, custom brushes, photo templates, Academy progress; the
  repositories in `model/` plus `academy/AcademyProgressRepository.kt`) uses `ignoreUnknownKeys = true` and
  `coerceInputValues = true`. Those files may have been written by an older or newer build (the device
  branches lag one another) or been damaged, and opening them must never crash the app.

The lenient side has a matching safety rule: a project whose `schemaVersion` is newer than this build
understands is listed read-only and never saved over, because dropping unknown keys and then saving would
destroy the newer build's data. Keep the two `Json` configurations as they are.

### 5. OpenCV stays on the 4.x line

`org.opencv:opencv` is pinned to 4.14.0. OpenCV 5.0 reorganised the Java API (for example
`Imgproc.arcLength` moved to a new `org.opencv.geometry.Geometry` class), which breaks `PhotoConverter`'s
contour tracing written against the 4.x API. Moving to 5.x is a migration with its own verification, not a
version bump. Also relevant: the OpenCV JNI library resolves `org.opencv.**` classes and fields by name from
native code, so `proguard-rules.pro` keeps that whole package, and `scripts/verify_apk.py` asserts from the
built release APK that `org.opencv.core.Mat` and its `nativeObj` field survived R8.

## Package map

Source root: `tablet-app/app/src/main/java/com/vellum/studio/`.

| Package | Role |
|---|---|
| (root) | `MainActivity`, `VellumApp` (process-wide singletons, startup work such as the trash purge) |
| `canvas/` | The drawing engine. `DrawingCanvasView` is a plain `android.view.View` (not Compose) that owns touch routing and rasterises strokes into layer bitmaps on the touch thread. `CanvasEngine` holds the layer stack, tool state and undo. `Brush`/`BrushPresets` (20 presets, data), `StrokeRenderer` + `BrushStampCache` (**frozen**), `Layer`, `LayerFlattener`, `UndoManager`, `FloodFillTool`, `ShapeAssist`, `SymmetryMode`, `PressureCurve`, `PaperTexture`, `PhotoConverter`, `RegionAnalyzer`, `PoseOverlay` |
| `canvas/gl/` | Experimental OpenGL layer compositor (off by default; only affects the idle view) |
| `model/` | Persistence. `ProjectRepository` (files on disk, trash, export), `SaveCoordinator`, `EditorAutosaver`, `Project` (`ProjectMeta`, `LayerMeta`), `ProjectSchemaMigrator`, `LoadResult`, and the palette / custom-brush / photo-template / settings repositories |
| `academy/` | Courses: the domain model, `AcademyContentLoader` (strict JSON to model), `DemoPlayer` and `DiagramRenderer` (animated stroke demos and drawn diagrams), progress repository |
| `art/` | Coloring-book pages (procedural drawings plus traced masterwork line art) |
| `network/` | LAN sync: `SyncServer` (NanoHTTPD), `PairingGuard` (the PIN and lockout), `LiveCanvasBridge`, `NetworkUtils` |
| `ui/` | Jetpack Compose screens and chrome: `editor/`, `gallery/`, `academy/`, `coloringbook/`, `connect/`, `settings/`, `colorpicker/`, `navigation/` (nav graph and double-tap guards), `theme/` |
| `util/` | `DurableFile` (crash-safe writes), `JsonFileStore`, bitmap caches, `DiagnosticLog`, `DeviceCapabilities` (memory-scaled budgets), printing, `RecoveryNotices` |

Layering rule of thumb: `ui/` depends on `model/`, `canvas/`, `academy/`; `canvas/` knows nothing about `ui/`;
`network/` reaches the editor only through `LiveCanvasBridge`; `util/` depends on nothing above it.

The PC companion is a separate .NET WPF app in `pc-companion/` that talks to `network/SyncServer` over HTTP
(see `PC_CONNECTION.md`).

## The save pipeline

Goal: whatever happens, whether the process is killed, the battery dies, the disk fills or two saves race,
the user ends up with either the previous complete project or the new complete project, and never loses
work to a silent overwrite.

- **`util/DurableFile`** is the one primitive every "user data lives in this file" write goes through. It
  writes `<name>.tmp` in the same directory, `fsync`s it, optionally rotates the current good file to
  `<name>.bak` (only if the current file validates), then atomically renames the tmp over the target. At
  every instant the target is a complete old or complete new file; a failure deletes the tmp and rethrows.
- **`model/ProjectRepository`** lays a project out as `metadata.json` + `layers/<id>.png` + `thumbnail.png`.
  A save writes the *changed* layer PNGs first, then `metadata.json` as the **commit point**, and only then
  deletes layer files the new metadata no longer references, so a kill at any instant never leaves metadata
  pointing at a missing PNG. A layer PNG that cannot be decoded is set aside as `<id>.png.corrupt` rather
  than replaced by a blank bitmap that the next save would write over the only surviving bytes.
- **`model/SaveCoordinator`** is the app-scoped single writer. At most one save per project runs at a time
  (a per-project mutex that load, delete, rename and export also take); work runs on the coordinator's own
  app-lifetime scope, so a caller that goes away (Back, composition left) stops waiting but the save still
  completes; requests coalesce (at most one save waits behind the running one, and a newer request
  supersedes it); and every failure, including IO errors, disk full and OOM, becomes a `SaveOutcome`
  instead of an exception thrown at a fire-and-forget caller. Layer pixels are snapshotted on the main
  thread at capture time, so the IO side never reads a live bitmap.
- **`model/EditorAutosaver`** decides *when* to save for the open project: 2 s after the last edit
  (`CanvasEngine.revision` changes), a 30 s cap so continuous drawing still saves, the old
  every-6th-stroke counter as a third backstop, and immediate flushes on `ON_STOP`, trim-memory
  `UI_HIDDEN`, Back and editor dispose. A flush with nothing dirty does no work. A failed save leaves the
  edits dirty (the editor shows the failure) and the next trigger retries; there is deliberately no retry
  loop against a full disk.
- **Opening** returns `LoadResult` (`Ok`, `NotFound`, `TooNew`, `Unreadable`), never throws for a project it
  cannot open, and a failed open creates no engine, so nothing can autosave over the project on disk.
- **Trash**: deleting a project moves its folder to `.trash/<id>__<timestamp>`; it is restorable and purged
  after 30 days. Nothing in the delete path is a hard delete.

## The undo model

`canvas/UndoManager` keeps one linear history, so Ctrl+Z always undoes the most recent thing the user did,
whatever kind it was. Entries are a sealed `UndoStep`: `PixelEdit` (a before and after bitmap of one layer,
for a stroke, fill or selection move, so undo is a cheap bitmap swap rather than a re-simulation),
`LayerRemove`, `LayerInsert`, `LayerMove` and `LayerPropsEdit` (opacity, visibility, blend mode, lock; a few
bytes). Structural steps reach the layer stack through the small `LayerStackHost` interface, so the manager
stays unit-testable against a fake.

- **Bounded by bytes, not step count**: a pixel step pins two full ARGB_8888 canvases, a removed-layer step
  one, a property step almost nothing. Oldest steps are evicted first while the undo stack is over the
  budget (`DeviceCapabilities.undoBudgetBytes()`, roughly 15% of the declared heap, clamped), except the
  newest 6 (`MIN_UNDO_STEPS`) always survive, and `MAX_UNDO_STEPS` (60) caps the count.
- **Bitmap ownership** is the rule to read before touching layer lifetimes: a layer in the engine's stack is
  owned by the engine and is never recycled except by `recycleAll`; a layer removed from the stack is owned
  by exactly one history step and recycled only when that step is discarded (evicted, dropped from the redo
  stack by a fresh edit, or cleared). So a save that walks the live layers on the main thread can never see a
  detached or recycled bitmap. Every mutation of the history happens on the main thread.
- The `beginStroke`/`commit` facade is what `DrawingCanvasView` uses, which is how structural undo was added
  without touching the dab loop.

## Local verification

CI is not available (the workflow is billing-blocked and never built the release variant), so
`scripts/verify.sh` / `verify.ps1` is the gate: frozen files, the gate's own self-tests, README counts,
unit tests, lint, then an R8 release build with assertions on the built APK (OpenCV, ML Kit and the
serializers not renamed, 16 KB alignment with `libxeno_native.so` as a recorded exception, a non-debug signer
when `keystore.properties` exists, and the permission and component allow-list in
`scripts/manifest-allowlist.json`). `--fast` runs the first four steps and is what the opt-in pre-push hook
(`bash scripts/install-hooks.sh`) runs. If a check needs changing because the app legitimately changed (a
new permission, a new required class), change the allow-list or list in `scripts/verify_apk.py` in the same
commit and say why.

## Running the tests

All commands from `tablet-app/`.

| What | Command |
|---|---|
| All JVM and Robolectric unit tests | `./gradlew :app:testDebugUnitTest` |
| One class or method | `./gradlew :app:testDebugUnitTest --tests "com.vellum.studio.canvas.UndoManagerTest"` |
| Lint (new findings fail; old ones are grandfathered in `app/lint-baseline.xml`) | `./gradlew :app:lint` (regenerate the baseline with `:app:updateLintBaseline`, deliberately) |
| The verification gate's own tests | `python -m unittest discover -s scripts/tests` (from the repo root) |
| Instrumented test (real device or emulator) | `./gradlew :app:connectedDebugAndroidTest` |
| Macrobenchmarks (device) | see `benchmark-baseline.md` |

Conventions and gotchas:

- Unit tests live in `app/src/test`. Tests that render real pixels use Robolectric with
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)`; see `StrokePreviewCompositorTest`, `ProjectDurabilityTest`
  and `DiagramRendererTest` for the pattern. The first run downloads Robolectric's android-all jars.
- **Every test class runs in its own JVM** (`forkEvery = 1` in `app/build.gradle.kts`). The Compose UI tests
  hang ("Compose did not get idle") when run late in a JVM shared with hundreds of other Robolectric tests;
  isolating classes is the remedy that was measured to work. The cost is that the full suite takes several
  minutes, so use `--tests` while iterating and run the full suite once before you finish.
- The tests write temp files. If your system drive is short of space, point `TMP`, `TEMP` and `TMPDIR` at
  another disk before running Gradle.
- A `var` delegated by `mutableStateOf` cannot carry a custom `set()` with a backing field; use a private
  backing `MutableState` with explicit accessors (`SettingsRepository` shows the pattern).
- `app/src/androidTest` currently holds one test, the photo-converter golden master, which needs a live
  OpenCV native call chain and so cannot run on the JVM. The fixture photos are shared with the JVM tests
  through `app/build.gradle.kts` rather than duplicated.
