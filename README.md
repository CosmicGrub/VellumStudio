# Vellum Studio

A high-fidelity S Pen drawing app for the Galaxy Tab S9 FE, with a Wi-Fi bridge to a PC companion.

```
VellumStudio/
├── tablet-app/       Android app (Kotlin + Jetpack Compose + a custom low-latency drawing View)
├── pc-companion/      Windows companion app (WPF, .NET) — LAN project browser/downloader
├── PC_CONNECTION.md   What the PC bridge does today vs. what's deliberately not built, and why
└── README.md          This file
```

## Status

- **tablet-app**: feature-complete first pass — pressure/tilt-sensitive brush engine, layers with
  blend modes, bounded undo/redo, pan/zoom/rotate with palm rejection, project gallery, PNG export,
  and the LAN sync server. This was the priority and got the most attention.
- **pc-companion**: scaffolded and builds clean — LAN project browsing + zip download works; the
  Live Mirror tab is a UI placeholder. See `pc-companion/README.md`.
- **Full tablet-as-PC-display mode** (à la Astropad/Duet, S Pen driving the OS cursor with real
  pressure in apps like Photoshop): intentionally not built. It requires a signed virtual-display
  driver and a virtual HID/pen-injection driver on Windows — kernel-mode, admin-install territory
  that a coding session shouldn't do unattended. Full rationale and prior-art pointers are in
  `PC_CONNECTION.md`.

## The drawing app (tablet-app/)

Native Kotlin, not a cross-platform toolkit — chosen because S Pen pressure/tilt fidelity and
input-to-pixel latency are the whole point, and that's easiest to get right talking to
`android.view.MotionEvent` directly rather than through a compatibility layer.

- **UI chrome** (gallery, toolbars, layers panel, color picker, settings) is Jetpack Compose.
- **The canvas itself** (`canvas/DrawingCanvasView.kt`) is a plain `android.view.View`, not Compose —
  strokes rasterize straight into layer bitmaps on the same thread the touch events arrive on, with
  no Compose recomposition in the hot path. Historical batched samples (`MotionEvent.getHistoricalX`
  etc.) are all consumed, not just the latest point, so fast strokes stay smooth.
- **Brush engine** (`canvas/Brush.kt`, `StrokeRenderer.kt`): five presets (pencil, ink pen, marker,
  airbrush, eraser) built from a shared stamping model — soft radial dabs spaced along the smoothed
  path, pressure modulating size/opacity, tilt widening the dab for nib-like shading. Non-buildup
  brushes (pencil/ink) stamp into a scratch layer first so overlapping dabs within one stroke don't
  double-darken; buildup brushes (airbrush/marker) stamp straight onto the layer so repeated passes
  visibly accumulate — matching how those tools behave physically.
- **Input routing**: the S Pen (or its eraser end) exclusively owns a stroke; while it's down, all
  finger input is swallowed (palm rejection). With no stylus active, one finger pans and two fingers
  pinch-zoom-and-rotate around the pinch focal point.
- **Undo/redo** (`canvas/UndoManager.kt`) snapshots the active layer's bitmap at stroke boundaries,
  with history depth computed from canvas resolution so it can't blow the heap on a large canvas.
- **Persistence** (`model/ProjectRepository.kt`): projects live in app-private storage as one PNG per
  layer plus a JSON metadata sidecar — no runtime storage permission needed. "Export" flattens and
  writes a PNG to `Pictures/Vellum Studio` via MediaStore, explicitly, on request.

## Building & installing

```bash
cd tablet-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires the Android SDK (compileSdk 36) and JDK 17+. The wrapper is pinned to Gradle 8.13.

## PC companion (pc-companion/)

```bash
cd pc-companion
dotnet run --project VellumCompanion
```

See `pc-companion/README.md` for what it does and the honest breakdown of what a full driver-based
mode would require.
