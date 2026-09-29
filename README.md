# Vellum Studio

A high-fidelity S Pen drawing app for Samsung Galaxy tablets (built and tested on the Galaxy Tab S9 FE
and the Z Fold5), with a Wi-Fi bridge to a Windows companion app. Native Kotlin, no cloud, no account,
and every "smart" feature (photo-to-line-art, pose overlay, shape snapping) runs on the device at zero
cost.

<!-- verify-docs: brush-presets=20 academy-courses=10 academy-lessons=47 -->
<!-- The line above is checked against the code by scripts/check_docs.py (part of scripts/verify.sh):
     change a count in the prose below and this marker together, or the gate fails. -->

```
VellumStudio/
├── tablet-app/        Android app (Kotlin, Jetpack Compose UI, one custom android.view.View for the canvas)
├── pc-companion/      Windows companion (WPF, .NET): browse and download projects over the LAN
├── scripts/           Local verification gate (verify.sh / verify.ps1), frozen-file guard, hooks installer
├── docs/              ARCHITECTURE.md: hard constraints, package map, save pipeline, undo model, tests
├── .githooks/         Tracked pre-push hook (opt-in, see "Verifying a change")
├── PC_CONNECTION.md   What the PC bridge does, what it protects, and what is deliberately not built
└── README.md          This file
```

Current version: **0.2.1** (versionCode 3). `minSdk` 29, `targetSdk`/`compileSdk` 36, arm64-v8a only.
Per-device work lives on `device/galaxy-tab-s9fe` and `device/galaxy-z-fold5`; `main` is the base they
are built from.

## What the app does today

**Drawing**
- **20 brush presets** in 11 categories: pencil, ink pen, fineliner, felt marker, highlighter, watercolor,
  pastel, soft airbrush, flat fill, six erasers (flat, precision, soft, hard, kneaded, fade) and four
  graffiti tools (spray can, fat-cap outline, wildstyle chisel, drip) plus a stencil. Presets are
  stamping brushes (soft dabs spaced along a smoothed path, pressure driving size and opacity, tilt
  widening the dab). You can also build and save your own brushes.
- **S Pen only draws.** Pressure, tilt and the pen's eraser end are read straight from `MotionEvent`.
  Fingers pan, pinch-zoom and rotate, and are ignored entirely while a stroke is in progress (palm
  rejection). This is a hardened invariant, see `docs/ARCHITECTURE.md`.
- **Layers** with opacity, visibility, lock, reordering and **16 blend modes** (Normal, Multiply, Screen,
  Overlay, Darken, Lighten, Color Dodge/Burn, Hard/Soft Light, Difference, Exclusion, Hue, Saturation,
  Color, Luminosity). An OpenGL layer compositor exists as an experimental setting (off by default); the
  live-stroke path is always software rendering.
- **Undo/redo** covers pixels and layer structure (add, delete, reorder, opacity, visibility, blend mode,
  lock) in one linear history, bounded by a memory budget scaled to the device.
- **Symmetry drawing** (vertical, horizontal, 4-way mirror, radial x4/x6/x8/x12), **Smart Shape Assist**
  (after a stroke that closely fits a line, ellipse, rectangle or triangle it offers to snap to it; pure
  geometry, no ML), bucket fill, paper
  texture, custom palettes and recent colors, Bluetooth-keyboard shortcuts (Ctrl+Z, Ctrl+Y, Ctrl+Shift+Z).
- **Canvas sizes** from A4 at 150 dpi (1240x1754) up to 4096x4096; the largest presets are only offered
  when the device has the memory for them.
- **Export** flattens to a PNG in `Pictures/Vellum Studio` (explicit, on request) and prints through the
  system print dialog.

**Learning and reference**
- **Academy**: 10 courses and 47 lessons (Drawing Foundations, Perspective, Shading and Light, Color
  Theory, Anatomy, Watercolor, Digital Drawing Fundamentals, Wet-on-Wet Landscapes, Graffiti Lettering,
  Drawing From Your Own Photos), taught by four original instructor personas. Lessons mix text, tips,
  drawn diagrams, animated stroke demos and masterwork references. Course content is authored JSON in
  `assets/academy/`, validated strictly at load; progress is saved locally.
- **Coloring Book**: 45 line-art pages (mandalas, geometric patterns, nature, animals, abstract, kids, and
  17 masterworks of which 11 are traced from public-domain paintings), colored with the normal tools.
- **Photo to coloring page / paint by number**: imports a photo and converts it to line art on the device
  with OpenCV (4.14), including a region analyzer for paint-by-number. Converted photos are kept as your
  own templates.
- **Pose reference overlay**: an ML Kit pose skeleton drawn over an imported reference photo, on-device
  with the model bundled in the APK (no download, no network).

**Projects**
- **Autosave** is durable: edits are saved about two seconds after you pause (at most 30 seconds while you
  draw continuously) and whenever the app is backgrounded or the editor closed, and a crash or power loss
  leaves either the previous or the new version, never a half-written one.
