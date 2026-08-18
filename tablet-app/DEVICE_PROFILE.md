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
