# Device Profile: Galaxy Z Fold5 (device/galaxy-z-fold5)

Target device for this branch: Samsung Galaxy Z Fold5, model SM-F946U, adb serial `RFCW80CK2RW`.

## Hardware facts (measured on-device)

- **Cover (outer) screen:** 904 x 2316 px @ 420dpi (no digitizer -- S Pen cannot draw here, hardware
  limitation, not app-side).
- **Main (inner, foldable) screen:** 1812 x 2176 px @ 420dpi.
- **OS:** Android 16 / SDK 36.
- **S Pen support:** same digitizer feature flag as the Galaxy Tab S9 FE
  (`com.sec.feature.spen_usp=75`, confirmed via `adb shell pm list features`) -- no garaged pen, but
  the digitizer supports the separately-sold S Pen Fold Edition on the main screen only.
- **Large-heap grant:** `dalvik.vm.heapsize` = 512m, same as the Tab. `util/DeviceCapabilities.kt`'s
  existing runtime scaling (off `ActivityManager.largeMemoryClass` / `isLowRamDevice`) already
  handles this correctly with zero device-specific changes.
- **Fold-state control:** `adb -s RFCW80CK2RW shell cmd device_state state <id>` --
  `0` = CLOSED, `2` = HALF_OPENED, `3` = OPENED, `reset` returns control to the physical hinge
  sensor. Always `reset` when done forcing a state.

## Input model

Drawing stays **stylus-exclusive** on this device too, unchanged from the Tab branch and from
`main` -- this branch does not add a finger-drawing fallback. See `canvas/DrawingCanvasView`'s
class doc for the exact model (stylus/eraser owns the stroke; 1-finger pans, 2-finger
pinch-zoom-rotates; palm rejection swallows finger input during an active stylus stroke). The cover
screen has no digitizer at all, so while closed the app is necessarily a browsing/navigation
surface (Gallery, Settings, Academy, etc.) -- actual drawing only ever happens on the unfolded main
screen, the same as it always required a stylus-capable surface.

## Fold-aware layout (`util/FoldState.kt`, wired into `ui/editor/EditorScreen.kt`)

`rememberFoldState()` observes `androidx.window.layout.WindowInfoTracker.windowLayoutInfo(activity)`
and classifies the current posture into a `FoldPosture`:

| Posture | Condition | Editor layout |
|---|---|---|
| `FLAT` | No active fold (fully open, or a non-tabletop device) | Normal single-column: canvas on top, `BrushBar` pinned at the bottom |
| `HALF_OPENED_TABLETOP` | `HALF_OPENED` + `HORIZONTAL` hinge (propped up like a laptop) | Split into two stacked panes around the hinge: canvas in the top pane, `BrushBar` + a quick Tool/Symmetry access row in the bottom pane, with a `Spacer` sized to the hinge's own bounds so nothing draws under the crease |
| `HALF_OPENED_OTHER` | `HALF_OPENED` + `VERTICAL` hinge (book held ajar) | Same as `FLAT` -- not worth a dedicated layout |
| `NO_FOLD_FEATURE` | Cover screen, or any non-foldable-aware display | Same as `FLAT` |

This is purely additive UI composition on top of the existing single-column layout -- it never
touches `DrawingCanvasView`'s input routing, `StrokeRenderer`, `BrushStampCache`, or the per-dab
stamping loop in `CanvasEngine`.

### Verified live on the physical device

- **CLOSED (cover screen):** Gallery renders correctly at the narrow ~387dp logical width
  (`GridCells.Adaptive` reflows as expected). The Editor's `TopAppBar` needed a real fix here (see
  below) -- otherwise unaffected, single-column layout.
- **OPENED (main screen, fully unfolded):** Gallery and Editor both render as the normal
  single-column layout, unchanged in appearance from before this branch's changes.
- **HALF_OPENED (forced via `device_state`):** the physical hinge on this unit, in its current
  resting rotation (portrait), reports a **VERTICAL** hinge orientation -- i.e. `HALF_OPENED_OTHER`,
  confirmed directly via a temporary on-device debug readout of `rememberFoldState()`'s output
  (`foldState=HALF_OPENED_OTHER hinge=null`). The Editor correctly rendered the plain single-column
  layout in this state, per the "treat like FLAT" rule above. The `HALF_OPENED_TABLETOP` (horizontal
  hinge) split-pane path was **not observed live** -- `device_state` only forces the fold *state*,
  not the device's physical *rotation*, and the physical device cannot be remotely rotated into the
  landscape/propped-up orientation that would produce a horizontal hinge. The split-pane path was
  verified by direct code review and successful compilation only.

## Narrow-cover-screen fixes (found via the required visual spot-check, not fold-posture-specific)

Two pre-existing layout issues were found while actually looking at the app on the real 904px-wide
cover screen (not assumed) and fixed, since they affect any sufficiently narrow display, not just
this device:

