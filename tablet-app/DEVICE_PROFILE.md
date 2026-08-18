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