- **Recently deleted**: deleting a canvas moves it to a trash that is kept for 30 days, with an Undo
  snackbar and Restore.
- Projects saved by a newer build are listed read-only and never overwritten; a project that cannot be
  opened shows an error card and is never written over.

## Where your data lives

Everything is in the app's **app-specific external storage**, the directory
`/sdcard/Android/data/com.vellum.studio/files/` (it is what `Context.getExternalFilesDir(null)` returns).
It needs no storage permission, is not visible to other apps, and **is deleted when the app is
uninstalled**. Export to gallery is how work leaves it.

| Path under `files/` | What |
|---|---|
| `projects/<id>/metadata.json` (+ `.bak`) | Project metadata and layer list |
| `projects/<id>/layers/<layerId>.png` | One PNG per layer |
| `projects/<id>/thumbnail.png` | Gallery preview |
| `.trash/<id>__<timestamp>/` | Soft-deleted projects, purged after 30 days |
| `photo_templates/` | Your converted-photo coloring templates |
| `palettes.json`, `custom_brushes.json`, `academy_progress.json` | Palettes, brushes, Academy progress |

App settings (`SharedPreferences`) and the diagnostic log are in the app's private internal storage.
Exported images go to `Pictures/Vellum Studio` through MediaStore.

## LAN sync and the PC companion

The tablet can run a small HTTP server (default port 8642, started and stopped from the in-app Connect
screen) so the Windows companion in `pc-companion/` can list projects and download each as a zip
(`metadata.json`, the layer PNGs and a 512 px `thumbnail.png`). Security posture, in brief:

- Every request needs the **6-digit PIN** shown on the tablet (`X-Vellum-Pin` header). The PIN is new each
  time sync is started. **Five wrong PINs lock the session** until sync is stopped and started again.
- The server binds **only to the Wi-Fi address**, never to every interface, and **stops itself after 10
  minutes** without an authenticated request.
- It is **plain HTTP, not encrypted**: the PIN keeps other people on the network from browsing your
  canvases, but someone who can sniff the network can read the traffic and the PIN. Use it on a network
  you trust.

Run the companion with `dotnet run --project VellumCompanion` inside `pc-companion/`. Details, and why
full tablet-as-PC-display mode is deliberately not built, are in `PC_CONNECTION.md` and
`pc-companion/README.md`.

## Building

Requires JDK 17 and the Android SDK (platform 36; put `sdk.dir=...` in `tablet-app/local.properties` or set
`ANDROID_HOME`). The Gradle wrapper is pinned to 8.13. Run these from `tablet-app/`:

```bash
./gradlew :app:assembleDebug                       # debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest                   # JVM + Robolectric unit tests
./gradlew :app:lint                                # lint (existing findings are grandfathered in lint-baseline.xml)
./gradlew :app:assembleRelease                     # R8-minified release build
```

**Release signing.** A release build is signed with the key described by `tablet-app/keystore.properties`
(gitignored; it points at a keystore kept outside the repository). Without that file the build falls back
to the debug key, which is fine for checking that a change builds and survives R8 but is **not shippable**.
If the release keystore is lost, installed copies can never be updated in place, so back it up.

## Verifying a change

CI is not available, so the gate runs locally:

```bash
bash scripts/verify.sh          # full gate (several minutes)
bash scripts/verify.sh --fast   # frozen-file check, script self-tests, README counts, unit tests
```
```powershell
scripts\verify.ps1              # same, PowerShell (add -Fast for the fast subset)
```

The full gate stops at the first failure with a non-zero exit and one line saying why. It checks, in order:
the frozen dab-loop files are unchanged; the gate's own self-tests; that this README's counts match the
code; the unit tests; lint; then it builds the **release** APK and asserts from the built file that
OpenCV, ML Kit and the kotlinx.serialization serializers were not renamed by R8, that native libraries
are 16 KB aligned (with one documented exception, ML Kit's `libxeno_native.so`), that the signer is not
the Android Debug certificate (when `keystore.properties` exists), and that the merged manifest's
permissions and components match `scripts/manifest-allowlist.json`.

To run the fast subset automatically before every `git push`, opt in once per clone:

```bash
bash scripts/install-hooks.sh   # sets core.hooksPath to .githooks (shared by all worktrees of the clone)
```

The frozen files are `canvas/StrokeRenderer.kt` and `canvas/BrushStampCache.kt`: an owner decision is
required to change them, see `docs/ARCHITECTURE.md`.

Device tests: `./gradlew :app:connectedDebugAndroidTest` runs the one instrumented test (the photo
converter golden master, which needs a real OpenCV native call chain). Macrobenchmarks live in the
`:benchmark` module, with the recorded baseline in `tablet-app/benchmark-baseline.md`.

More about how the code is organised, and why, is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