1. **`ui/gallery/GalleryScreen.kt` -- New Canvas dialog's canvas-size chips.** A fixed 2-per-row
   `chunked(2)` grid squeezed long preset labels (e.g. "Tablet Screen · 1440×2304") into a half-width
   slot, wrapping the text into an unreadable single-word column. Switched to `FlowRow` so chips pack
   as many per line as actually fit and wrap individually otherwise. No-op on wide screens where
   every chip already fits two-per-row.
2. **`ui/editor/EditorScreen.kt` -- Editor `TopAppBar` icon overflow.** Material3's `TopAppBar`
   right-aligns the `actions` row at `x = barWidth - actionsWidth`, with no awareness of the
   navigation icon's own width. With 8 action icons, their combined natural width is close to the
   *entire* cover-screen bar width, so that formula landed the actions row directly on top of the
   Back button and squeezed the title to 0dp (both visually confirmed via a zoomed screenshot and a
   `uiautomator` bounds dump). Fixed by capping the actions row's width relative to
   `LocalConfiguration.screenWidthDp` and wrapping it in `horizontalScroll` so every icon (Undo,
   Redo, Tools, color swatch, Symmetry, Fit to screen, Layers, Export) stays reachable via a swipe.
   The cap is calibrated to never bind on any screen wide enough for the icons to fit already (every
   tablet/foldable-main-screen posture this app targets), so it's a zero-behavior-change on those.

## Known limitations

- The tabletop split-pane layout's exact visual appearance was not observed live on this unit,
  for the physical-rotation reason above -- only its single-column fallback path (`HALF_OPENED_OTHER`)
  was. Re-verify visually if this device is ever physically propped up in landscape with a future
  test pass.

## Merge: `main` -> `device/galaxy-z-fold5` (2026-08-25, "The Conservation Lab")

Merged origin/main (through commit 1c93324 -- JVM test module, durable diagnostic log, project
schema versioning/migration, Android CI + release minification, Macrobenchmark tooling, Academy
content-as-data + PhotoConverter golden-master fixture) into this branch. Three real conflicts,
all in files this branch had already restructured for the fold-aware layout:

1. **`ui/gallery/GalleryScreen.kt`** -- both sides touched the FAB: this branch made it
   compact-width-conditional (Quick Sketch vs. New Canvas), main added a TalkBack
   `contentDescription` fix for the FAB's icon+text not merging into an accessible label.
   Resolved by keeping this branch's conditional and applying main's accessibility fix to
   *both* FAB variants, not just the one main happened to touch -- both are the same
   `ExtendedFloatingActionButton` shape with the same underlying gap.
2. **`ui/settings/SettingsScreen.kt`** -- both sides independently fixed the exact same
   pre-existing "Column has no vertical scroll" bug (this branch while narrowing the screen for
   the cover display; main while adding the Diagnostics card), just with the `.padding()` /
   `.verticalScroll()` modifier order swapped. Kept main's order (scroll wraps the Column, padding
   applied inside it, so the gutter is part of the scrollable extent) since it's the more correct
   Compose idiom; functionally both fixed the same gap.
3. **`ui/editor/EditorScreen.kt`** -- looked like a large conflict but was a diff-alignment
   artifact: main's only real change here (since this branch's last sync) was one line,
   `contentDescription = "Drawing canvas"`, added to the `DrawingCanvasView.apply {}` block for
   the same TalkBack-gap reason as above. This branch had since extracted that exact block into
   the shared `CanvasSurface` composable (used by both `EditorScreen`'s tabletop-split path and
   `QuickSketchScreen`), so the merge tried to reconcile main's pre-extraction snapshot against
   this branch's post-extraction call site. Resolved by discarding main's raw
   pre-extraction block entirely (verified byte-identical to this branch's pre-merge HEAD once
   the markers were removed) and hand-porting the one real line -- `contentDescription =
   "Drawing canvas"` plus its explanatory comment -- into `CanvasSurface`'s own
   `DrawingCanvasView.apply {}`, so the fix now applies in every posture that reuses this shared
   composable, not just the single call site main originally touched.

