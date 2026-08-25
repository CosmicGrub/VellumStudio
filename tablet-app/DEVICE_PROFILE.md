# Device Profile: Galaxy Tab S9 FE

This branch (`device/galaxy-tab-s9fe`) targets a Samsung Galaxy Tab S9 FE, adb serial
`R52X101MB6W`, model `SM-X518U`. Confirmed via `adb shell` against the physical unit: display
1440x2304 @ 280dpi, Android 16 (SDK 36), S Pen digitizer present (`spen_usp` feature flag), and a
`dalvik.vm.heapsize` (large-heap grant) of 512MB. `main` is already the baseline this app was
built and iteratively verified against on this exact hardware, so this branch is a fidelity check,
not a retune.

`util/DeviceCapabilities.kt` scales the undo budget and the "New Canvas" resolution ceiling off
`ActivityManager.largeMemoryClass` / `isLowRamDevice` at runtime rather than any per-device
constant. For this device's 512MB large-heap grant (not a low-RAM device, so the usable heap for
budgeting is the full 512MB), its formulas resolve to:

- `undoBudgetBytes()`: `(512 * 0.15).coerceIn(48, 512)` = 76.8MB of budget, i.e. **80,530,640
  bytes** (~76.8MB) of undo-history headroom.
- `maxSafeCanvasPixels()`: half the heap (268,435,456 bytes) divided across 7 nominal concurrent
  ARGB_8888 bitmaps at 4 bytes/pixel = **9,586,980 pixels** as the largest single-layer canvas
  considered safe to offer — roughly a 3096x3096 square, or equivalent at other aspect ratios.

These numbers were computed directly from the formulas in `DeviceCapabilities.kt` for a 512MB
heap input, not hardcoded or estimated; the file itself remains untouched and device-agnostic.

## 2026-08-23/24: merged main (Precision Input, Performance & Footprint, Power Tools)

Three feature phases plus one bugfix landed on `main` and were merged into this branch with no
conflicts (this branch still only carries the device-specific content above; everything else was
additive from `main`):

- **Pressure curve default**: new Soft/Linear/Firm presets (plus a continuous gamma slider) are
  applied at the `MotionEvent` input layer before pressure reaches `StrokeRenderer`, so they need
  no per-device tuning here. Default preset is Linear (gamma 1.0, a no-op) unless the user opens
  Settings > Pressure curve and changes it. Note: `main` shipped this with SOFT/FIRM's gamma values
  accidentally swapped (Soft behaved Firm and vice versa); that was caught and fixed same-day
  (commit `51422c5`, gamma values only, math/UI copy untouched) and this branch merges the fixed
  version directly — no separate fix needed here.
