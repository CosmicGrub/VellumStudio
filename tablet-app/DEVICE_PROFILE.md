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