All other files auto-merged cleanly, including `app/build.gradle.kts` (main's new test/benchmark
dependencies and this branch's `androidx.window` dependency both landed correctly) and
`AndroidManifest.xml` (main's new `FileProvider` for diagnostic-log export alongside this
branch's existing `faketouch`/banner entries).

**Verification performed:** `:app:testDebugUnitTest` run fresh (`--rerun-tasks`): 64/64 tests
pass, 0 failures, across the same 10 test classes as `main`. `:app:assembleDebug` and
`:app:assembleRelease` both `BUILD SUCCESSFUL` (release exercises R8 minification with the
existing ProGuard rules, zero new failures). Debug APK installed cleanly on RFCW80CK2RW
(`adb install -r -d`, after `am force-stop`). Launch was attempted and logcat was scanned for
`AndroidRuntime`/`FATAL EXCEPTION` across the launch window: the process started, drew its first
frame (`ActivityTaskManager: Displayed ... +948ms`), and is alive with no crash of any kind.

**Known limitation, this pass could not resolve:** RFCW80CK2RW was behind a secure lock screen
(PIN/pattern/biometric -- confirmed via `locksettings get-disabled`=false and
`dumpsys trust` showing `deviceLocked=1`) for this entire session, including after multiple
legitimate wake attempts (`KEYCODE_WAKEUP`, plus a swipe-up to dismiss the ambient-display/dream
layer, which only revealed the actual lock screen underneath). Per this project's hard rule
against ever bypassing a secure lock screen, no PIN/pattern/biometric entry was attempted. This
means the on-device visual regression pass the task called for -- an existing real project
reopening correctly, the Settings > Diagnostics and Pressure Curve cards rendering, the migrated
Academy course rendering (including its tabletop flex-mode layout), and a walkthrough across
cover/open/forced-tabletop postures -- could **not** be visually confirmed this session, only
code-reviewed at the merge-conflict level (above) and confirmed non-crashing via logcat. This is
a device-access limitation, not a code defect: the build, tests, and install all succeeded, and
the one thing observed on-device (the app launching and drawing its first frame before the
keyguard stopped it) showed no crash. Re-run the visual posture walkthrough once the device is
physically unlocked.

## Follow-up visual regression pass (2026-08-25, post-c3e7ab0)

RFCW80CK2RW was unlocked and reachable this session, closing out the "still needs visual
confirmation" gap noted above. No code changes were needed -- everything below was confirmed by
actually looking at the running app, not by re-reading the diff.

- Force-stopped and cold-relaunched `com.vellum.studio` (`am force-stop` + `am start -n
  com.vellum.studio/.MainActivity`); confirmed via `dumpsys activity activities` that the new
  process became `topResumedActivity` before screenshotting anything, so nothing below is a stale
  process.
- **Gallery:** screenshotted (three scroll positions). Real pre-existing projects render with
  correct thumbnails alongside the blank `Untitled` scratch projects -- most notably **"Lotus
  Mandala"** (Aug 24, 2026), whose grid thumbnail is the actual mandala line-art, not a
  placeholder.
- **Editor (real project, not blank):** tapped into "Lotus Mandala" from the Gallery. It opened
  with its full mandala line-art intact and pixel-matching the Gallery thumbnail, confirming the
  Phase 3 project-file schema versioning/migration did not corrupt pre-existing on-disk projects.
  Toolbar (Pencil/Ink Pen/Fineliner/Marker/Highlighter, Size/Opacity, undo/redo) rendered normally.
- **Settings:** screenshotted end to end. Both previously-at-risk cards are present and reachable
  by scroll, confirming the earlier "Column has no vertical scroll" merge conflict (resolved
  during the c3e7ab0 merge, see above) is still fixed on this branch/device:
  - **Pressure curve** -- Soft/Linear/Firm chips (Firm selected) + a working Gamma slider (reading
    1.80).
  - **Diagnostics** -- on-device event log viewer showing real timestamped entries, a live
    "Current size: 615 B" readout, and Export/Clear buttons.
- **Academy:** opened the course list, scrolled to and opened **"Drawing From Your Own Photos"**
  (the course migrated to the new JSON content-as-data format). Course-detail screen rendered
  correctly (description, instructor card, 3 numbered lessons). Opened Lesson 1 ("Turning a Photo
  Into a Coloring Page") and confirmed all four block types render: Heading, Paragraph, BulletList
  (with a working "Mark lesson complete" button after it), and a visually distinct Tip callout
  block. Special characters survived the migration intact -- em dashes throughout ("a photo you
  took yourself — a pet...") and the arrow glyph in the bulleted steps ("Coloring Book → photo
  icon (top bar) → pick a photo from your library").
- **Tabletop/flex-mode layout:** `dumpsys device_state` showed `mCommittedState=CLOSED` (physical
  hinge sensor reading) at both the start and end of this session -- the device was genuinely
  folded shut throughout, not flat/open and not HALF_OPENED. Per this branch's confirmed
  can't-override-the-real-sensor-via-`device_state` behavior, this couldn't be forced, and there
  was no way to physically re-fold the unit mid-session. All of the screenshots above (Gallery,
  Editor, Settings, Academy) were therefore taken on the narrow 904x2316 cover screen in the
  existing single-column `FLAT`-equivalent (`NO_FOLD_FEATURE`) layout, which rendered correctly
  throughout. **The `HALF_OPENED_TABLETOP` split-pane layout (`TabletopLessonLayout` /
  canvas-up-top-controls-below) still could not be visually observed this session** -- this
  remains the one honestly-unverified item from the "Known limitations" section above, unchanged
  by this pass.
- **Logcat:** `adb logcat -d` scanned for the full buffer. Zero `FATAL EXCEPTION` lines anywhere
  in the log. The only `AndroidRuntime: VM exiting with result code -1` lines belong to an
  unrelated third-party app (`com.tmobile.tuesdays` / `.vpnservice`, an intentional
  `System.exit(-1)`), not `com.vellum.studio`. No crashes, ANRs, or exceptions tied to
  `com.vellum.studio` anywhere in the session log.

No bugs found; no code changes made this pass.