- **Frame-pacing / performance findings**: the two-finger pan/zoom `ACTION_MOVE` handler in
  `DrawingCanvasView` was allocating two fresh `FloatArray`s per frame; replaced with reused fields
  plus a bounds check. This is a general hot-path fix, not device-specific, but worth noting here
  since a 90Hz+ display (this device's panel) is exactly where per-frame GC churn would first show
  up as visible jank. OpenCV and ML Kit were both verified already lazy-loaded (no change) — both
  fire only on real user action (photo import / pose overlay), so this device's on-device-only "no
  network cost" constraint is unaffected.
- **16KB native-library page-size alignment**: investigated and documented in
  `app/build.gradle.kts` (not device-specific, but this device ships Android 16 / SDK 36, the
  platform where the 16KB page-size requirement is actually enforced, so it is directly relevant to
  this branch). Zip-level alignment is confirmed fine via `zipalign -c -v -P 16 4` against the debug
  APK. At the ELF LOAD-segment level, `libopencv_java4.so` and `libandroidx.graphics.path.so` are
  already 16384-byte aligned; the one remaining 4096-byte-aligned library is ML Kit's closed-source
  `libxeno_native.so` prebuilt (pose-detection-accurate:17.0.0), which has no newer stable release
  to pin to — left alone and documented in-line rather than worked around.
- **Keyboard shortcuts / drag-and-drop / print presets** (Power Tools phase): all UI-layer
  additions to `EditorScreen`, none device-specific. Worth a real check on this device specifically
  since a Tab S9 FE is more likely than other test devices to have a physical keyboard cover
  attached in practice — verified on-device this session, see below.

## 2026-08-24: merged main (reference-image drag-and-drop fix)

Commit `ab284ba` landed on `main` and was merged into this branch with no conflicts, touching only
`EditorScreen.kt` (91 insertions / 66 deletions, no hardened canvas/touch-routing files involved).
Root cause and fix are UI-layer and device-agnostic: Compose's `Modifier.dragAndDropTarget` on the
canvas `Box` never received platform drag events because a real `android.view.View`
(`DrawingCanvasView`, plus the optional GL compositor overlay) fully overlaps it in the native View
hierarchy and intercepts `ACTION_DRAG_STARTED` first. Fix moves drag handling to a single
`View.OnDragListener` (`referenceImageDragListener`) attached directly to both AndroidViews.

On-device verification this session (`R52X101MB6W`): `:app:compileDebugKotlin` and
`:app:assembleDebug` both succeed, the merged APK installs over the existing one, and the app
launches and navigates cleanly between the canvas gallery and the Editor screen (confirmed via
`adb exec-out screencap`) with zero `AndroidRuntime`/`FATAL EXCEPTION` logcat entries across the
whole session. Did **not** obtain a live end-to-end confirmation of an actual cross-app drag
producing a new reference layer: this device has other personal apps installed, and blind
recents/app-switch navigation (`KEYCODE_APP_SWITCH`) proved unreliable for staging split-screen
here — it surfaced unrelated foreground apps unpredictably rather than opening an overview grid, so
further blind UI automation down that path was abandoned rather than risk poking around apps
outside this task's scope. This matches the prior phase's own documented finding that Samsung's
real drag gesture is highly non-deterministic under synthetic/automated touch on this device family.
Final confirmation of the live drop (reference layer appearing in LayersPanel + Snackbar) still
needs a human to drag a real photo onto the canvas by hand.

## 2026-08-25: merged main (The Conservation Lab: six phases culminating in Academy content-as-data)

Six commits landed on `main` since the last merge (`54e652b`..`1c93324`) -- a real JVM unit test
module, a durable on-device diagnostic log, project file schema versioning/migration, an Android CI
workflow plus release minification, Macrobenchmark tooling, and the Academy content-as-data format
with a PhotoConverter golden-master fixture -- and were merged into this
branch (`46b9fcf`). One real conflict, in `SettingsScreen.kt`: this branch and `main` had each
independently fixed the same "Settings screen unscrollable" bug (device-branch commit `e17db8a`
vs. `main`'s `9deb321`, both wrapping the root `Column` in `verticalScroll`), differing only in
whether `.padding(24.dp)` was applied before or after `.verticalScroll(...)`. Took `main`'s version
whole -- it carries an explanatory code comment about why the fix is in scope (the gap predates the
Diagnostics card that commit was actually adding) and keeps this branch a thin diff on `main` rather
than a competing implementation of the same fix. No other conflicts.

On-device verification this session (`R52X101MB6W`):

- **Tests**: `:app:testDebugUnitTest` run fresh (`--rerun-tasks`, all 24 tasks executed): 64/64 tests
  pass, 0 failures/errors across all 10 test classes -- matches the count from `main`'s own
  verification pass exactly.
- **Build**: `:app:assembleDebug` and `:app:assembleRelease` both succeed (88 tasks, 39 executed + 20
  from cache + 29 up-to-date). `minifyReleaseWithR8` and `lintVitalRelease` both completed cleanly,
  confirming Phase 4's R8 minification config still holds on this branch's merged tree.
- **Install**: force-stopped the existing install, then `adb install -r` the new debug APK over it;
  launched cleanly, `MainActivity` resumed, zero `AndroidRuntime`/`FATAL EXCEPTION`/`Exception`
  entries in the app's own pid-filtered logcat for the whole session.
- **Gallery**: all 8 real projects load with correct thumbnails/dates (The Starry Night,
  The_Milkmaid_Test, The Great Wave off, Spiral Bloom, Interlocking Triangle, The Anatomy, Lotus
  Mandala, 2 Untitled).
- **Editor / schema versioning**: opened "The Starry Night" -- full drawing content intact, both
  real layers present (locked "Line Art" + "Coloring") with working opacity sliders. Confirms
  Phase 3's schema migration (already verified once on `main`, now re-verified after merging into
  this branch) did not corrupt this device's real on-disk project data.
- **Settings > Pressure curve / Diagnostics**: both cards render past the merged scroll fix. Soft
  preset shows gamma 0.55 (<1.0), confirming the gamma-inversion fix (`51422c5`) is still intact on
  this branch. Diagnostics card shows a live on-device event log (a real timestamped
  `[Lifecycle] App started (samsung SM-X518U, Android 16 (API 36), Vellum Studio 0.1.0)` entry from
  this exact session), confirming the durable diagnostic log works end-to-end here too.
- **Academy / content-as-data migration**: opened "Drawing From Your Own Photos" (the course Phase
  6 migrated to the new JSON format) -- course detail shows 3 lessons / "Taught by Rowan" correctly,
  and Lesson 1 ("Turning a Photo Into a Coloring Page") renders its Heading, Paragraph, BulletList,
  and Tip blocks correctly from the JSON asset, including em-dashes and the arrow (->) glyph.
- **Known pre-existing/out-of-scope item, re-confirmed, not a regression**: the debug-build "Android
  App Compatibility" 16 KB page-alignment dialog (same four libraries as previously documented)
  reappears on first launch after install; already tracked above and on `main`.

Only the `SettingsScreen.kt` scroll-order conflict needed a judgment call; everything else merged,
built, and ran clean.
